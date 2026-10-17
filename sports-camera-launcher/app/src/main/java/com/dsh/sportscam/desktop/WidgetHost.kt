package com.dsh.sportscam.desktop

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent

/**
 * 第三方小组件宿主。
 *
 * 用 [AppWidgetHost] 在应用内承载别人的小组件（桌面格子里放的就是它）。
 * 绑定流程：
 *   1. [allocateAndBind] 先静默绑定；返回 false 说明没拿到 BIND_APPWIDGET 权限；
 *   2. 此时用 [bindIntent] 拉起系统的授权界面，用户同意后再 [createView]。
 */
object WidgetHost {

    /** 宿主 id，随便取一个固定值即可（同进程内唯一） */
    private const val HOST_ID = 0x5350

    private var host: AppWidgetHost? = null

    fun host(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also {
            host = it
            runCatching { it.startListening() }
        }

    /** 设备上所有可添加的小组件 */
    fun providers(context: Context): List<AppWidgetProviderInfo> =
        runCatching {
            AppWidgetManager.getInstance(context).installedProviders
        }.getOrDefault(emptyList())
            .sortedBy { it.loadLabel(context.packageManager).toString() }

    /**
     * 分配一个 appWidgetId 并尝试静默绑定。
     * 返回 (id, 是否已授权)。未授权时需要走 [bindIntent] 让用户点同意。
     */
    fun allocateAndBind(context: Context, provider: AppWidgetProviderInfo): Pair<Int, Boolean> {
        val id = runCatching { host(context).allocateAppWidgetId() }.getOrDefault(-1)
        if (id < 0) return -1 to false
        val mgr = AppWidgetManager.getInstance(context)
        val allowed = runCatching {
            mgr.bindAppWidgetIdIfAllowed(id, provider.provider)
        }.getOrDefault(false)
        return id to allowed
    }

    fun bindIntent(id: Int, provider: AppWidgetProviderInfo): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
        }

    fun createView(context: Context, appWidgetId: Int): AppWidgetHostView? =
        runCatching {
            host(context).createView(context, appWidgetId, null)
        }.onFailure { CoverDesktopLog.log("WidgetHost", "createView 失败: ${it.message}") }
            .getOrNull()

    fun delete(context: Context, appWidgetId: Int) {
        runCatching { host(context).deleteAppWidgetId(appWidgetId) }
    }

    fun stopListening() {
        runCatching { host?.stopListening() }
    }
}

/** 桌面相关的最小落盘日志（与 cover_debug.log 同一份，便于真机排查） */
internal object CoverDesktopLog {
    fun log(tag: String, msg: String) =
        com.dsh.sportscam.cover.CoverDebugLog.log(tag, msg)
}
