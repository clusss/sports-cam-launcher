package com.dsh.sportscam.device

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.Display

/**
 * Intent 启动工具。
 *
 * 副屏适配的关键点：副屏上的 Activity 通过 `displayId` 启动目标应用时，
 * 系统会尝试在**同一块屏幕**上创建任务，从而避免 OriginOS 弹出
 * 「展开手机继续 / 打开手机以继续」。
 */
object IntentLauncher {

    private const val TAG = "IntentLauncher"

    /** Open Camera 包名（需求：拍摄按钮打开它，而不是系统相机） */
    const val OPEN_CAMERA_PACKAGE = "net.sourceforge.opencamera"

    /**
     * 在指定 Display 上启动 Intent。
     * @return true 表示成功启动
     */
    fun launch(context: Context, intent: Intent, displayId: Int? = null): Boolean {
        // 从 Home（非 Activity 栈顶）启动必须带 NEW_TASK
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        // 先尝试带 displayId 启动（副屏关键）
        if (displayId != null && displayId != Display.DEFAULT_DISPLAY) {
            try {
                val options = ActivityOptions.makeBasic().apply {
                    setLaunchDisplayId(displayId)
                }
                context.startActivity(intent, options.toBundle())
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "带 displayId 启动失败，退化为默认启动", t)
            }
        }

        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "启动失败: $intent", t)
            false
        }
    }

    /**
     * 打开相机。
     * 依次尝试：
     *  1. Open Camera（net.sourceforge.opencamera）—— 需求指定
     *  2. MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA （系统相机拍照界面，兜底）
     *  3. MediaStore.ACTION_IMAGE_CAPTURE         （单张拍摄，再兜底）
     *
     * 用 getLaunchIntentForPackage 拿启动入口（比写死 Activity 名稳），
     * 并带上 displayId 让它直接开在副屏上。
     */
    fun openCamera(context: Context, displayId: Int? = null): Boolean {
        val openCameraIntent = context.packageManager
            .getLaunchIntentForPackage(OPEN_CAMERA_PACKAGE)
        if (openCameraIntent != null) {
            openCameraIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            if (launch(context, openCameraIntent, displayId)) return true
            Log.w(TAG, "Open Camera 启动失败，回退系统相机")
        } else {
            Log.w(TAG, "未安装 $OPEN_CAMERA_PACKAGE，回退系统相机")
        }

        val still = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (launch(context, still, displayId)) return true

        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (launch(context, capture, displayId)) return true

        return false
    }

    /** 用系统播放器播放视频 */
    fun playVideo(context: Context, uri: Uri, displayId: Int? = null): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(context, intent, displayId)
    }

    /** 启动已安装应用（应用列表用） */
    fun launchApp(context: Context, packageName: String, displayId: Int? = null): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return launch(context, intent, displayId)
    }
}
