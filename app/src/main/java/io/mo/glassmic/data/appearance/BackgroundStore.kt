package io.mo.glassmic.data.appearance

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 手机壁纸读取结果。 */
enum class WallpaperStatus {
    /** 尚未读取。 */
    UNKNOWN,
    /** 已取到壁纸图片。 */
    IMAGE,
    /** 读不到图片（动态壁纸 / 无权限 / Root 未授权），退而使用壁纸主色。 */
    COLORS_ONLY,
    /** 什么都没取到，回退默认背景。 */
    FAILED
}

/**
 * 页面背景图片存储。
 *
 * - 自选图片：SAF content:// → 下采样 → 存 filesDir/background/custom_xxx.jpg，只记相对路径。
 * - 手机壁纸：优先 [WallpaperManager.getDrawable]（Android 13+ 普通应用通常无权读取）；
 *   失败时用 Root 直接复制 /data/system/users/<id>/wallpaper；再不行就只取壁纸主色给光晕上色。
 *   以 wallpaperId 判断是否换过壁纸，没变就复用缓存，回到前台时调用 [refreshWallpaper] 即可。
 *
 * 背景会被模糊处理，解码时按长边约 1200px 下采样，单张约 2~3MB。
 */
@Singleton
class BackgroundStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dir: File by lazy {
        File(context.filesDir, "background").apply { if (!exists()) mkdirs() }
    }
    private val wallpaperFile: File get() = File(dir, WALLPAPER_NAME)

    private val _wallpaperStatus = MutableStateFlow(WallpaperStatus.UNKNOWN)
    val wallpaperStatus: StateFlow<WallpaperStatus> = _wallpaperStatus.asStateFlow()

    /** 壁纸主色（读不到图片时给背景光晕上色）。 */
    private val _wallpaperColors = MutableStateFlow<List<Color>>(emptyList())
    val wallpaperColors: StateFlow<List<Color>> = _wallpaperColors.asStateFlow()

    /** 壁纸文件每次刷新后自增，供上层判断需要重新解码。 */
    private val _wallpaperVersion = MutableStateFlow(0)
    val wallpaperVersion: StateFlow<Int> = _wallpaperVersion.asStateFlow()

    private var lastWallpaperId = Int.MIN_VALUE
    /** 启动时「切到壁纸模式」与「回到前台」可能同时触发，串行化避免重复调用 Root。 */
    private val wallpaperLock = Mutex()

    fun file(relativePath: String): File = File(context.filesDir, relativePath)

    /** 导入自选图片；成功返回相对路径。旧的自选图片会被删除，避免堆积。 */
    suspend fun importImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            // ImageDecoder 会按 EXIF 自动转正，也支持 HEIC；软件位图才能再压缩写盘
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longEdge = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (longEdge / (sample * 2) >= TARGET_LONG_EDGE) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            dir.listFiles { f -> f.name.startsWith(CUSTOM_PREFIX) }?.forEach { runCatching { it.delete() } }
            val name = "$CUSTOM_PREFIX${System.currentTimeMillis()}.jpg"
            FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            "background/$name"
        }.onFailure { GlassLog.b("Background") { "导入背景图片失败: ${it.message}" } }.getOrNull()
    }

    /** 删除自选图片。 */
    suspend fun clearImage() = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.startsWith(CUSTOM_PREFIX) }?.forEach { runCatching { it.delete() } }
    }

    /**
     * 读取 / 刷新手机壁纸。壁纸没换过且缓存在时直接返回；[force] 为 true 时忽略缓存。
     */
    suspend fun refreshWallpaper(force: Boolean = false) = wallpaperLock.withLock { refreshWallpaperLocked(force) }

    private suspend fun refreshWallpaperLocked(force: Boolean) = withContext(Dispatchers.IO) {
        val wm = WallpaperManager.getInstance(context)
        val id = runCatching { wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
        if (!force && id == lastWallpaperId && _wallpaperStatus.value != WallpaperStatus.UNKNOWN) return@withContext
        lastWallpaperId = id

        _wallpaperColors.value = readWallpaperColors(wm)
        val ok = readViaWallpaperManager(wm) || readViaRoot()
        _wallpaperStatus.value = when {
            ok -> WallpaperStatus.IMAGE
            _wallpaperColors.value.isNotEmpty() -> WallpaperStatus.COLORS_ONLY
            else -> WallpaperStatus.FAILED
        }
        if (ok) _wallpaperVersion.value += 1
        GlassLog.b("Background") { "壁纸刷新: id=$id status=${_wallpaperStatus.value}" }
    }

    /** 解码背景图（自选图片或壁纸缓存）供界面绘制。 */
    suspend fun loadBitmap(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching { decodeSampled { BitmapFactory.decodeFile(file.absolutePath, it) }?.asImageBitmap() }
            .onFailure { GlassLog.b("Background") { "背景解码失败: ${it.message}" } }
            .getOrNull()
    }

    fun wallpaperCacheFile(): File = wallpaperFile

    private fun readViaWallpaperManager(wm: WallpaperManager): Boolean = runCatching {
        @Suppress("MissingPermission")
        val drawable = wm.drawable ?: return false
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: return false
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: return false
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                drawable.setBounds(0, 0, w, h)
                drawable.draw(Canvas(it))
            }
        }
        FileOutputStream(wallpaperFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        true
    }.onFailure { GlassLog.b("Background") { "WallpaperManager 读取失败: ${it.message}" } }.getOrDefault(false)

    /** Root 直接复制系统壁纸文件（静态壁纸才有）。 */
    private fun readViaRoot(): Boolean = runCatching {
        val userId = Process.myUid() / 100_000
        val src = "/data/system/users/$userId/wallpaper"
        val tmp = File(dir, "$WALLPAPER_NAME.tmp")
        val process = ProcessBuilder("su", "-c", "cat '$src'").start()
        process.inputStream.use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            return false
        }
        // 能解码才算成功，避免把错误输出当图片
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.absolutePath, opts)
        if (process.exitValue() != 0 || opts.outWidth <= 0) {
            tmp.delete()
            return false
        }
        tmp.renameTo(wallpaperFile)
    }.onFailure { GlassLog.b("Background") { "Root 读取壁纸失败: ${it.message}" } }.getOrDefault(false)

    private fun readWallpaperColors(wm: WallpaperManager): List<Color> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return emptyList()
        val c = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return emptyList()
        listOfNotNull(c.primaryColor, c.secondaryColor, c.tertiaryColor).map { Color(it.toArgb()) }
    }.getOrDefault(emptyList())

    /** 按长边约 [TARGET_LONG_EDGE] 以 2 的幂下采样解码，背景要模糊，不需要原图分辨率。 */
    private inline fun decodeSampled(decode: (BitmapFactory.Options?) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return decode(null)
        var sample = 1
        while (longEdge / (sample * 2) >= TARGET_LONG_EDGE) sample *= 2
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val CUSTOM_PREFIX = "custom_"
        private const val WALLPAPER_NAME = "wallpaper.jpg"
        private const val TARGET_LONG_EDGE = 1200
    }
}
