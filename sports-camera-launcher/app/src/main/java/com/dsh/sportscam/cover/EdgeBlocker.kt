package com.dsh.sportscam.cover

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 边缘手势锁定（防误退）。
 *
 * 原理：在副屏的**左 / 右 / 下**三边各挂一条透明、不可获焦、可接收触摸的
 * `TYPE_APPLICATION_OVERLAY` 悬浮窗，把这些区域里的系统手势
 * （左右边缘返回、底部上滑回桌面）**吃掉**，从而避免在启动器里误触退出。
 *
 * 几个刻意的设计：
 *  · **顶边不拦** —— 否则下拉通知栏就废了，而通知栏正是方向开关的入口；
 *  · 条带很薄（[EDGE_DP] ≈ 18dp），只覆盖手势感应区，尽量不影响正常点击；
 *  · **不可获焦**（FLAG_NOT_FOCUSABLE）—— 绝对不能重演"悬浮窗抢输入焦点导致
 *    输入法弹不出来"那个事故；
 *  · 只在启动器**处于前台**时存在（由保活服务的守卫循环按 [CoverPresence] 决定），
 *    这样绝不会把用户关在别的应用里。
 */
object EdgeBlocker {

    private const val TAG = "EdgeBlocker"
    private const val EDGE_DP = 18f

    private val attached = ArrayList<Pair<View, WindowManager>>()

    @Volatile
    var isShowing: Boolean = false
        private set

    /** 挂出三边屏障 */
    fun show(context: Context, displayId: Int) {
        if (isShowing) return
        if (!Settings.canDrawOverlays(context)) {
            CoverDebugLog.log(TAG, "show: 没有悬浮窗权限")
            return
        }

        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = runCatching { dm?.getDisplay(displayId) }.getOrNull()
        if (display == null) {
            CoverDebugLog.log(TAG, "show: 找不到副屏 $displayId")
            return
        }

        // 与方向锁定悬浮窗同样的构造方式：必须用窗口上下文，
        // 只做 createDisplayContext 的话 addView 会被静默丢弃。
        var ctx: Context = context.createDisplayContext(display)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ctx = runCatching {
                ctx.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            }.getOrDefault(ctx)
        }
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return

        val metrics = ctx.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val edge = (EDGE_DP * metrics.density).toInt().coerceAtLeast(6)

        fun add(x: Int, y: Int, w: Int, h: Int, name: String) {
            val view = View(ctx).apply {
                // alpha=1 几乎不可见，但保证视图参与命中测试（完全透明也照样收触摸）
                setBackgroundColor(Color.argb(1, 0, 0, 0))
            }
            val lp = WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
                title = name
            }
            runCatching { wm.addView(view, lp) }
                .onSuccess { attached += view to wm }
                .onFailure { CoverDebugLog.log(TAG, "addView($name) 失败: ${it.message}") }
        }

        add(0, 0, edge, screenH, "cover-block-left")
        add(screenW - edge, 0, edge, screenH, "cover-block-right")
        add(0, screenH - edge, screenW, edge, "cover-block-bottom")

        isShowing = attached.isNotEmpty()
        CoverDebugLog.log(
            TAG,
            "show: 挂出 ${attached.size} 条边缘屏障 display=$displayId " +
                "size=${screenW}x$screenH edge=$edge"
        )
    }

    /** 收回全部屏障 */
    fun hide(reason: String = "") {
        if (attached.isEmpty()) {
            isShowing = false
            return
        }
        attached.forEach { (view, wm) ->
            runCatching { wm.removeViewImmediate(view) }
        }
        val n = attached.size
        attached.clear()
        isShowing = false
        CoverDebugLog.log(TAG, "hide: 收回 $n 条屏障（$reason）")
    }
}
