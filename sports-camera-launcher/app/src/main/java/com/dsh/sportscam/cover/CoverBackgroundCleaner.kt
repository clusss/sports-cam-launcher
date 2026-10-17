package com.dsh.sportscam.cover

import android.app.ActivityManager
import android.content.Context
import android.content.Intent

/**
 * 清理后台进程（副屏启动器的"双击相簿 #2"入口）。
 *
 * 背景：常驻悬浮窗开启后，上滑停留会被悬浮窗截停，用户没法用系统的后台面板清理内存。
 * 所以给一个专用入口，效果等同于系统面板上的"一键清理"。
 *
 * **绝不清理自己**：`context.packageName` 从列表里排除掉。
 * 被清理的范围是"所有带启动图标的应用"—— 这样不会误伤系统常驻服务，
 * 又能覆盖用户真正装的应用。
 */
object CoverBackgroundCleaner {

    /**
     * 返回：**-1** 表示走了 Shizuku 的强路径（`am kill-all`），否则是下发的包数量。
     *
     * 为什么要两条路：
     *  · Android 8 之后 `killBackgroundProcesses` **只回收"已缓存"进程**（adj 比
     *    cached 更低的那种）。实测：微信有 `:push` 服务、设置有 `SettingsExProvider`
     *    提供者、B站有下载服务 —— 它们的 adj 都高于缓存，系统直接忽略我们的请求，
     *    调用不抛异常但进程照旧（日志"已下发=107"却毫无效果）。
     *  · Shizuku 以 shell 身份可以执行 `am kill-all`，那才是真正能清掉后台进程的手段。
     */
    suspend fun clear(context: Context): Int {
        // ① 强路径：Shizuku 在跑就用 shell 的 am kill-all
        if (ShizukuShell.state.value.usable) {
            val output = ShizukuShell.exec("am kill-all")
            if (output != null) {
                CoverDebugLog.log("Clean", "Shizuku am kill-all 已执行（强路径）")
                return -1
            }
            CoverDebugLog.log("Clean", "Shizuku 可用但 am kill-all 失败，退回弱路径")
        }

        // ② 弱路径：killBackgroundProcesses（只能回收已缓存进程）
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = runCatching {
            context.packageManager.queryIntentActivities(launcherIntent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())

        // ★ 关键：把自己排除掉，否则双击一下就把启动器杀了
        val targets = packages - context.packageName
        if (targets.isEmpty()) {
            CoverDebugLog.log("Clean", "清理后台：没有可清理的目标")
            return 0
        }

        var delivered = 0
        targets.forEach { pkg ->
            runCatching {
                am.killBackgroundProcesses(pkg)
                delivered++
            }
        }

        CoverDebugLog.log(
            "Clean",
            "清理后台（弱路径，仅回收已缓存进程）：目标=${targets.size} 已下发=$delivered" +
                "（已排除自身 ${context.packageName}）"
        )
        return delivered
    }
}
