package com.dsh.sportscam.cover

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「通知栏截屏」用的**透明 Activity**。
 *
 * 为什么不用广播接收器（上一版的做法，用户实测踩到两个问题）：
 *  1. **广播 PendingIntent 不会收起通知栏** ✗ —— 用户点完按钮，通知栏还盖在副屏上，
 *     截出来的图全被通知栏遮住；
 *  2. 广播里做截屏还得 `goAsync()` 硬撑生命周期，不优雅。
 *
 * 启动 Activity 则会被系统**自动收起通知栏** ✓（这是系统行为，不需要任何权限）。
 * 所以：点按钮 → 启动本 Activity（透明、无标题、不进最近任务、不显示动画）
 *      → 等通知栏收完 → 调 [CoverScreenshot] 截图 → 轻提示 → 立刻 finish()。
 *
 * 注意：本 Activity 必须启动在**副屏**上，界面那边的 PendingIntent 已经带了
 * `ActivityOptions.setLaunchDisplayId(副屏id)`（否则会落到没有面板的主屏，通知栏不会收）。
 */
class CoverShotActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 完全透明：不画任何东西，用户只会看到通知栏收起来
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        scope.launch {
            // 等通知栏收起动画结束（系统约 200~300ms），否则截图里还会带上它
            delay(COLLAPSE_WAIT_MS)
            val result = runCatching { CoverScreenshot.capture(this@CoverShotActivity) }
                .getOrElse { CoverScreenshot.Result.Failure(it.message ?: "未知错误") }
            when (result) {
                is CoverScreenshot.Result.Success -> {
                    CoverDebugLog.log("Shot", "已保存 ${result.path}（通道 ${result.via}）")
                    CoverToast.show(this@CoverShotActivity, "已截屏")
                }
                is CoverScreenshot.Result.Failure -> {
                    CoverDebugLog.log("Shot", "失败：${result.reason}")
                    CoverToast.show(this@CoverShotActivity, "截屏失败：${result.reason}")
                }
            }
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        /** 等通知栏收起的时长；太短会把通知栏截进去 */
        const val COLLAPSE_WAIT_MS = 500L
    }
}
