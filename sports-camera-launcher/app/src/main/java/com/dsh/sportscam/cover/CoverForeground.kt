package com.dsh.sportscam.cover

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

/**
 * 副屏当前前台是谁 —— 由无障碍服务喂数据。
 *
 * 用途：常驻悬浮窗**只能在副屏停在自己的桌面时挂出**。
 * 否则用户在副屏上用别的应用（看视频、回消息）时，悬浮窗会盖上来抢界面，非常干扰。
 *
 * 数据来源：[CoverTriggerAccessibilityService] 的窗口事件（带 displayId），
 * 它能同时告诉我们"哪块屏"和"哪个包"。没有它就只能靠"启动应用后抑制一段时间"
 * 这种粗糙启发式 —— 时间一到照样会在别的应用上弹出来。
 */
object CoverForeground {

    /** 副屏最近一次报告的前台包名 */
    @Volatile
    var coverPackage: String? = null
        private set

    /** 副屏 id（仅供日志） */
    @Volatile
    var coverDisplayId: Int = -1
        private set

    /** 无障碍服务是否在运行（决定我们能否信任上面的前台信息） */
    @Volatile
    var serviceRunning: Boolean = false
        private set

    /** 系统里所有 primary/secondary HOME 应用的包名（无障碍服务查询后注入） */
    @Volatile
    private var homePackages: Set<String> = emptySet()

    /** 兜底清单：即使还没查到 HOME 列表，也要认得这些桌面 */
    private val KNOWN_HOMES = setOf(
        "com.vivo.fliplauncher",
        "com.bbk.launcher2",
        "com.bbk.SuperPowerSave",
        "com.vivo.simplelauncher",
        "com.vivo.privacylauncher"
    )

    /**
     * "软前台"：这些包在"要不要收起悬浮窗"的判断里**不算别的应用**。
     *
     *  · `com.vivo.upslide` —— 上滑并停留呼出的后台/最近任务面板。
     *    实测反馈：它一到前台就把悬浮窗顶掉，导致"看后台时启动器退出"，很不稳。
     *  · `com.android.systemui` / `com.vivo.systemuiplugin` —— 下拉通知栏、状态栏等。
     *    它们本来就在悬浮窗之上，没必要把我们收起（收起反而让界面闪烁）。
     */
    private val SOFT_PACKAGES = setOf(
        "com.vivo.upslide",
        "com.android.systemui",
        "com.vivo.systemuiplugin"
    )

    /**
     * 不能算作桌面的"伪 HOME"包。
     *
     * ⚠ 实测踩坑（两次，同一个包）：
     *  · `queryIntentActivities(ACTION_MAIN + CATEGORY_HOME)` 会把系统的兜底桌面
     *    **`com.android.settings/.FallbackHome`** 一起返回 —— 于是"设置"这个包被当成桌面；
     *  · 更麻烦的是 **vivo 的应用锁解锁界面也跑在 `com.android.settings` 里**
     *    （实测组件：`com.android.settings/com.vivo.settings.secret.PasswordActivity`，
     *    在副屏 d1 全屏可见）。如果"设置"被当成桌面包，无障碍服务的**自动拉起**
     *    就会在你点开带应用锁的应用时把启动器拉上来，**盖住解锁界面** ——
     *    表现就是"应用锁的应用打不开"，以及"没加锁的系统设置也会被拦"（同一个包）。
     */
    private val NOT_REALLY_HOME = setOf(
        "com.android.settings",
        "com.android.systemui",
        "android"
    )

    /** 过滤掉"伪 HOME"包；无障碍服务的拉起判断也必须用过滤后的集合 */
    fun sanitizeHomePackages(homes: Set<String>): Set<String> =
        homes.filterNot { it in NOT_REALLY_HOME }.toSet()

    fun onServiceConnected(homes: Set<String>) {
        serviceRunning = true
        homePackages = sanitizeHomePackages(homes)
    }

    fun onServiceDisconnected() {
        serviceRunning = false
        coverPackage = null
    }

    /**
     * 按需刷新一次副屏前台（**权威值**）。
     *
     * 为什么最终用「使用记录」（UsageStats）而不是别的：
     *  · 事件流（无障碍窗口事件）会被自家窗口、SystemUI、后台面板互相顶替而抖动；
     *  · `AccessibilityService.getWindows()` 实测**只返回默认显示器的窗口**
     *    （日志：全部=4，disp 全是 0，里面还有主屏的 com.bbk.launcher2），看不到副屏；
     *  · Shizuku 能拿到权威窗口栈，但它没在运行。
     *
     * 使用记录给出的是"最近被切到前台的应用活动"（ACTIVITY_RESUMED）。这台机器主屏
     * 没有面板，所以最近恢复的活动实际就是副屏上用户正在用的那个 —— 足够可靠。
     * 自家包跳过（否则永远是"自己"）。任何抖动都会被每 350ms 一次的刷新自愈。
     */
    fun refresh(context: Context) {
        val pkg = queryUsageForeground(context)
        if (pkg != null && pkg != coverPackage) {
            CoverDebugLog.log("Fg", "副屏前台(使用记录): $coverPackage -> $pkg")
            coverPackage = pkg
            serviceRunning = true
        }
    }

    private fun queryUsageForeground(context: Context): String? = runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@runCatching null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 60_000L, now) ?: return@runCatching null
        val event = UsageEvents.Event()
        var latest: String? = null
        var latestAt = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                event.timeStamp >= latestAt
            ) {
                // ⚠ 这里**不跳过自家包**：被动模式需要知道"最近恢复的是不是我们自己"，
                //   那正是"启动器此刻可见"的唯一可靠信号。
                //   （曾经用 CoverPresence 判断，但悬浮窗是 FLAG_NOT_FOCUSABLE，
                //    它一挂起来底下 Activity 永远不 onPause，那个信号恒为真。）
                latestAt = event.timeStamp
                latest = event.packageName ?: latest
            }
        }
        latest
    }.getOrNull()

    /** 是否具备"使用记录"访问权限（没有的话前台判定会退回事件流，可能不准） */
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return@runCatching false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** 无障碍服务每收到一个副屏窗口事件就调用一次（即时触发的提示，权威值以 [refresh] 为准） */
    fun onCoverWindow(packageName: String, displayId: Int) {
        val previous = coverPackage
        coverPackage = packageName
        coverDisplayId = displayId
        // 只在真正变化时落盘：这是排查"该弹不弹 / 该收不收"的唯一线索
        if (previous != packageName) {
            CoverDebugLog.log("Fg", "副屏前台: $previous -> $packageName (display=$displayId)")
        }
    }

    /** 是否已有可用的前台信息 */
    val hasData: Boolean get() = coverPackage != null

    /**
     * 副屏此刻是否"停在桌面上"（vivo 桌面 / 我们的启动器 / 其它 HOME 应用）。
     *
     * 拿不到数据时返回 true —— 宁可回到旧行为（还能用），也不要因为信息缺失
     * 而让悬浮窗永远不出来；真正的风险场景（别的应用在前台）一定有数据。
     */
    fun isCoverHomeInFront(ourPackage: String): Boolean {
        val pkg = coverPackage ?: return true
        return pkg == ourPackage || pkg in homePackages || pkg in KNOWN_HOMES || pkg in SOFT_PACKAGES
    }
}
