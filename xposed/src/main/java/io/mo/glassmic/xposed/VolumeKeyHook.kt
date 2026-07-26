package io.mo.glassmic.xposed

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import android.view.KeyEvent
import io.github.libxposed.api.XposedInterface
import io.mo.glassmic.core.Constants
import java.lang.reflect.Method

/**
 * 音量键双击快捷操作（在 system_server 内安装）。
 *
 * 双击音量上键 = 播放，双击音量下键 = 暂停。
 *
 * ## 为什么必须住在 system_server
 * 悬浮窗是 TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE，系统根本不会把按键事件派发给它；
 * 音量键更是在派发给任何窗口之前，就被 `PhoneWindowManager#interceptKeyBeforeQueueing`
 * 直接吃掉去调音量了。要拿到它，只能在这个方法上动手。
 *
 * ## 「双击后音量不变」是怎么做到的
 * 判定双击必须等第二下，所以第一下按下时无法立刻知道它是不是双击的一半。做法是：
 * **先把第一下扣住**（返回 0 = 丢弃，音量不动、系统音量条也不弹），同时起一个
 * [Constants.VOLUME_DOUBLE_TAP_WINDOW_MS] 的定时器；
 *  - 窗口内等到第二下 → 判定为双击，广播给 App，两下都不还给系统；
 *  - 窗口内没等到 → 把扣住的那一下补成一次真正的音量调整。
 *
 * 代价是布防期间单次调音量会晚 280ms 生效。**这也是为什么布防条件卡得很死**：
 * 只有「设置开关打开 **且** 悬浮窗正在运行」才拦截（见 [armed]），悬浮窗一关立刻恢复原样。
 *
 * ## 安全边界
 * - 整条路径全在 runCatching / PROTECTIVE 里，任何异常都退回 `chain.proceed()`（= 系统原逻辑）。
 * - 找不到方法、拿不到 Context、读不到 prefs —— 一律当作没开启，绝不吞键。
 * - 长按音量（连续调音量）会被识别为 repeatCount > 0，立刻补发并放行本次按压的后续事件。
 *
 * ## 已知局限
 * 补发走 `adjustSuggestedStreamVolume(USE_DEFAULT_STREAM_TYPE)`，由 AudioService 按当前活跃
 * 播放挑流；这和 PhoneWindowManager 自己那套「通话中优先 VOICE_CALL」的细则不完全等价。
 * 通话中调音量若手感不对，关掉本开关即可。
 */
object VolumeKeyHook {

    private const val TAG = "GlassMic-VolKey"

    /** 布防状态的读取节流：音量键按得再快也用不着比这更新的读数。 */
    private const val PREFS_REFRESH_MS = 500L

    @Volatile private var installed = false

    private var api: XposedInterface? = null
    /** 由模块入口注入：remote preferences 的取用方式属于 XposedModule，这里不自己去够。 */
    private var prefsProvider: (() -> SharedPreferences?)? = null
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var appContext: Context? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // ---- 布防状态缓存（节流读取） ----
    @Volatile private var cachedArmed = false
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedAt = 0L

    // ---- 双击状态机 ----
    // interceptKeyBeforeQueueing 跑在 input 线程，补发定时器跑在 main looper，
    // 因此所有状态都在 [lock] 里改，并用 [generation] 让过期的定时器自己失效。
    private val lock = Any()
    private var pendingKeyCode = 0      // 已扣住、等待判定第二击的键（0 = 无）
    private var swallowingKeyCode = 0   // DOWN 已吞，对应的 UP 也要吞
    private var passThroughKeyCode = 0  // 本次按压已判定为长按，后续事件全部放行
    private var generation = 0

