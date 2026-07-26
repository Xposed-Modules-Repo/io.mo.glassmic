package io.mo.glassmic.data.runtime

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.Constants
import io.mo.glassmic.log.GlassLog
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音量键快捷操作的「布防」状态。
 *
 * 键事件只有 system_server 里的 PhoneWindowManager 拿得到，所以拦截逻辑住在 Xposed 侧
 * （VolumeKeyHook）。这个类负责把 App 侧的两条信息递过去，走 LSPosed remote preferences
 * （App 用 MODE_WORLD_READABLE 写、system_server 只读）：
 *
 *  1. **armed** —— 设置开关打开 **且** 悬浮窗正在运行。为 false 时模块侧一律直接放行，
 *     音量键行为和没装模块完全一样。悬浮窗一关就撤防，把误伤面压到最小。
 *  2. **token** —— 每次布防重新生成的随机串。模块把它带在广播里回传，App 侧核对通过才执行。
 *     接收器必须 EXPORTED 才收得到 system_server 的广播，token 用来挡第三方 App 伪造。
 *
 * 与 [VisibilityCompatRepository] 不同，这里不需要 `persist` 系统属性：那条路是为了让
 * system_server 在**开机极早期**（onSystemServerStarting）也能读到，而布防状态是开机之后
 * 才由悬浮窗改写的，remote preferences 在那个阶段是可靠的。
 */
@Singleton
class VolumeShortcutRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        @Suppress("DEPRECATION", "WorldReadableFiles")
        runCatching {
            context.getSharedPreferences(Constants.REMOTE_PREFS, Context.MODE_WORLD_READABLE)
        }.getOrElse {
            // 退回 MODE_PRIVATE 只是为了不崩——此时 system_server 读不到，功能相当于没开。
            GlassLog.b("VolKey") { "remote prefs 不可写（模块未激活？），音量键快捷键将不生效" }
            context.getSharedPreferences(Constants.REMOTE_PREFS, Context.MODE_PRIVATE)
        }
    }

    private val random = SecureRandom()

    /**
     * 布防 / 撤防。布防时顺带换一枚新 token，撤防时把 armed 落为 false（token 留着无妨）。
     *
     * 用 commit() 而非 apply()：撤防发生在悬浮窗 onDestroy，进程随时可能被回收，
     * 异步写有可能丢掉，留下一个「悬浮窗已关但音量键还被拦」的状态。
     */
    fun setArmed(armed: Boolean) {
        runCatching {
            val editor = prefs.edit().putBoolean(Constants.KEY_VOLUME_SHORTCUT_ARMED, armed)
            if (armed) editor.putString(Constants.KEY_VOLUME_SHORTCUT_TOKEN, newToken())
            editor.commit()
        }.onFailure {
            GlassLog.b("VolKey") { "写布防状态失败: ${it.message}" }
        }
    }

    /** 校验模块回传的 token。token 缺失或对不上一律拒绝执行。 */
    fun verifyToken(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val expected = runCatching {
            prefs.getString(Constants.KEY_VOLUME_SHORTCUT_TOKEN, null)
        }.getOrNull()
        return !expected.isNullOrEmpty() && expected == token
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
