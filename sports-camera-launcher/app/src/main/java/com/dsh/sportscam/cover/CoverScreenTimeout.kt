package com.dsh.sportscam.cover

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 副屏「自动息屏」。
 *
 * 为什么需要它（用户实测反馈）：
 *   悬浮窗模式下，副屏上一直盖着我们的 2038 全屏悬浮窗，系统不把这当成"空闲"，
 *   于是**屏幕不会自动熄灭** ✗ → 一直亮着很耗电。
 *
 * 双保险：
 *  ① **写系统超时** `Settings.System.SCREEN_OFF_TIMEOUT`：让系统自己按这个时长息屏。
 *     优先用 Shizuku（`settings put system screen_off_timeout <ms>`，shell 有权限 ✓，不需要用户点任何东西）；
 *     没 Shizuku 时退回 `Settings.System.putInt`（需要「修改系统设置」权限，`Settings.System.canWrite`）。
 *  ② **自己的看门狗**（[CoverKeeperService] 里）：只要"启动器在副屏前台"，且距上次交互超过设定时长，
 *     就主动按一次电源键（`input keyevent 223` = KEYCODE_SLEEP，走 Shizuku）。
 *     这一条对付"ROM 在悬浮窗下忽略系统超时"的情况 —— 那正是用户遇到的现象。
 *
 * 交互时间由 [markInteraction] 记录：副屏界面 Activity 的 `onUserInteraction()` 与
 * 悬浮窗的触摸都会调用它。
 */
object CoverScreenTimeout {

    private const val TAG = "ScreenTimeout"

    /** 「永不息屏」用的超大值（Android 允许 2147483647） */
    const val NEVER_MS = Int.MAX_VALUE.toLong()

    /** 可选档位（毫秒），顺序即控制台显示顺序 */
    val PRESETS: List<Pair<String, Long>> = listOf(
        "15 秒" to 15_000L,
        "30 秒" to 30_000L,
        "1 分钟" to 60_000L,
        "5 分钟" to 300_000L,
        "永不息屏" to NEVER_MS
    )

    @Volatile
    private var lastInteractionAt: Long = System.currentTimeMillis()

    /** 用户和界面有交互（触摸/按键）时调用 */
    fun markInteraction() {
        lastInteractionAt = System.currentTimeMillis()
    }

    fun msSinceInteraction(): Long = System.currentTimeMillis() - lastInteractionAt

    /**
     * 把设定值套用到系统。
     * @return 给用户看的结果说明（成功/失败原因）
     */
    suspend fun apply(context: Context, timeoutMs: Long): String {
        val value = timeoutMs.coerceAtLeast(1_000L)

        // ① 首选 Shizuku：不需要用户授权任何东西
        if (ShizukuShell.isUsable) {
            val out = ShizukuShell.exec("settings put system screen_off_timeout $value")
            val check = ShizukuShell.exec("settings get system screen_off_timeout")?.trim()
            if (check == value.toString()) {
                CoverDebugLog.log(TAG, "已用 Shizuku 设置系统息屏超时 = $value ms")
                return "已设置：${labelOf(timeoutMs)}"
            }
            CoverDebugLog.log(TAG, "Shizuku 写入后回读不符：$check（out=${out?.trim()}）")
        }

        // ② 退回直接写系统设置（需要「修改系统设置」权限）
        if (Settings.System.canWrite(context)) {
            val ok = runCatching {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    value.toInt()
                )
            }.getOrDefault(false)
            if (ok) {
                CoverDebugLog.log(TAG, "已用 Settings.System 写入息屏超时 = $value ms")
                return "已设置：${labelOf(timeoutMs)}"
            }
        }

        return "已保存，但没能改到系统设置：请授予「修改系统设置」权限（控制台「自动息屏」下方有按钮），或用 Shizuku"
    }

    fun labelOf(timeoutMs: Long): String =
        PRESETS.firstOrNull { it.second == timeoutMs }?.first ?: "${timeoutMs / 1000} 秒"

    /** 当前系统里的息屏超时（毫秒），读不到返回 -1 */
    fun systemTimeoutMs(context: Context): Long = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT).toLong()
    }.getOrDefault(-1L)

    /**
     * 主动熄屏（看门狗用）：走 Shizuku 发一次 KEYCODE_SLEEP。
     * 没有 Shizuku 时退而求其次用 `PowerManager` 的 `goToSleep`（多数机型需要系统权限，通常失败）。
     */
    suspend fun sleepNow(context: Context): Boolean {
        if (ShizukuShell.isUsable) {
            // 223 = KEYCODE_SLEEP
            ShizukuShell.exec("input keyevent 223")
            CoverDebugLog.log(TAG, "看门狗：已发送 KEYCODE_SLEEP（Shizuku）")
            return true
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                false // PowerManager.goToSleep 需要 DEVICE_POWER，普通应用别试
            } else false
        }.getOrDefault(false).also {
            if (!it) CoverDebugLog.log(TAG, "看门狗：没有 Shizuku，无法主动熄屏")
        }
    }

    /** 屏幕是否亮着（避免重复发熄屏） */
    fun isScreenOn(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive
    }.getOrDefault(true)
}
