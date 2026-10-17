package com.dsh.sportscam.cover

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 副屏上的轻提示。
 *
 * 为什么不用系统 `Toast`：
 *  1. 悬浮窗模式下用**悬浮窗的窗口上下文**（type 2038）弹 Toast 会抛
 *     `IllegalArgumentException: Window type mismatch ... 2005` 直接崩进程；
 *  2. 换成 `applicationContext` 虽然不崩，但 Toast 会落到**默认显示器**上 ——
 *     这台机器的主屏没有面板，等于提示根本看不见。
 *
 * 所以自己用一个小的 `TYPE_APPLICATION_OVERLAY` 在副屏底部显示 1.6 秒：
 * 不抢焦点、不吃触摸、自动消失。
 */
object CoverToast {

    private const val DURATION_MS = 1_600L

    private val handler = Handler(Looper.getMainLooper())
    private var current: View? = null
    private var currentWm: WindowManager? = null
    private val hideRunnable = Runnable { hideNow() }

    fun show(context: Context, message: String) {
        val appContext = context.applicationContext
        handler.post {
            runCatching { showNow(appContext, message) }
                .onFailure { CoverDebugLog.log("Toast", "显示失败: ${it.message}") }
        }
    }

    private fun showNow(appContext: Context, message: String) {
        if (!Settings.canDrawOverlays(appContext)) return
        val coverId = CoverDisplay.coverDisplayId(appContext)
        if (coverId == android.view.Display.DEFAULT_DISPLAY) return

        val dm = appContext.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = runCatching { dm?.getDisplay(coverId) }.getOrNull() ?: return

        var ctx: Context = appContext.createDisplayContext(display)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ctx = runCatching {
                ctx.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            }.getOrDefault(ctx)
        }
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return

        hideNow()

        val textView = TextView(ctx).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(28, 14, 28, 14)
            setBackgroundColor(Color.argb(230, 30, 30, 32))
        }

        // ★ 竖屏时提示必须跟着转 −90°（用户实测：竖屏下主界面「相簿 #2」单击弹出的
        //   「暂无照片/视频」是**与屏幕长边垂直**的 ✗）。根因：启动器内容整体逆时针 −90°，
        //   而本提示是往副屏直接加的裸 TextView，从不旋转 → 就成了唯一"横着"的文字。
        //
        // ⚠ 不能只写 textView.rotation = -90f：View.rotation **不改测量尺寸** ✗，
        //   TextView 会被自己的窗口边界裁掉。正确做法：套一个**尺寸互换**的容器 ——
        //   先按自然尺寸量一次文字，再把容器宽高互换（= 旋转 90° 后的占位），
        //   onLayout 里把文字中心对准容器中心，旋转后正好铺满。
        val portrait = CoverDisplay.isPortraitNow(appContext)
        val root: View = if (!portrait) {
            textView
        } else {
            object : android.widget.FrameLayout(ctx) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    textView.measure(
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                    )
                    setMeasuredDimension(textView.measuredHeight, textView.measuredWidth)
                }

                override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                    textView.layout(0, 0, textView.measuredWidth, textView.measuredHeight)
                    // rotation 绕自身中心 → 把它的中心对准容器中心
                    textView.translationX = (width - textView.measuredWidth) / 2f
                    textView.translationY = (height - textView.measuredHeight) / 2f
                }
            }.apply {
                clipChildren = false
                addView(textView)
                textView.rotation = -90f
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // ★ 居中显示：原来放在底部会压住轮播图标下方的文字（"暂无照片" 与标签重叠）
            gravity = Gravity.CENTER
        }

        // 注意：加/移除的必须是 root（旋转容器包着 textView）
        runCatching { wm.addView(root, params) }
            .onSuccess {
                current = root
                currentWm = wm
                handler.removeCallbacks(hideRunnable)
                handler.postDelayed(hideRunnable, DURATION_MS)
            }
            .onFailure { CoverDebugLog.log("Toast", "addView 失败: ${it.message}") }
    }

    private fun hideNow() {
        handler.removeCallbacks(hideRunnable)
        val v = current
        val wm = currentWm
        current = null
        currentWm = null
        if (v != null && wm != null) runCatching { wm.removeViewImmediate(v) }
    }
}
