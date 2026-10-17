package com.dsh.sportscam.cover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启：把启动器重新投到副屏（对齐原版《超级小副屏》的 BootReceiver）。
 *
 * 注意 Android 10+ 的后台启动 Activity 限制：
 *  - 持有 SYSTEM_ALERT_WINDOW 权限时属于豁免情形，能稳定拉起；
 *  - 否则只能尽力而为（多数 ROM 会静默拦截）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ALLOWED_ACTIONS) return
        Log.d(TAG, "onReceive $action")

        // 先保证保活服务起来（服务内部会在副屏点亮时把启动器拉回来）
        if (CoverSettings.isKeeperEnabled(context)) {
            CoverKeeperService.start(context)
        }

        // 再直接尝试投一次副屏
        if (CoverSettings.isAutoPullEnabled(context)) {
            val pending = goAsync()
            val appContext = context.applicationContext
            Thread {
                try {
                    val coverId = CoverDisplay.coverDisplayId(appContext)
                    if (coverId != android.view.Display.DEFAULT_DISPLAY) {
                        CoverKeeperService.start(appContext)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "开机投屏失败", t)
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }

    companion object {
        private const val TAG = "BootReceiver"

        private val ALLOWED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }
}
