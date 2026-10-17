package com.dsh.sportscam.cover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.util.Log
import kotlinx.coroutines.launch

/**
 * 通知栏方向按钮的接收器。
 *
 * 主机制＝**悬浮窗锁方向**（[ScreenOrientationOverlay]，对齐原版、不需要 Shizuku）；
 * 若没有悬浮窗权限但有 Shizuku，则退化为 `cmd window user-rotation lock`。
 */
class OrientationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            OrientationNotification.ACTION_SCREENSHOT -> {
                doScreenshot(context)
                return
            }
            OrientationNotification.ACTION_ORIENTATION -> doOrientation(context, intent)
            else -> return
        }
    }

    /**
     * 截屏：走 Shizuku 在**副屏**抓图（见 [CoverScreenshot]）。
     * 截屏要做多次 shell 往返，不能在 onReceive 里同步做完 —— 用 goAsync() 延长生命周期，
     * 放到 IO 线程执行，完成后 pendingResult.finish()。
     */
    private fun doScreenshot(context: Context) {
        val appContext = context.applicationContext
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val result = runCatching { CoverScreenshot.capture(appContext) }
                .getOrElse { CoverScreenshot.Result.Failure(it.message ?: "未知错误") }
            when (result) {
                is CoverScreenshot.Result.Success -> {
                    CoverDebugLog.log("Shot", "已保存 ${result.path}（通道 ${result.via}）")
                    CoverToast.show(appContext, "已截屏")
                }
                is CoverScreenshot.Result.Failure -> {
                    CoverDebugLog.log("Shot", "失败：${result.reason}")
                    CoverToast.show(appContext, "截屏失败：${result.reason}")
                }
            }
            runCatching { pending.finish() }
        }
    }

    private fun doOrientation(context: Context, intent: Intent) {
        val orientation = intent.getIntExtra(
            OrientationNotification.EXTRA_ORIENTATION,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        )
        Log.d(TAG, "通知栏选择方向 -> $orientation")

        CoverSettings.setOrientationLock(context, orientation)
        applyOrientation(context, orientation)

        // 通知内容里有"当前 xxx"，需要刷新
        OrientationNotification.show(context)
    }

    companion object {
        private const val TAG = "OrientationReceiver"

        /**
         * 套用方向锁。给服务/界面/接收器共用。
         * @return 走的哪条通道，便于回显
         */
        fun applyOrientation(context: Context, orientation: Int): String {
            val coverId = CoverDisplay.coverDisplayId(context)

            // ① 悬浮窗（需要 SYSTEM_ALERT_WINDOW；targetSdk 29 才生效）
            if (ScreenOrientationOverlay.canDrawOverlays(context)) {
                val ok = if (orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    ScreenOrientationOverlay.restore(context)
                } else {
                    ScreenOrientationOverlay.apply(context, orientation, coverId)
                }
                if (ok) return "overlay"
            }

            // ② Shizuku 兜底：锁整块显示器
            if (ShizukuShell.isUsable) {
                val rotation = when (orientation) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> 0
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> 1
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> 2
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> 3
                    else -> -1
                }
                if (rotation < 0) {
                    // 跟随系统 → 解除锁定
                    kotlinx.coroutines.runBlocking {
                        OrientationController.freeDisplayRotation(coverId)
                    }
                    return "shizuku-free"
                }
                kotlinx.coroutines.runBlocking {
                    OrientationController.lockDisplayRotation(
                        coverId,
                        OrientationController.DisplayRotation.entries.first { it.value == rotation }
                    )
                }
                return "shizuku"
            }

            return "none"
        }
    }
}
