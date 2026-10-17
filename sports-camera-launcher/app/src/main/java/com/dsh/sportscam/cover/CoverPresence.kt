package com.dsh.sportscam.cover

/**
 * 进程内共享的"副屏界面此刻是否真的在前台"标记。
 *
 * 为什么需要它：
 * 保活服务里的自动拉起逻辑（副屏点亮 / 折叠 / 前台轮询）如果只按
 * "副屏亮了"或"焦点不在我们这"来判断，就会**自激** ——
 * 因为在副屏上，我们自己那个 0×0 的方向锁定悬浮窗（TYPE_APPLICATION_OVERLAY）
 * 会持有窗口焦点，于是 `hasWindowFocus()` 恒为 false、`onDisplayChanged`
 * 又会因为我们界面自己引起的显示变化而反复回调，结果就是反复把自己重新拉起，
 * 用户看到界面**反复跳动**。
 *
 * ⚠ 第一版用"displayId 相等"来清除标记，出过一个致命 bug：
 * 只要 onPause 时的 displayId 与 onResume 时记录的不一致（配置变化、被搬屏、
 * 时序问题都会导致），标记就**永久残留**，跳板会误判"副屏界面已在前台"
 * 而直接退场 —— 表现就是"快捷启动放了段动画，界面完全不上来"。
 * 现在改成**计数**：只有计数归零才算不在了，绝不会因为一次不匹配而泄漏。
 */
object CoverPresence {

    /** 当前处于 RESUMED 的副屏界面数量 */
    @Volatile
    private var resumedCount: Int = 0

    /** 最近一次 RESUMED 的副屏界面所在屏幕 */
    @Volatile
    private var resumedDisplayId: Int = -1

    /**
     * 每次 onResume 自增的令牌。用于"我发起投放之后，到底有没有东西真的前台起来"
     * 的验证 —— 仅靠 `startActivity` 没抛异常是**不可信**的，系统会静默丢弃启动。
     */
    @Volatile
    private var resumeToken: Long = 0L

    fun onResumed(displayId: Int) {
        resumedDisplayId = displayId
        resumedCount++
        resumeToken++
    }

    /** ⚠ 不带参数：任何一次 onPause 都计数减一，绝不因为 displayId 不匹配而漏清 */
    fun onPaused() {
        resumedCount = (resumedCount - 1).coerceAtLeast(0)
        if (resumedCount == 0) resumedDisplayId = -1
    }

    /** 副屏界面此刻是否正前台显示在指定屏幕上 */
    fun isResumedOn(displayId: Int): Boolean = resumedCount > 0 && resumedDisplayId == displayId

    /** 当前令牌。投放前后对比它，就能知道"期间是否有界面真的 resume 了" */
    fun token(): Long = resumeToken

    /** 调试用：把内部状态拼成一行，便于落盘排查 */
    fun debugState(): String = "count=$resumedCount display=$resumedDisplayId token=$resumeToken"
}
