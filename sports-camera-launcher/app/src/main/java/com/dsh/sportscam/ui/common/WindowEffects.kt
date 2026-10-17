package com.dsh.sportscam.ui.common

import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

/**
 * 轻提示。
 *
 * 优先用 [com.dsh.sportscam.cover.CoverToast]（自己挂在副屏上的小悬浮窗）：
 *  · 系统 Toast 用悬浮窗上下文弹会因窗口类型冲突（2005 vs 2038）**崩进程**；
 *  · 换成 applicationContext 虽然不崩，却会显示在默认显示器上 ——
 *    这台机器主屏没有面板，等于看不见。
 * 只有拿不到副屏时才退回系统 Toast。
 */
fun showToast(context: Context, message: String) {
    runCatching {
        val coverId = com.dsh.sportscam.cover.CoverDisplay.coverDisplayId(context)
        if (coverId != android.view.Display.DEFAULT_DISPLAY && message.isNotEmpty()) {
            com.dsh.sportscam.cover.CoverToast.show(context, message)
        } else {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 延迟弹出的轻提示。
 *
 * 用途：轮播上"暂无照片/视频"这类提示 —— 单击可能只是双击/多击的**第一下**，
 * 立刻弹提示会很突兀（例如"相簿 #2"双击要清后台，若第一下就冒"暂无照片"，
 * 用户会先看到一个莫名其妙的提示）。统一延后 [delayMs] 再弹。
 */
fun showToastDelayed(context: Context, message: String, delayMs: Long = 400L) {
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
        { showToast(context, message) },
        delayMs
    )
}

/**
 * 是否已授予全部权限。
 *
 * ⚠ 会**先过滤掉当前系统上根本不存在的权限**：带 `maxSdkVersion` 的权限（例如
 * `WRITE_EXTERNAL_STORAGE maxSdkVersion=29`）在更高版本上不在 `requestedPermissions`
 * 里，`checkSelfPermission` 永远返回"未授予" —— 若直接纳入判断，界面会永远停在
 * "需要授权"（实测踩过：Android 13 上相簿一直要求授权）。
 */
fun hasPermissions(context: Context, permissions: List<String>): Boolean {
    val declared = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            ?: emptySet()
    }.getOrDefault(emptySet())

    return permissions
        .filter { it in declared }
        .all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}

/**
 * 屏蔽左右边缘返回手势。
 *
 * 通过 View.setSystemGestureExclusionRects 向系统申请「边条区域不接收返回手势」，
 * 每次最多 200dp / 边。主界面启用；进入相册、应用列表等全屏界面时应关闭，
 * 让用户能正常返回。
 */
@Composable
fun EdgeGestureExclusion(enabled: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    DisposableEffect(enabled, view) {
        if (enabled) {
            // 等布局完成后再取宽高
            view.post { applyEdgeExclusion(view, true) }
        } else {
            applyEdgeExclusion(view, false)
        }
        onDispose { applyEdgeExclusion(view, false) }
    }
}

private fun applyEdgeExclusion(view: View, enabled: Boolean) {
    if (!enabled) {
        ViewCompat.setSystemGestureExclusionRects(view, emptyList())
        return
    }
    val w = view.width
    val h = view.height
    if (w <= 0 || h <= 0) return
    // 50px（约 25dp@density2），但不超过屏宽的 1/6，避免两侧占用过多触摸区
    val edge = minOf((50 * view.resources.displayMetrics.density).toInt(), w / 6)
    ViewCompat.setSystemGestureExclusionRects(
        view,
        listOf(
            android.graphics.Rect(0, 0, edge, h),
            android.graphics.Rect(w - edge, 0, w, h)
        )
    )
}

/**
 * 顶部「下拉通知栏」拦截条（尽力而为）。
 *
 * 说明：Android 不允许普通应用彻底禁用通知栏下拉——系统的下拉手势由
 * SystemUI 处理，只有 DeviceOwner / kiosk（锁任务模式）才能真正屏蔽。
 * 这里在窗口顶部铺一条不可见的窄条并吞掉其上的所有触摸事件，
 * 让「从屏幕最顶部向下滑」的手势落在本应用窗口里而不是穿透到 SystemUI。
 */
@Composable
fun StatusBarSwipeBlocker(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    // 只消费事件，不做任何处理：用户从顶部下滑不会触发系统手势
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.all { !it.pressed }) break
                    }
                }
            }
    )
}
