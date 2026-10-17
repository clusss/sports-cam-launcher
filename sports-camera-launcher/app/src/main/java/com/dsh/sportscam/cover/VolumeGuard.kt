package com.dsh.sportscam.cover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

/**
 * 主界面静音守卫。
 *
 * 需求：停在「运动相机」主界面时把音量始终压到 0，并且**不让系统音量条弹出来**；
 * 切到其它界面（相册 / 桌面 / 控制台 / 别的应用）就恢复正常、可以调音量。
 *
 * 两个关键点：
 *  1. **压低音量**：`setStreamVolume(STREAM_MUSIC, 0)`，并注册
 *     `VOLUME_CHANGED_ACTION` 监听 —— 只要有人把音量调上去（其它应用、系统……）
 *     就立刻再压回 0，保证"始终为 0"。
 *  2. **不弹音量条**：音量条是系统在收到音量键时弹出的。我们的悬浮窗是
 *     `FLAG_NOT_FOCUSABLE`，所以副屏上的按键焦点一直在**底下的 Activity** 上，
 *     在 Activity 的 `onKeyDown` 里把音量键消费掉（返回 true）就能阻止它弹出。
 *     见 [com.dsh.sportscam.BaseLauncherActivity.onKeyDown]。
 */
object VolumeGuard {

    /** 当前是否处于"主界面"（由 Compose 侧按当前屏幕 + 开关设置刷新） */
    @Volatile
    var active: Boolean = false
        private set

    /** 进入静音前保存的音量，切走时恢复 */
    private var savedVolume: Int = -1

    private var receiver: BroadcastReceiver? = null

    fun setActive(context: Context, enabled: Boolean) {
        if (enabled == active) return
        active = enabled
        val appContext = context.applicationContext
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        CoverDebugLog.log("Volume", "主界面静音: $enabled")
        if (enabled) {
            // 只记录一下"进入静音前的音量"用于日志，**离开时不恢复**
            // —— 按需求：离开主界面后保持 0，要声音得用户自己调上去。
            savedVolume = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
            forceZero(am)
            register(appContext, am)
        } else {
            unregister(appContext)
            // ⚠ 刻意**不恢复** savedVolume：用户要求离开主界面后仍保持 0。
            CoverDebugLog.log(
                "Volume",
                "离开主界面：保持当前音量（进入静音前为 $savedVolume，不恢复）"
            )
        }
    }

    /** 把音量压到 0（幂等，随时可调） */
    fun silenceNow(context: Context) {
        if (!active) return
        val am = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        forceZero(am)
    }

    private fun forceZero(am: AudioManager) {
        runCatching {
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }
        }
    }

    private fun register(appContext: Context, am: AudioManager) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // 只要不在主界面就放行；在主界面就再压回 0
                if (active) forceZero(am)
            }
        }
        receiver = r
        runCatching {
            appContext.registerReceiver(r, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
            CoverDebugLog.log("Volume", "已监听音量变化，将维持在 0")
        }
    }

    private fun unregister(appContext: Context) {
        val r = receiver ?: return
        receiver = null
        runCatching { appContext.unregisterReceiver(r) }
    }
}