    fun install(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        remotePrefs: () -> SharedPreferences?
    ): Boolean {
        synchronized(this) {
            if (installed) return true

            val method = findInterceptMethod(classLoader)
            if (method == null) {
                xposed.log(Log.WARN, TAG, "interceptKeyBeforeQueueing not found; volume shortcut off")
                return false
            }

            api = xposed
            prefsProvider = remotePrefs
            runCatching {
                xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val decision = runCatching { decide(chain) }.getOrElse {
                            xposed.log(Log.WARN, TAG, "handler error: ${it.message}", it)
                            null
                        }
                        // null = 不干预，交还系统原逻辑；0 = 丢弃该键（音量不动、不弹音量条）
                        decision ?: chain.proceed()
                    }
            }.onFailure {
                xposed.log(Log.WARN, TAG, "hook failed: ${it.message}", it)
                api = null
                prefsProvider = null
                return false
            }

            installed = true
            xposed.log(Log.INFO, TAG, "installed on ${method.declaringClass.name}#${method.name}")
            return true
        }
    }

    /**
     * 返回 null 表示「放行，走系统原逻辑」；返回 0 表示「吞掉这个键」。
     *
     * 0 是 interceptKeyBeforeQueueing 的返回值语义：不带 ACTION_PASS_TO_USER 等任何标志位，
     * 事件既不派发给窗口、也不交给策略层处理，等同于这次按键没发生过。
     */
    private fun decide(chain: XposedInterface.Chain): Int? {
        val event = chain.getArg(0) as? KeyEvent ?: return null
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return null
        }
        if (!armed()) {
            // 未布防：确保状态机是干净的，免得撤防瞬间残留一个扣住的键再也补不回去
            resetIfDirty()
            return null
        }

        // Context 取不到就没法补发音量、也没法广播 —— 那就一律不拦，宁可快捷键不生效
        val ctx = contextOf(chain.getThisObject()) ?: return null

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> onDown(ctx, keyCode, event.repeatCount)
            KeyEvent.ACTION_UP -> onUp(keyCode)
            else -> null
        }
    }

    private fun onDown(ctx: Context, keyCode: Int, repeatCount: Int): Int? {
        if (repeatCount > 0) {
            // 长按连续调音量：把扣住的那一下立刻补上，本次按压之后全部放行
            synchronized(lock) {
                if (swallowingKeyCode != keyCode && passThroughKeyCode != keyCode) return null
                swallowingKeyCode = 0
                passThroughKeyCode = keyCode
            }
            flushPending(ctx)
            return null
        }

        val secondTap: Boolean
        synchronized(lock) {
            secondTap = pendingKeyCode == keyCode
            if (secondTap) {
                // 判定为双击：取消补发（音量因此保持不变），两下都不还给系统
                pendingKeyCode = 0
                generation++
            }
            swallowingKeyCode = keyCode
            passThroughKeyCode = 0
        }

        if (secondTap) {
            handler.removeCallbacksAndMessages(null)
            fire(ctx, keyCode)
            return 0
        }

        // 第一下：可能是双击的一半，也可能只是想调音量。先扣住，到点没等到第二下再补发。
        flushPending(ctx)   // 上一次的残留（换了个键/超时未清）先补掉
        val myGeneration: Int
        synchronized(lock) {
            pendingKeyCode = keyCode
            generation++
            myGeneration = generation
        }
        handler.postDelayed({
            val expired: Boolean
            val code: Int
            synchronized(lock) {
                expired = generation != myGeneration
                code = pendingKeyCode
                if (!expired) pendingKeyCode = 0
            }
            if (!expired && code != 0) adjustVolume(ctx, code)
        }, Constants.VOLUME_DOUBLE_TAP_WINDOW_MS)
        return 0
    }

    private fun onUp(keyCode: Int): Int? {
        synchronized(lock) {
            if (passThroughKeyCode == keyCode) {
                passThroughKeyCode = 0
                return null
            }
            if (swallowingKeyCode == keyCode) {
                swallowingKeyCode = 0
                return 0
            }
        }
        return null
    }

    /** 把还扣着的那一下补成真正的音量调整（若没有则什么也不做）。 */
    private fun flushPending(ctx: Context) {
        val code: Int
        synchronized(lock) {
            code = pendingKeyCode
            if (code == 0) return
            pendingKeyCode = 0
            generation++
        }
        handler.removeCallbacksAndMessages(null)
        adjustVolume(ctx, code)
    }

    /** 撤防时清空状态机；扣住的那一下不能白吞，照样补发。 */
    private fun resetIfDirty() {
        val dirty = synchronized(lock) {
            pendingKeyCode != 0 || swallowingKeyCode != 0 || passThroughKeyCode != 0
        }
        if (!dirty) return
        val ctx = appContext
        if (ctx != null) flushPending(ctx)
        synchronized(lock) {
            pendingKeyCode = 0
            swallowingKeyCode = 0
            passThroughKeyCode = 0
            generation++
        }
    }

    private fun adjustVolume(ctx: Context, keyCode: Int) {
        runCatching {
            val am = ctx.getSystemService(AudioManager::class.java) ?: return
            val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                AudioManager.ADJUST_RAISE
            } else {
                AudioManager.ADJUST_LOWER
            }
            am.adjustSuggestedStreamVolume(
                direction,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
            )
        }.onFailure {
            api?.log(Log.WARN, TAG, "replay volume failed: ${it.message}", it)
        }
    }

    private fun fire(ctx: Context, keyCode: Int) {
        val token = cachedToken ?: return
        val action = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            Constants.SHORTCUT_PLAY
        } else {
            Constants.SHORTCUT_PAUSE
        }
        val intent = Intent(Constants.ACTION_VOLUME_SHORTCUT)
            .setPackage(Constants.APP_PACKAGE)
            .putExtra(Constants.EXTRA_SHORTCUT_ACTION, action)
            .putExtra(Constants.EXTRA_SHORTCUT_TOKEN, token)
        // 刻意**不加** FLAG_INCLUDE_STOPPED_PACKAGES：接收器是悬浮窗服务运行时注册的，
        // App 没在跑就应该什么都不发生。按个音量键把 GlassMic 从冷状态拉起来是明确不要的行为。
        val sent = runCatching {
            val current = UserHandle::class.java.getDeclaredField("CURRENT").get(null) as UserHandle
            Context::class.java
                .getMethod("sendBroadcastAsUser", Intent::class.java, UserHandle::class.java)
                .invoke(ctx, intent, current)
            true
        }.getOrDefault(false)
        // system_server 里直接 sendBroadcast 可能因为「未指定 user」被拒，所以上面优先走
        // sendBroadcastAsUser；反射失败再退回普通广播。
        if (!sent) runCatching { ctx.sendBroadcast(intent) }
        api?.log(Log.INFO, TAG, "double tap -> $action")
    }

    /**
     * 是否布防：设置开关打开 **且** 悬浮窗正在运行。
     *
     * 每次刷新都**重新向模块要一次** remote preferences，而不是把第一次拿到的实例缓存住：
     * LSPosed 的 remote preferences 是否会就地随文件变化而更新，各版本行为不一致；缓存住一个
     * 不刷新的快照会让布防状态永远停在安装那一刻（= 功能彻底不工作）。重取的代价由
     * [PREFS_REFRESH_MS] 节流兜住，且只在真的按了音量键时才发生。
     */
    private fun armed(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - cachedAt < PREFS_REFRESH_MS) return cachedArmed
        cachedAt = now
        val sp = runCatching { prefsProvider?.invoke() }.getOrNull() ?: prefs
        if (sp == null) {
            cachedArmed = false
            return false
        }
        prefs = sp   // 留作下次取不到时的兜底
        runCatching {
            cachedArmed = sp.getBoolean(Constants.KEY_VOLUME_SHORTCUT_ARMED, false)
            cachedToken = sp.getString(Constants.KEY_VOLUME_SHORTCUT_TOKEN, null)
        }.onFailure {
            cachedArmed = false
        }
        return cachedArmed
    }

    private fun contextOf(pwm: Any?): Context? {
        appContext?.let { return it }
        pwm ?: return null
        var cls: Class<*>? = pwm.javaClass
        while (cls != null) {
            val field = runCatching { cls!!.getDeclaredField("mContext") }.getOrNull()
            if (field != null) {
                val ctx = runCatching {
                    field.isAccessible = true
                    field.get(pwm) as? Context
                }.getOrNull()
                if (ctx != null) {
                    appContext = ctx
                    return ctx
                }
            }
            cls = cls.superclass
        }
        api?.log(Log.WARN, TAG, "PhoneWindowManager.mContext not found; volume shortcut off")
        return null
    }

    /**
     * 找 `PhoneWindowManager#interceptKeyBeforeQueueing`。
     *
     * 签名随版本变过（Android 9 之前多一个 `boolean isScreenOn` 参数），所以按「名字 + 返回 int
     * + 第一个参数是 KeyEvent」来匹配，不硬编码参数表。
     */
    private fun findInterceptMethod(cl: ClassLoader): Method? {
        val clazz = sequenceOf(
            "com.android.server.policy.PhoneWindowManager",
            "com.android.server.wm.PhoneWindowManager"
        ).firstNotNullOfOrNull { runCatching { cl.loadClass(it) }.getOrNull() } ?: return null

        return clazz.declaredMethods.firstOrNull { m ->
            m.name == "interceptKeyBeforeQueueing" &&
                m.returnType == Int::class.javaPrimitiveType &&
                m.parameterTypes.firstOrNull() == KeyEvent::class.java
        }
    }
}
