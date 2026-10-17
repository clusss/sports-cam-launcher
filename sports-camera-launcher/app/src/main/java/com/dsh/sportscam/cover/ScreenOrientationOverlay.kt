package com.dsh.sportscam.cover

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager

/**
 * 悬浮窗锁方向 —— 完全对齐《超级小副屏》的 `ScreenOrientationController`。
 *
 * 原理：加一个 **0×0 的透明 TYPE_APPLICATION_OVERLAY 窗口**，把
 * `WindowManager.LayoutParams.screenOrientation` 设成目标方向，
 * WindowManager 就会去锁**整块显示器**的方向（影响副屏上的所有应用）。
 *
 * 反编译原文（`ScreenOrientationController.java:113-119`）：
 * ```
 * lp.screenOrientation = screenOrientation;
 * if (added) { wm.removeView(view); added = false; }
 * wm.addView(view, lp);      // 每次都 removeView + addView 重建
 * ```
 * 窗口参数（`:181`）：`LayoutParams(0,0,0,0,2038,312,-3)`
 *   = 0×0、TYPE_APPLICATION_OVERLAY、FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS。
 *
 * ⚠ 前提：**targetSdk ≤ 30**。Android 12 起，对 targetSdk≥31 的非 trusted 覆盖窗，
 *   这个字段会被忽略 —— 本工程已按需把 targetSdk 降到 29（见 app/build.gradle）。
 *   另有 `Settings.canDrawOverlays()` 这个硬门禁。
 */
object ScreenOrientationOverlay {

    private const val TAG = "OrientationOverlay"

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var attachedManager: WindowManager? = null

    /** 当前锁定值（ActivityInfo.SCREEN_ORIENTATION_*），UNSPECIFIED 表示没锁 */
    @Volatile
    var current: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        private set

    fun canDrawOverlays(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /**
     * 套用方向。orientationValue 取 ActivityInfo.SCREEN_ORIENTATION_*；
     * 传 UNSPECIFIED 等价于 restore()。
     */
    fun apply(context: Context, orientationValue: Int, displayId: Int): Boolean {
        if (orientationValue == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            return restore(context)
        }
        if (!canDrawOverlays(context)) {
            Log.w(TAG, "没有悬浮窗权限，无法锁方向")
            return false
        }
        return runCatching {
            val displayContext = displayContextOf(context, displayId)
            val wm = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val view = overlayView ?: View(displayContext).also { overlayView = it }
            val lp = overlayParams ?: WindowManager.LayoutParams(
                0, 0,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // ★ 必须带 NOT_FOCUSABLE | NOT_TOUCHABLE（原版参数是 312 = 0x138）：
                //   漏掉这两个 flag 时，这个 0×0 的悬浮窗会**抢走输入焦点**
                //   （dumpsys 里 mCurrentFocus 一直是它），后果是：
                //   · 从本启动器打开别的应用后，点输入框**输入法弹不出来**；
                //   · 本应用被杀掉后才恢复正常 —— 正是"焦点被我们占着"的典型症状。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).also { overlayParams = it }

            lp.screenOrientation = orientationValue

            // 每次都 remove + add 重建（原版就是这么做的）
            attachedManager?.let { old ->
                runCatching { old.removeView(view) }
            }
            wm.addView(view, lp)
            attachedManager = wm
            current = orientationValue
            Log.d(TAG, "锁定 display $displayId 方向 -> $orientationValue")
            true
        }.onFailure { Log.w(TAG, "锁方向失败", it) }.getOrDefault(false)
    }

    /** 解除锁定（移除悬浮窗） */
    fun restore(context: Context): Boolean = runCatching {
        val view = overlayView
        val wm = attachedManager
        if (view != null && wm != null) {
            runCatching { wm.removeView(view) }
        }
        attachedManager = null
        overlayView = null
        overlayParams = null
        current = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        Log.d(TAG, "已解除方向锁定")
        true
    }.onFailure { Log.w(TAG, "解除方向锁定失败", it) }.getOrDefault(false)

    /**
     * 取目标显示器对应的 Context。
     * 对齐原版 `ScreenOrientationController.displayContext()`：
     * createDisplayContext(display) →（API 30+）createWindowContext(TYPE_APPLICATION_OVERLAY)
     */
    private fun displayContextOf(context: Context, displayId: Int): Context {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display: Display = dm?.let { manager ->
            runCatching { manager.getDisplay(displayId) }.getOrNull()
                ?: runCatching { manager.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
        } ?: return context

        var ctx = context.createDisplayContext(display)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ctx = runCatching {
                ctx.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            }.getOrDefault(ctx)
        }
        return ctx
    }
}
