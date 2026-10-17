package com.dsh.sportscam.ui

import android.content.Context
import android.view.Display
import com.dsh.sportscam.cover.CoverAppLauncher
import com.dsh.sportscam.cover.CoverDisplay
import com.dsh.sportscam.cover.CoverSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 从"非 Compose 挂起环境"（比如桌面格子点击）把应用送到副屏。
 *
 * 复用 [CoverAppLauncher.launchPackageToCover] 的完整策略
 * （原生 setLaunchDisplayId → 校验 → 必要时升级为 Shizuku 两步法），
 * 只是给它一个自己的协程作用域。
 */
object CoverAppLaunchBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 返回是否已发起（false = 没找到副屏） */
    fun launchToCover(context: Context, packageName: String): Boolean {
        val appContext = context.applicationContext
        val coverId = CoverDisplay.coverDisplayId(appContext)
        if (coverId == Display.DEFAULT_DISPLAY) return false
        scope.launch {
            runCatching {
                CoverAppLauncher.launchPackageToCover(
                    context = appContext,
                    packageName = packageName,
                    coverDisplayId = coverId,
                    preferShizuku = CoverSettings.isPreferShizuku(appContext, packageName)
                )
            }
        }
        return true
    }
}
