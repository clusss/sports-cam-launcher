package com.dsh.sportscam.cover

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import android.view.Surface

/**
 * 屏幕方向控制。
 *
 * 两个层次，按需要选用：
 *
 *  1. **应用层**（不需要任何权限）：`Activity.requestedOrientation`
 *     —— 只影响本应用自己的窗口。副屏若是倒装的（本机 installOrientation=ROTATION_270、
 *     mDisplayRotation=ROTATION_180），用"反向横屏"就能把画面正过来。
 *
 *  2. **显示器层**（需要 Shizuku/shell）：
 *     `cmd window user-rotation [-d <displayId>] free|lock <0|1|2|3>`
 *     —— 锁定整块副屏的旋转，影响副屏上所有应用。已在本机实测语法可用：
 *     `cmd window user-rotation -d 1` → 回显 `lock 3`。
 */
object OrientationController {

    private const val TAG = "OrientationController"

    /** 应用层方向选项 */
    enum class AppOrientation(val label: String, val activityInfoValue: Int) {
        LANDSCAPE("横屏", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
        REVERSE_LANDSCAPE("反向横屏", ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
        PORTRAIT("竖屏", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        REVERSE_PORTRAIT("反向竖屏", ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT),
        UNSPECIFIED("跟随系统", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    /** 显示器层旋转值（对应 Surface.ROTATION_*） */
    enum class DisplayRotation(val label: String, val value: Int) {
        ROTATION_0("0°", Surface.ROTATION_0),
        ROTATION_90("90°", Surface.ROTATION_90),
        ROTATION_180("180°", Surface.ROTATION_180),
        ROTATION_270("270°", Surface.ROTATION_270)
    }

    /** 读取当前 App 方向（用于 UI 回显） */
    fun currentAppOrientation(activity: Activity): Int =
        activity.requestedOrientation

    /** 设置本 Activity 的方向（立即生效，不需要权限） */
    fun applyAppOrientation(activity: Activity, orientation: AppOrientation) {
        runCatching {
            activity.requestedOrientation = orientation.activityInfoValue
            Log.d(TAG, "app orientation -> ${orientation.label}")
        }.onFailure { Log.w(TAG, "设置应用方向失败", it) }
    }

    /** 由 Configuration 反推当前实际方向，便于 UI 显示 */
    fun describe(configuration: Configuration): String =
        when (configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "横屏"
            Configuration.ORIENTATION_PORTRAIT -> "竖屏"
            else -> "未知"
        }

    // ---------------- 显示器层（需要 Shizuku） ----------------

    /** 锁定副屏旋转 */
    suspend fun lockDisplayRotation(displayId: Int, rotation: DisplayRotation): Boolean {
        val cmd = "cmd window user-rotation lock ${rotation.value} -d $displayId"
        val out = ShizukuShell.exec(cmd)
        Log.d(TAG, "$cmd -> ${out?.trim()}")
        return out != null && !out.contains("Error") && !out.contains("Exception")
    }

    /** 解除锁定，交还给传感器/系统 */
    suspend fun freeDisplayRotation(displayId: Int): Boolean {
        val cmd = "cmd window user-rotation free -d $displayId"
        val out = ShizukuShell.exec(cmd)
        Log.d(TAG, "$cmd -> ${out?.trim()}")
        return out != null && !out.contains("Error") && !out.contains("Exception")
    }

    /** 查询当前锁定状态，回显形如 "lock 3" / "free" */
    suspend fun queryDisplayRotation(displayId: Int): String? =
        ShizukuShell.exec("cmd window user-rotation -d $displayId")?.trim()
}
