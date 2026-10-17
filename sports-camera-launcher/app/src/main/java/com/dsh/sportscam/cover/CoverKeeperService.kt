package com.dsh.sportscam.cover

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import com.dsh.sportscam.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 副屏常驻（保活 + 自动拉起）。
 *
 * 三条触发路径（对应《超级小副屏》的三条机制，但用现代写法实现）：
 *  1. **副屏点亮**：DisplayListener 发现副屏 state==ON → 投回副屏；
 *  2. **折叠动作**：铰链角度传感器（TYPE_HINGE_ANGLE）判定 展开→折叠 → 投回副屏；
 *  3. **副屏前台不是我们**：每 1500ms 经 Shizuku 轮询 dumpsys，命中桌面/触发包 → 投回副屏
 *     （原版就是这条；没授权 Shizuku 时自动跳过，靠无障碍服务那条路）。
 *
 * 保活手段（对齐原版 SubScreenKeeper）：
 *  - 每 2s 重新 startForeground，防止前台通知被系统摘掉；
 *  - 60s AlarmManager 看门狗周期性唤醒自己；
 *  - START_STICKY。
 *
 * ⚠ Android 10+ 限制后台启动 Activity，但**持有 SYSTEM_ALERT_WINDOW 权限的应用属于豁免情形**
 *   —— 这也是原版声明该权限的原因。没有悬浮窗权限时后台拉起只在部分场景生效。
 */
class CoverKeeperService : Service() {

    companion object {
        private const val TAG = "CoverKeeperService"

        /** 保活通知（标准视图，点主体进应用）；方向开关是另一条 1002 */
        const val CHANNEL_ID = "cover_keeper"
        const val NOTIFICATION_ID = 1001

        /**
         * 保活通知的**显式** group key（第五轮第 1 项）。
         *
         * ★ 为什么必须给保活通知分组、而**方向通知故意不分组**：
         *   NMS 只对 `getGroup()==null` 的通知做自动分组，且需要 **≥2 条**未分组通知
         *   才会造出那条 `ranker_group` 折叠摘要行；那一行只渲染系统拼出来的标准标题/正文，
         *   方向通知的自定义视图（方向 5 键 + 截屏 + 录屏按钮）在折叠态**完全不渲染** ✗
         *   —— 用户看到的就是"通知栏只剩保活通知" ✓（真机 `dumpsys notification` +
         *   副屏截图证据见 [OrientationNotification] 顶部注释）。
         *   给保活通知一个显式 key 后，全应用只剩方向通知一条"未分组通知" ⇒ 没有可合并对象
         *   ⇒ 方向通知必定独立成行、自定义视图正常显示 ✓。
         */
        const val KEEPER_GROUP_KEY = "com.dsh.sportscam.group.keeper"
        private const val POLL_INTERVAL_MS = 1_500L
        private const val NOTIFICATION_GUARD_MS = 2_000L
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        private const val PULL_DEBOUNCE_MS = 3_000L

        /** 悬浮窗快速同步周期（毫秒） */
        private const val OVERLAY_FAST_MS = 350L

        // ---- 悬浮窗"挂出/收起"去抖（第三轮；参数含义见 syncOverlayWindow 的注释） ----

        /** 挂出前要求条件连续成立这么久（杀掉秒级抖动导致的 addView 风暴） */
        private const val OVERLAY_SHOW_DWELL_MS = 500L

        /** "前台是易抖系统类"时，收起前要求该状态连续成立这么久 */
        private const val OVERLAY_HIDE_DWELL_MS = 1_200L

        /** 硬闸：任何两次挂出/收起之间至少间隔这么久（保证 1s 内不会出现两组 hide/show） */
        private const val OVERLAY_MIN_TOGGLE_GAP_MS = 1_200L

        /** 「重建悬浮窗」的最小间隔（重建会销毁整棵 Compose 树，必须去抖） */
        private const val OVERLAY_RECREATE_MIN_GAP_MS = 3_000L

        /** 让保活服务在副屏上挂出/收起"锁屏之上的启动器界面"（悬浮窗模式） */
        const val ACTION_SHOW_OVERLAY = "com.dsh.sportscam.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.dsh.sportscam.action.HIDE_OVERLAY"

        /** 立即同步一次悬浮窗状态（无障碍服务发现副屏回到桌面时用，做到"秒回"） */
        const val ACTION_SYNC_OVERLAY = "com.dsh.sportscam.action.SYNC_OVERLAY"

        /** 重建悬浮窗（改了分辨率/DPI 之后，窗口尺寸需要重新测量） */
        const val ACTION_RECREATE_OVERLAY = "com.dsh.sportscam.action.RECREATE_OVERLAY"

        /**
         * **退出悬浮窗模式**（不只是临时藏起来）。
         *
         * 处理时会：`CoverSettings.setOverlayModeEnabled(false)`（写**用户意愿** ✓ 控制台可重开 ✓）
         * + 立刻 `hide` 悬浮窗 + 刷保活/方向通知 ✓ —— 见 `onStartCommand` 里那条分支 ✓。
         *
         * ★★ 第九轮**最终定稿（用户决定：不提供任何逃生通道 ✗）**：
         *   · **当前没有任何 UI 入口** ✗ —— 通知栏两处（方向通知「退出」药丸 / 保活通知
         *     「退出悬浮窗」action）都已删除 ✓；**视频1 页右滑方案也已撤回** ✗；
         *   · **唯一正常关闭途径** = **控制台 ③ 页「悬浮窗防退出模式」开关** ✓（页面内开关）；
         *   · 本 action 的**处理器保留** ✓（当前无 UI 入口，仅供代码/测试触发 ✓；
         *     将来若要再加 UI 入口，发这个 action 即可 —— 见 `onStartCommand` 那条分支 ✓）。
         *   ⚠ 所以**不要**再在任何注释/文案里写"通知栏/视频页有退出口" ✗。
         */
        const val ACTION_EXIT_OVERLAY = "com.dsh.sportscam.action.EXIT_OVERLAY"

        /**
         * 通知栏录屏按钮（第二轮 D1）：未录屏 → 开始，录屏中 → 结束。
         *
         * 为什么挂在**保活服务**上而不是广播接收器：录屏的启停是"长时间动作"
         * （shell 往返 + 起/停进程 + 收尾等 1~2s），Service 天生适合；
         * 广播里必须 goAsync 才能做，而保活服务本来就常驻。
         */
        const val ACTION_RECORD_TOGGLE = "com.dsh.sportscam.action.RECORD_TOGGLE"

        fun requestOverlay(context: Context) {
            val intent = Intent(context, CoverKeeperService::class.java).setAction(ACTION_SHOW_OVERLAY)
            runCatching { context.startForegroundService(intent) }
        }

        /** 立刻同步悬浮窗（不等到下一个轮询周期） */
        fun syncOverlaySoon(context: Context) {
            val intent = Intent(context, CoverKeeperService::class.java).setAction(ACTION_SYNC_OVERLAY)
            runCatching { context.startForegroundService(intent) }
        }

        /** 重建悬浮窗（改过分辨率/DPI 后调用） */
        fun recreateOverlay(context: Context) {
            val intent = Intent(context, CoverKeeperService::class.java).setAction(ACTION_RECREATE_OVERLAY)
            runCatching { context.startForegroundService(intent) }
        }

        // ★ 第九轮最终定稿：这里原来那个 `fun exitOverlay(context)` 小助手**已删除** ✗
        //   （当初 desktop-ui 的右滑是"直接发 action"，没有调用方 ⇒ 按 Lead 要求"少一个 API 面"删掉）。
        //   现在**没有任何 UI 入口** ✗（右滑方案也已撤回 ✓）；`ACTION_EXIT_OVERLAY` 的处理器仍保留 ✓，
        //   仅供代码/测试触发；真正给用户用的关闭途径是 **控制台 ③ 页「悬浮窗防退出模式」开关** ✓。

        fun start(context: Context) {
            val intent = Intent(context, CoverKeeperService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "启动保活服务失败", it) }
        }

        /** 请求在副屏上挂出启动器悬浮窗（锁屏时用，能显示在 vivo 锁屏之上） */
        fun requestOverlayLegacy(context: Context) {
            val intent = Intent(context, CoverKeeperService::class.java).setAction(ACTION_SHOW_OVERLAY)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "请求悬浮窗失败", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CoverKeeperService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var displayListener: DisplayManager.DisplayListener? = null
    private var foldDetector: FoldDetector? = null
    private var guardJob: Job? = null
    private var pollJob: Job? = null
    private var overlayFastJob: Job? = null
    private var lastPullAt = 0L
    private var lastCoverDisplayState = Display.STATE_UNKNOWN
    private var homePackages: Set<String> = emptySet()

    // ---------------- 悬浮窗"挂出/收起"去抖状态（第三轮，见 syncOverlayWindow） ----------------

    /** `want=true` 连续成立起点 */
    private var wantTrueSince = 0L

    /** `want=false` 连续成立起点 */
    private var notWantSince = 0L

    /** 最近一次真的挂出/收起的时间（硬闸：1.2s 内不得再次切换） */
    private var lastOverlayToggleAt = 0L

    /** 最近一次"重建悬浮窗"的时间与在途标志（重建去抖，第三轮） */
    private var lastRecreateAt = 0L
    private var recreating = false

    /** 去抖"延后"日志的节流时间（取证用，不能刷屏） */
    private var lastDwellSkipLogAt = 0L

    /**
     * 锁屏之上的启动器界面（悬浮窗模式）。
     * vivo 的副屏锁屏是自绘系统窗口，普通 Activity 压不过它，
     * 只有 TYPE_APPLICATION_OVERLAY 能显示在它之上 —— 详见 [CoverOverlayWindow]。
     */
    private var overlayWindow: CoverOverlayWindow? = null

    /** 悬浮窗的快速同步循环（约 3 次/秒），只为把"回到桌面后补悬浮窗"的延迟压到感知不到 */
    private fun startOverlayFastLoop() {
        var lastStatusBarAssert = 0L
        overlayFastJob = scope.launch {
            while (isActive) {
                delay(OVERLAY_FAST_MS)
                if (!CoverSettings.isOverlayModeEnabled(this@CoverKeeperService)) continue
                runCatching { syncOverlayWindow() }
                // 自动息屏看门狗（见下）
                runCatching { screenTimeoutWatchdog() }
                // 状态栏重申（约 1s 一次）：vivo 的"切后台/回桌面"手势会把顶部状态栏顶出来
                // 并常驻（用户实测），只隐藏一次挡不住 → 周期性再按回去。
                val now = System.currentTimeMillis()
                if (overlayWindow?.isShowing == true && now - lastStatusBarAssert > 1_000L) {
                    lastStatusBarAssert = now
                    runCatching { overlayWindow?.reassertStatusBar() }
                }
            }
        }
    }

    /**
     * 「自动息屏」看门狗。
     *
     * 为什么需要它：悬浮窗模式下副屏一直盖着我们的全屏悬浮窗，**系统不把这当空闲** ✗ →
     * 屏幕永不熄灭、耗电明显（用户实测反馈）。写系统 `screen_off_timeout` 在部分 ROM 上
     * 也可能被忽略，所以再补一条兜底：只要"启动器在副屏前台"且距上次交互超过设定时长，
     * 就主动发一次熄屏。
     *
     * ★ 只在**启动器自己在前台**时动手：用户在用别的应用时由系统自己管，不打扰。
     * ★ 「永不息屏」档位直接跳过。
     */
    private suspend fun screenTimeoutWatchdog() {
        val timeout = CoverSettings.screenTimeoutMs(this)
        if (timeout <= 0L) return
        if (timeout == CoverScreenTimeout.NEVER_MS) return
        if (!CoverScreenTimeout.isScreenOn(this)) return
        if (!CoverForeground.isCoverHomeInFront(packageName)) return
        if (CoverScreenTimeout.msSinceInteraction() < timeout) return

        CoverScreenTimeout.sleepNow(this)
        // 发完就重置计时，避免连续下发
        CoverScreenTimeout.markInteraction()
    }

    /**
     * 常驻悬浮窗模式的挂出/收起决策（由快循环与 2 秒守卫共同驱动）。
     *
     * 挂出条件（全部满足）：
     *   1. 设置里开启了悬浮窗模式；
     *   2. 副屏存在且**亮着**；
     *   3. **当前没有锁屏** —— 悬浮窗层级在 vivo 锁屏之上，锁屏时若盖着，
     *      用户就没法在副屏上输入密码解锁了。锁屏时一律收起，
     *      "快捷启动 → 指纹验证 → 秒进应用"那条链路保持原样；
     *   4. 没有处于"刚启动过别的应用"的抑制期（那时悬浮窗会让位，免得挡住新应用）。
     *
     * 这样它就能**常驻覆盖 vivo 的副屏桌面**，同时不干扰锁屏与其它应用。
     */
    private fun syncOverlayWindow() {
        // ★ 运动相机模式总开关（任务书第 1 项）。
        //   「关」= 本应用退回普通启动器：所有防查能力整体下线。
        //   必须在**第一时间**收起已挂的悬浮窗并直接 return —— 快循环（350ms）与 2s 守卫
        //   都会调到这里；只要漏了这道判断，历史设置残留（旧版本写过 overlay_mode_enabled=true）
        //   就会让悬浮窗继续盖在副屏上，而用户在控制台已经把它置灰、关不掉了。
        if (!CoverSettings.isCameraModeEnabled(this)) {
            CoverOverlayState.clearSticky()
            overlayWindow?.takeIf { it.isShowing }?.hide("运动相机模式已关闭")
            return
        }

        // ★ 先按需刷新一次"副屏前台是谁" —— 用使用记录（权威），每 350ms 一次，
        //   事件流的抖动会被下次刷新纠正回来。
        runCatching { CoverForeground.refresh(this) }

        val coverId = CoverDisplay.coverDisplayId(this)
        val w = overlayWindow ?: CoverOverlayWindow(applicationContext).also { overlayWindow = it }

        if (coverId == Display.DEFAULT_DISPLAY) {
            if (w.isShowing) w.hide("无副屏")
            return
        }

        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val displayOn = runCatching { dm?.getDisplay(coverId)?.state == Display.STATE_ON }.getOrDefault(false)

        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val locked = km?.isKeyguardLocked != false        // 拿不到状态就按"锁着"处理，保守

        val enabled = CoverSettings.isOverlayModeEnabled(this)

        // ★ 只在副屏停在自己的桌面上时才挂出。
        //   否则用户在副屏上用别的应用（看视频、回消息）时，悬浮窗会盖上来抢界面 ——
        //   这是实测反馈的干扰问题。前台信息取自使用记录（权威），每 350ms 刷新一次。
        val homeInFront = !CoverForeground.serviceRunning ||
            CoverForeground.isCoverHomeInFront(packageName)

        // 回到桌面（权威判定）就解除"让位"：这样"启动应用 → 返回桌面 → 悬浮窗回来"是即时的。
        // 必须在上面的 suppressed 取值**之前**做，否则要等下一个 350ms 周期才生效。
        if (homeInFront && CoverOverlayState.suppressed) {
            CoverOverlayState.clearSuppress()
        }
        val suppressed = CoverOverlayState.suppressed

        // 「不主动弹出」：不做前台判断，改用**粘住**语义 ——
        // 启动器出现过一次就一直保持悬浮窗，用户做返回/上滑手势时（我们会 onPause）
        // 也不会跟着收掉。只有"从界面启动别的应用 / 通知栏退出 / 关掉开关"才解除。
        val passive = CoverSettings.isOverlayPassive(this)
        if (!enabled) CoverOverlayState.clearSticky()

        // ★ "启动器此刻可见"的可靠判据 = 使用记录里最近恢复的活动就是我们自己。
        //   不能用 CoverPresence：悬浮窗是 FLAG_NOT_FOCUSABLE，挂起来后底下 Activity
        //   永远不 onPause，那个信号恒为真（实测日志：设置在前台时它仍是 true）。
        val launcherInFront = CoverForeground.coverPackage == packageName

        if (passive) {
            // 第三方应用真的到了前台时要让位并解除粘住 ——
            // 否则"从通知栏点通知""按快捷键开相机"这类不是从我们界面发起的启动会被盖住。
            // 注意 isCoverHomeInFront 把桌面包与软前台（后台面板/SystemUI）都算作"桌面"，
            // 所以返回桌面、上滑看后台都不会误解除。
            val realAppInFront = CoverForeground.serviceRunning &&
                CoverForeground.coverPackage != null &&
                !CoverForeground.isCoverHomeInFront(packageName)
            if (realAppInFront) CoverOverlayState.clearSticky()

            // ★ 重新粘住的前提是"前台不是第三方应用"。
            //   为什么必须加这个前提：我们的悬浮窗是 FLAG_NOT_FOCUSABLE，
            //   它一挂起来，底下的 Activity 就永远不会 onPause ——
            //   于是 launcherInFront 恒为真，每个周期都重新粘住，
            //   刚让位给第三方应用的悬浮窗会在 0.3 秒后又冒出来（实测日志）。
            if (launcherInFront && !realAppInFront) CoverOverlayState.markSticky()
        }

        val frontOk = if (passive) {
            CoverOverlayState.sticky || launcherInFront
        } else {
            homeInFront
        }

        // ★★☠☠ 第九轮收口（**请先读这段，别只看下面那段历史注释**）：
        //   「非运动相机界面不使用悬浮窗」这个设置在第九轮已按用户要求**整个删除** ✗
        //   —— Lead 用真机日志确证：悬浮窗就是应用列表/控制台这些页面的**宿主** ✗，
        //   收起必然先闪一下 vivo 桌面、再被挂出（日志实证 hide→onResume→挂出→show 循环 ✗），
        //   因此需求在当前架构下做不到 ✓ → 删设置项，而不是继续加宽限期 ✓。
        //   现状：`CoverSettings.overlayAllowedOnCurrentScreen()` **恒返回 true** ✓
        //   ⇒ 下面的 `gateSaysNo` 永远是 false、`screenOk` 永远是 true
        //   ⇒ **本段不会再触发任何收起** ✓（死循环从结构上消失 ✓）。
        //   保留这段判定+日志，是为了：① 万一将来真的重做该功能（需要"界面由 Activity 接手渲染"
        //   这种架构改动 ✓）时有现成的接入点；② 日志口径仍能区分"因设置收起 / 跳过补位拉起" ✓。
        //   如果你看到有人把 `overlayAllowedOnCurrentScreen` 改回依赖屏幕判断 ✗，
        //   请先确认 ① 悬浮窗不再是那些页面的宿主、② `pullToCover` 的"我们自己在前台就不拉起" 仍在 ✓。
        //
        // ---- 以下为第七轮的历史注释（保留说明当初为什么删短路） ----
        // ★★ 第七轮：用户澄清「非运动相机界面」的定义，判据随之收窄 ✓ ——
        //   算"运动相机界面"（悬浮窗照旧允许）的**只有**：
        //     Screen.Carousel（主界面）/ Screen.Gallery（相簿#1）/ Screen.Videos（视频#1）✓
        //   算"非运动相机界面"（本设置开启时收起悬浮窗）：应用列表 / 控制台 / 桌面 / 显示设置 /
        //     截屏录屏设置 / 私密应用 / 最近删除 / 查看器 …其余全部 ✓
        //   settings-console 已把 `CoverScreenGate` 的白名单收窄成这三个（仍**穷尽、不加 else** ✓）。
        //
        //   ⇒ 这里**删掉**上一轮那条"我们自己的界面在前台 ⇒ screenOk 恒 true"的短路 ✗：
        //     它当初是为了防锁死（悬浮窗模式下整个 UI 都画在悬浮窗里），但留着它，
        //     本设置在这些子界面上就**永远不生效** ✗（与用户明确要求相反 ✓）。
        //   判据仍复用 settings-console 的封装 ✓：读失败按"允许"（`CoverScreenGate` 默认 true ✓，保守 ✓）。
        //
        //   ☠ 删了为什么**不会**再锁死（三条必须一起成立，缺一不可）：
        //     ① 闸值只对那三个"运动相机界面"为 true ⇒ 其余界面 `screenOk=false` ⇒ **按用户要求收起** ✓；
        //     ② **收起后绝不自动弹回** —— `pullToCover()` 的第 4 道闸
        //        "我们自己的界面已在副屏前台 ⇒ 跳过补位拉起" 继续有效 ✓✓（死循环的另一半被掐住 ✓）；
        //     ③ 悬浮窗只是**盖在上面**：底下 `CoverHomeActivity` 的窗口仍在渲染**同一棵**
        //        `SportsCamApp`，且导航栈是**共享单例** `LauncherNavState`（MainActivity.kt:993
        //        注释"两棵树共享的同一个状态" ✓，`var stack by LauncherNavState.stack` ✓）
        //        ⇒ 两棵树显示**同一页** ⇒ 收起悬浮窗后用户**仍然看得到、点得动**当前界面 ✓
        //        （应用列表/控制台照常可用 ✓）。真机窗口证据（2026-10-11 `dumpsys window windows`，
        //        副屏 display=1）：`type=2038` 悬浮窗与 `com.dsh.sportscam.CoverHomeActivity type=1`
        //        Activity 窗口**同时存在** ✓；悬浮窗是 `FLAG_NOT_FOCUSABLE` ⇒ 底下 Activity 不会被 pause ✓。
        val gateSaysNo = !runCatching { CoverSettings.overlayAllowedOnCurrentScreen(this) }
            .getOrDefault(true)   // 读失败按"允许"，保守 ✓
        val screenOk = !gateSaysNo
        if (!screenOk && launcherInFront) logOverlayHiddenByScreenGate()

        val want = enabled && displayOn && !locked && !suppressed && frontOk && screenOk

        // ==============================================================
        // 悬浮窗去抖（第三轮：用户实测"图标/名称反复消失又出现"）
        // ==============================================================
        // 真机日志（2026-10-10 00:08~00:09）显示，1 秒内能出现成对多次 hide/show：
        //   00:09:05.807 hide（副屏前台是其它应用 com.android.settings ← vivo 应用锁）
        //   00:09:08.484 show
        //   00:09:10.250 hide（重建） / 00:09:10.277 show
        //   00:09:10.868 hide（副屏前台是其它应用 settings） / 00:09:11.484 show 校验 attached=false
        // 每次 hide+show 都会**销毁并重建整棵 Compose 树**（桌面图标/名称随之闪一下）。
        // 根因是"前台是谁"这个信号在 [我们的界面 / com.android.settings(应用锁) / 桌面] 之间抖动，
        // 而挂出/收起是**瞬时**执行的 —— 于是状态一抖就跟着抖。
        //
        // 去抖策略（只针对"前台信号"这个抖源，其它显式原因仍旧瞬时生效）：
        //   · 显式原因（关开关 / 锁屏 / 无副屏 / 让位抑制）→ **立即**收起，不延迟；
        //   · 前台是**第三方应用**（用户真的打开了一个 App）→ **立即**收起（不能挡着人家）；
        //   · 前台是"易抖的系统类"（应用锁 com.android.settings / SystemUI / 后台面板 / 桌面）
        //     → 连续 [OVERLAY_HIDE_DWELL_MS] 都是这个状态才收起；
        //   · 挂出 → 条件连续 [OVERLAY_SHOW_DWELL_MS] 成立才挂；
        //   · 兜底硬闸：任何两次挂出/收起之间至少 [OVERLAY_MIN_TOGGLE_GAP_MS]，
        //     保证"任何情况下 1 秒内不会出现两组 hide/show"。
        val nowMs = System.currentTimeMillis()
        if (want) {
            notWantSince = 0L
            if (wantTrueSince == 0L) wantTrueSince = nowMs
        } else {
            wantTrueSince = 0L
            if (notWantSince == 0L) notWantSince = nowMs
        }
        // hardNotWant = "显式原因"，必须**立即**收起（不等前台信号去抖）。
        // ★ 第六轮第 1b 项：`!screenOk`（非运动相机界面）也算显式原因 ——
        //   那是用户在界面间主动导航，不是信号抖动，不该被 1.2s 的去抖拖住 ✓。
        val hardNotWant = !enabled || !displayOn || locked || suppressed || !screenOk
        val flappyFront = isFlappyForeground(CoverForeground.coverPackage)
        val gapOk = nowMs - lastOverlayToggleAt >= OVERLAY_MIN_TOGGLE_GAP_MS

        when {
            want && !w.isShowing -> {
                val dwellOk = nowMs - wantTrueSince >= OVERLAY_SHOW_DWELL_MS
                if (!dwellOk) {
                    // 抖动中，先不挂（下一轮 350ms 后再看）
                    logDwellSkip("挂出延后", "条件刚成立 ${nowMs - wantTrueSince}ms")
                } else if (!gapOk) {
                    CoverDebugLog.log(
                        "Keep",
                        "overlay: 挂出被硬闸拦住（距上次切换 ${nowMs - lastOverlayToggleAt}ms " +
                            "＜ ${OVERLAY_MIN_TOGGLE_GAP_MS}ms），保持当前状态"
                    )
                } else {
                    lastOverlayToggleAt = nowMs
                    CoverDebugLog.log(
                        "Keep",
                        "overlay: 挂出（${if (passive) "不主动弹出·粘住" else "解锁+亮屏+桌面在前台"}）" +
                            " [前台=${CoverForeground.coverPackage} sticky=${CoverOverlayState.sticky}" +
                            " 启动器前台=$launcherInFront 桌面在前台=$homeInFront]" +
                            " 已稳定 ${nowMs - wantTrueSince}ms"
                    )
                    w.show(coverId)
                }
            }
            !want && w.isShowing -> {
                val why = when {
                    !enabled -> "开关关闭"
                    locked -> "已锁屏"
                    suppressed -> "刚启动过应用，让位中"
                    !screenOk -> "设置要求只在运动相机界面（主界面/相簿/视频）显示（当前不在这三个界面）"
                    passive -> "不主动弹出·尚未粘住且启动器不在前台"
                    !homeInFront -> "副屏前台是其它应用(${CoverForeground.coverPackage})"
                    !displayOn -> "副屏未亮"
                    else -> "条件不满足"
                }
                // 只有"前台信号抖动"这一类需要等稳定；其它原因立刻收起
                val needDwell = !hardNotWant && flappyFront
                val dwellOk = !needDwell || nowMs - notWantSince >= OVERLAY_HIDE_DWELL_MS
                val waitSuffix = if (needDwell) "，还需等 ${OVERLAY_HIDE_DWELL_MS - (nowMs - notWantSince)}ms" else ""
                if (!dwellOk) {
                    // 抖动中，先不收（避免 1 秒内成对 hide/show）
                    logDwellSkip("收起延后（$why）", "已持续 ${nowMs - notWantSince}ms$waitSuffix")
                } else if (!gapOk) {
                    CoverDebugLog.log(
                        "Keep",
                        "overlay: 收起被硬闸拦住（$why，距上次切换 " +
                            "${nowMs - lastOverlayToggleAt}ms ＜ ${OVERLAY_MIN_TOGGLE_GAP_MS}ms）"
                    )
                } else {
                    lastOverlayToggleAt = nowMs
                    CoverDebugLog.log(
                        "Keep",
                        "overlay: 收起（$why）" +
                            " [前台=${CoverForeground.coverPackage} sticky=${CoverOverlayState.sticky}" +
                            " 启动器前台=$launcherInFront 桌面在前台=$homeInFront]" +
                            " 已稳定 ${if (needDwell) nowMs - notWantSince else 0}ms"
                    )
                    w.hide(why)
                }
            }
        }
    }

    /**
     * 落盘"因为 1b（非运动相机界面不使用悬浮窗）而收起、且此刻我们自己的界面在前台"这一分支
     * （节流 5s）。
     *
     * 为什么值得单独一行：这正是**用户明确要的新行为** ✓（在应用列表/控制台/桌面/…这些界面
     * 收起悬浮窗 ✓），同时也是上一轮"锁死"的入口场景 ✗ —— 所以每次出现都要能一眼看到：
     *   · 它证明收起是**按设置**发生的（不是信号抖动 ✓）；
     *   · 紧接着**不应该**出现任何 `pull(...)` 拉起日志 ✓（第 4 道闸会拦住 ✓）；
     *   · 用户仍然能看到界面（底下 `CoverHomeActivity` 渲染同一页 ✓）。
     */
    private fun logOverlayHiddenByScreenGate() {
        val now = System.currentTimeMillis()
        if (now - lastGateForceAllowLogAt < 5_000L) return
        lastGateForceAllowLogAt = now
        CoverDebugLog.log(
            "Keep",
            "overlay: 1b 判定 screenOk=false（我们自己在前台，但当前 Screen 不属于" +
                " Carousel/Gallery/Videos）→ 按设置收起悬浮窗 ✓" +
                "（coverPackage=${CoverForeground.coverPackage}" +
                " gate=${CoverScreenGate.currentScreenIsCarousel}；" +
                "底下 CoverHomeActivity 仍渲染同一页 ⇒ 界面照常可操作 ✓；且不会自动拉起 ✓）"
        )
    }

    /** 上一次 [logOverlayHiddenByScreenGate] 的落盘时间（节流 5s） */
    private var lastGateForceAllowLogAt = 0L

    /**
     * 记录一次"因去抖而延后"的挂出/收起（节流 5s，避免刷屏）。
     *
     * 为什么要落盘：第三轮的问题就是"1 秒内成对多次 hide/show"，用户要能从日志里
     * **直接看到去抖生效**（应当出现"挂出延后/收起延后"而不是紧接着的 show/hide）。
     */
    private fun logDwellSkip(what: String, detail: String) {
        val now = System.currentTimeMillis()
        if (now - lastDwellSkipLogAt < 5_000L) return
        lastDwellSkipLogAt = now
        CoverDebugLog.log(
            "Keep",
            "overlay: $what（去抖，$detail；前台=${CoverForeground.coverPackage}" +
                " 距上次切换=${now - lastOverlayToggleAt}ms）"
        )
    }

    /**
     * 这个前台包名属于"会秒级抖动"的系统类吗？
     *
     * 为什么要区分：只有这几类才会在日志里反复顶掉彼此（应用锁界面 `com.android.settings`
     * 一闪而过、SystemUI/后台面板切换），对它们收起悬浮窗要走晕动；
     * 而**真正的第三方应用**到了前台必须立刻让位（否则用户看不见刚打开的应用）。
     */
    private fun isFlappyForeground(pkg: String?): Boolean = when (pkg) {
        null -> true
        "com.android.settings",
        "com.android.systemui",
        "com.vivo.systemuiplugin",
        "com.vivo.upslide",
        "android" -> true
        else -> false
    }

    /** 在副屏上挂出启动器悬浮窗（锁定屏状态下有意义） */
    private fun showOverlayIfLocked(reason: String) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked != true) {
            CoverDebugLog.log("Keep", "showOverlay($reason): 未锁屏，不需要悬浮窗")
            return
        }
        val coverId = CoverDisplay.coverDisplayId(this)
        if (coverId == Display.DEFAULT_DISPLAY) return
        val w = overlayWindow ?: CoverOverlayWindow(applicationContext).also { overlayWindow = it }
        w.show(coverId)
    }

    override fun onCreate() {
        super.onCreate()
        ShizukuShell.init(applicationContext)
        homePackages = CoverAppLauncher.homePackages(this)
        createChannel()
        startForegroundCompat()
        registerDisplayListener()
        startFoldDetector()
        startNotificationGuard()
        startForegroundPoller()
        registerAlarmWatchdog()

        // ★ 悬浮窗模式的"快循环"：状态栏/桌面切回来时要**立刻**把悬浮窗补上，
        //   2 秒的通知守卫周期太慢（用户按返回后会有明显空档，看到的是 vivo 桌面）。
        //   循环常驻，但开关关闭时每轮立刻跳过，开销可忽略。
        startOverlayFastLoop()

        // 恢复上次的方向锁 + 补发方向通知
        val saved = CoverSettings.orientationLock(this)
        if (saved != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            OrientationActionReceiver.applyOrientation(this, saved)
        }
        if (CoverSettings.isOrientationNotifEnabled(this)) {
            OrientationNotification.show(this, "服务 onCreate")
        }
        Log.d(TAG, "onCreate")

        // ★ 录屏收尾（第二轮 D1）：screenrecord 挂在 Shizuku 服务进程下，
        //   本应用进程被杀不会把它带走 —— 会留下"一直在写、没有 moov、用户停不掉"的文件。
        //   所以启动时检查落盘状态，有就主动 SIGINT 收尾（见 CoverScreenRecorder.recoverStaleRecording）。
        scope.launch { runCatching { CoverScreenRecorder.recoverStaleRecording(applicationContext) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                CoverDebugLog.log("Keep", "收到 SHOW_OVERLAY")
                showOverlayIfLocked("请求")
            }
            ACTION_HIDE_OVERLAY -> {
                CoverDebugLog.log("Keep", "收到 HIDE_OVERLAY")
                overlayWindow?.hide("显式请求")
            }
            ACTION_SYNC_OVERLAY -> {
                CoverDebugLog.log("Keep", "收到 SYNC_OVERLAY（立即同步）")
                runCatching { syncOverlayWindow() }
            }
            ACTION_RECREATE_OVERLAY -> {
                CoverDebugLog.log("Keep", "收到 RECREATE_OVERLAY（改过分辨率/DPI）")
                // ★ 第三轮去抖：见 recreateOverlayDebounced 的注释
                //   （重建会销毁整棵 Compose 树，1 秒内多次重建 = 用户看到"图标/名称反复消失又出现"）
                recreateOverlayDebounced("RECREATE_OVERLAY")
            }
            ACTION_EXIT_OVERLAY -> {
                // ★★☠ 处理器（第九轮最终定稿：**当前没有任何 UI 入口** ✗，处理器保留备用 ✓）
                //   行为：**真的把「悬浮窗模式」关掉**（写"用户意愿" ✓，控制台 ③ 页随时能重开 ✓）
                //   并立刻收起 + 刷通知 ✓。
                //   历史：通知栏两处入口（方向通知「退出」药丸 / 保活通知 action）已按用户要求删除 ✗；
                //   视频1 页右滑方案也已撤回 ✗ ⇒ 用户正常关闭悬浮窗模式走 **控制台 ③ 页开关** ✓。
                CoverDebugLog.log(
                    "Keep",
                    "收到 EXIT_OVERLAY（当前无 UI 入口，仅代码/测试可触发）→ 关闭悬浮窗模式" +
                        "（写用户意愿 ✓ 可在控制台重开）+ 立刻收起 + 刷通知"
                )
                runCatching { CoverSettings.setOverlayModeEnabled(this, false) }
                CoverOverlayState.clearSticky()
                runCatching { overlayWindow?.hide("退出悬浮窗模式（ACTION_EXIT_OVERLAY 触发）") }
                runCatching { syncOverlayWindow() }
                runCatching { startForegroundCompat() }
                if (CoverSettings.isOrientationNotifEnabled(this)) {
                    OrientationNotification.show(this@CoverKeeperService, "退出悬浮窗模式后刷新")
                }
            }
            ACTION_RECORD_TOGGLE -> {
                CoverDebugLog.log("Rec", "收到 RECORD_TOGGLE（通知栏录屏按钮）")
                scope.launch { runCatching { toggleRecording() } }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 「重建悬浮窗」去抖执行（第三轮）。
     *
     * 为什么必须去抖（真机日志）：
     *   重建 = `hide()` + 下一次 `syncOverlayWindow()` 再 `show()`，也就是**销毁并重建整棵
     *   Compose 树**（桌面图标/名称会闪一下，小组件会重新 createView —— 日志里
     *   `[Desktop] WidgetCell createView` 就跟着每次 rebuild 出现）。而"重建"有三个入口：
     *     ① 桌面右上角刷新按钮、② 应用列表刷新按钮、③ **启动失败重试路径**
     *        （`CoverAppLauncher` 的 `attempt(true)`）；
     *   它们可能在同一两秒内都触发，日志里因此出现"1 秒内成对多次 hide/show"。
     * 两个保护：
     *   · [recreating] 在途标志：上一次重建还没走完，直接忽略新请求（不会叠加）；
     *   · [OVERLAY_RECREATE_MIN_GAP_MS] 最小间隔：3 秒内不重复重建。
     * **不改变语义**：调用方拿不到返回值，忽略时只落盘一行日志。
     */
    private fun recreateOverlayDebounced(reason: String) {
        val now = System.currentTimeMillis()
        if (recreating) {
            CoverDebugLog.log("Keep", "重建悬浮窗被忽略（上一次还在进行中；原因=$reason）")
            return
        }
        if (now - lastRecreateAt < OVERLAY_RECREATE_MIN_GAP_MS) {
            CoverDebugLog.log(
                "Keep",
                "重建悬浮窗被去抖忽略（距上次 ${now - lastRecreateAt}ms ＜ " +
                    "${OVERLAY_RECREATE_MIN_GAP_MS}ms；原因=$reason）"
            )
            return
        }
        recreating = true
        lastRecreateAt = now
        scope.launch {
            try {
                runCatching {
                    overlayWindow?.hide("重建")
                    // 记一次"切换"时间，避免紧接着又被挂出/收起逻辑抖一次
                    lastOverlayToggleAt = System.currentTimeMillis()
                    syncOverlayWindow()
                }
            } finally {
                recreating = false
            }
        }
    }

    /**
     * 通知栏 ▶/■ 的实际动作：不在录屏就开始，在录屏就结束。
     *
     * ⚠ 第四轮第 6 项：**开始录屏时不能弹提示**（会被 screenrecord 录进视频开头）；
     *   失败/结束仍然提示。详见下面各分支的注释。
     */
    private suspend fun toggleRecording() {
        // ★ 第四轮：合并音视频期间按钮是"⋯"，点击要**如实**回应用户（不能假装已完成）
        if (CoverScreenRecorder.isMuxing) {
            CoverDebugLog.log("Rec", "收到 RECORD_TOGGLE，但正在合并音视频 → 提示稍候（不假装完成）")
            CoverToast.show(this, "正在合并声音，请稍候…")
            return
        }
        if (CoverScreenRecorder.isRecording(this)) {
            when (val r = CoverScreenRecorder.stop(this)) {
                is CoverScreenRecorder.StopResult.Ok ->
                    CoverToast.show(
                        this,
                        "录屏已保存（${r.sizeBytes / 1024}KB）" +
                            if (r.note.isNotEmpty()) "·${r.note}" else ""
                    )
                is CoverScreenRecorder.StopResult.Failure ->
                    CoverToast.show(this, "结束录屏失败：${r.reason}")
            }
        } else {
            when (val r = CoverScreenRecorder.start(this)) {
                // ★ 第四轮第 6 项：开始成功时**不弹任何提示**。
                //   原因：CoverToast 是画在副屏上的 2038 覆盖窗（见 CoverToast 注释），
                //   而 screenrecord 录的就是整个副屏 → 提示会被**录进视频开头**（用户实测 ✗）。
                //   反馈交给通知栏那颗按钮：▶ → ■ 一眼可见 ✓。失败仍然要提示（否则用户不知道）。
                is CoverScreenRecorder.StartResult.Ok ->
                    CoverDebugLog.log("Rec", "已开始录屏（不弹提示，避免被录进开头）：${r.path}")
                is CoverScreenRecorder.StartResult.Failure ->
                    CoverToast.show(this, "开始录屏失败：${r.reason}")
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        EdgeBlocker.hide("服务销毁")
        overlayWindow?.hide("服务销毁")
        overlayWindow = null
        displayListener?.let { listener ->
            runCatching {
                (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.unregisterDisplayListener(listener)
            }
        }
        foldDetector?.stop()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- 通知 / 前台 ----------------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.cover_keeper_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    /** 标准内容视图 → 点通知主体就能进应用（自定义视图会让 contentIntent 失效） */
    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.dsh.sportscam.MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.cover_keeper_title))
            .setContentText(getString(R.string.cover_keeper_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            // ★★ 第五轮第 1 项：保活通知必须给**自己的**显式 group key，否则它会和
            //   方向通知一起被 NMS 自动合并成 "ranker_group" 的折叠摘要行
            //   —— 那一行只渲染标准标题/正文，方向/截屏/录屏按钮全部看不见 ✗
            //   （真机证据见 OrientationNotification.GROUP_KEY 的注释）。
            //   两条 key 不同 ⇒ 各自成行，互不干扰 ✓。
            .setGroup(KEEPER_GROUP_KEY)
            // ★★☠ 第九轮**最终定稿**：保活通知里那条「退出悬浮窗」action **已按用户要求删除** ✗
            //   （用户原话："通知栏里彻底不留任何退出口" ✓）⇒ 保活通知现在**没有任何按钮** ✓
            //   （它仍然只是前台服务的常驻通知：标题/正文/点主体进应用 ✓，仅此而已 ✓）。
            //   ★ 视频1 页右滑方案也**已撤回** ✗（用户最终决定：不提供任何逃生通道 ✗）。
            //   ⇒ 用户正常关闭悬浮窗模式的**唯一途径** = **控制台 ③ 页「悬浮窗防退出模式」开关** ✓。
            //   ⚠ `ACTION_EXIT_OVERLAY` 的**处理器保留** ✓（当前无 UI 入口，仅供代码/测试触发 ✓）。
            //   ⚠ 所以不要再往这条通知里加任何 action ✗（用户明确要求通知栏无出口 ✓）。
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        runCatching {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }.onFailure {
            Log.w(TAG, "startForeground(dataSync) 失败，退化调用", it)
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    /**
     * 每 2s 重刷通知（原版 SubScreenKeeper 的"通知守卫"）。
     * 保活与方向两条一起刷 —— 这样即便某条曾因通知权限未开被丢弃，之后也能自动回来。
     */
    private fun startNotificationGuard() {
        guardJob = scope.launch {
            while (isActive) {
                delay(NOTIFICATION_GUARD_MS)
                runCatching { startForegroundCompat() }
                if (CoverSettings.isOrientationNotifEnabled(this@CoverKeeperService)) {
                    OrientationNotification.show(this@CoverKeeperService, "2s 通知守卫")
                }
                // 常驻悬浮窗模式：统一在这里决定挂出/收起
                runCatching { syncOverlayWindow() }
                // ★★ 第六轮第 3 项（执行侧）：定时开关运动相机模式
                //   （计划数据/UI 由 settings-console 提供：`CoverSettings.cameraModeSchedules` ✓）
                runCatching { runCameraModeSchedules() }
                // 边缘手势锁定：只在"启动器确实在副屏前台"时存在，
                // 这样既拦住了误退，也绝不会把用户关在别的应用里。
                // ⚠ 除了"我们的界面在副屏"，还要求**副屏前台是桌面**：
                //   否则从启动器点开设置/相机后，条带会留在别人界面上继续吃掉边缘手势。
                // 边缘手势锁定已从控制台移除（用户要求），这里同步停用：
                // 否则历史设置值 edge_block_enabled=true 会让它继续生效，而用户已无处可关。
                runCatching { EdgeBlocker.hide("功能已下线") }
            }
        }
    }

    // ==================================================================
    // 第六轮第 3 项（执行侧）：定时开关运动相机模式
    // ==================================================================

    /** 已经执行过的"分钟"（毫秒取整到分钟），用于"同一分钟只执行一次" */
    private var lastScheduleMinute = Long.MIN_VALUE

    /** 本次进程是否已经做过"启动收敛"（见 [runCameraModeSchedules]） */
    private var scheduleConverged = false

    /**
     * 执行「定时开关运动相机模式」（数据/UI 由 settings-console 提供）。
     *
     * 语义与防抖（Lead 定死 + 实现说明）：
     *  1. **到点执行**：每条启用中的计划用 [CameraModeSchedule.matches] 判当前分钟是否命中
     *     （ISO 星期换算在数据类里统一做好了，执行侧不自己算星期 ✓）；
     *  2. **同一分钟只执行一次**：`lastScheduleMinute` 记录"已经处理过的那一分钟"，
     *     同一分钟内多次进入（2s 守卫每 2s 一次 ✓）直接跳过 ⇒ 不会反复 setXxx 抖动 ✓；
     *  3. **启动收敛**：进程/服务重启后**不能乱开乱关** ✗ —— 只按"当前时间点的应有状态"收敛一次：
     *     取"今天已经过去的最晚一条启用计划"（今天没有则回看昨天的最晚一条）作为目标值，
     *     只有与当前模式**不一致**时才写入 ✓，并落盘 `[Schedule] 启动收敛…` ✓。
     *     （例如 22:00 开、07:00 关：23:00 重启 → 收敛为"开" ✓；21:00 重启 → 收敛为"关" ✓）
     *  4. 每次真正改变模式后：立刻 `syncOverlayWindow()` + 刷通知（见调用点每 2s 已刷 ✓）。
     */
    private fun runCameraModeSchedules() {
        val plans = CoverSettings.cameraModeSchedules(this).filter { it.enabled }
        val calendar = java.util.Calendar.getInstance()

        // ---- 启动收敛（每个进程只做一次）----
        if (!scheduleConverged) {
            scheduleConverged = true
            runCatching { convergeCameraModeOnStart(plans) }
        }

        if (plans.isEmpty()) return

        // ---- 到点执行（同一分钟只执行一次）----
        val minuteKey = (calendar.timeInMillis / 60_000L) * 60_000L
        if (minuteKey == lastScheduleMinute) return
        lastScheduleMinute = minuteKey

        var changed = false
        // ★ 同一分钟若有多条命中：按 settings-console 的建议取 **id 升序的最后一条**
        //   （界面写入时已按 hour/minute/id 排序 ⇒ 行为可预期；否则"两条同刻一开一关"
        //    的结果会取决于列表顺序 ✗）。这里只套用这一条，并把"被覆盖的其它条"落盘 ✓。
        val hit = plans.filter { runCatching { it.matches(calendar) }.getOrDefault(false) }
            .maxByOrNull { it.id }
        if (hit != null) {
            if (plans.count { runCatching { it.matches(calendar) }.getOrDefault(false) } > 1) {
                CoverDebugLog.log(
                    "Schedule",
                    "同一分钟有 ${plans.count { runCatching { it.matches(calendar) }.getOrDefault(false) }} 条命中" +
                        " → 按 id 取最后一条 id=${hit.id}（${hit.timeText}）执行"
                )
            }
            val current = CoverSettings.isCameraModeEnabled(this)
            if (current == hit.turnOn) {
                CoverDebugLog.log(
                    "Schedule",
                    "到点命中 id=${hit.id} ${hit.timeText}（${hit.weekdayText}）" +
                        "→ ${if (hit.turnOn) "开" else "关"}，但当前状态已一致，跳过 ✓"
                )
            } else {
                CoverDebugLog.log(
                    "Schedule",
                    "执行 id=${hit.id} ${hit.timeText}（${hit.weekdayText}）：" +
                        "运动相机模式 ${if (current) "开" else "关"} → ${if (hit.turnOn) "开" else "关"}"
                )
                CoverSettings.setCameraModeEnabled(this, hit.turnOn)
                changed = true
            }
        }
        if (changed) {
            runCatching { syncOverlayWindow() }
            runCatching { startForegroundCompat() }
            if (CoverSettings.isOrientationNotifEnabled(this)) {
                OrientationNotification.show(this@CoverKeeperService, "定时计划改变了运动相机模式")
            }
        }
    }

    /**
     * 启动收敛：把模式设成"当前时刻**本来就应该**是的状态"，从而跨重启/跨天保持一致 ✓。
     *
     * 只回看**已经发生**的计划（今天 ≤ 现在；今天还没有任何一条时回看昨天的最晚一条），
     * 绝不"提前"应用未来的计划 ✗ —— 那正是一重启就乱开乱关的根因 ✓。
     * 与当前状态一致时**不写**（避免无谓地触发界面刷新）✓。
     */
    private fun convergeCameraModeOnStart(plans: List<CameraModeSchedule>) {
        if (plans.isEmpty()) return
        val now = java.util.Calendar.getInstance()

        fun latestBefore(dayOffset: Int): CameraModeSchedule? {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = now.timeInMillis
            if (dayOffset != 0) cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
            // 用"当天的星期"判星期集合：matches() 只看 hour/minute/weekdays，所以先把它
            // 的时间设成该天，再比较分钟数是否 ≤ 现在（dayOffset=0 时）或整点取当天最晚（-1 时不比较）
            val candidates = plans.filter { plan ->
                if (!weekdayAllowed(plan, cal)) return@filter false
                if (dayOffset != 0) return@filter true
                val planMinutes = plan.hour * 60 + plan.minute
                val nowMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                    now.get(java.util.Calendar.MINUTE)
                planMinutes <= nowMinutes
            }
            return candidates.maxByOrNull { it.hour * 60 + it.minute }
        }

        val target = latestBefore(0) ?: latestBefore(-1)
        if (target == null) {
            CoverDebugLog.log("Schedule", "启动收敛：没有可回看的启用计划，保持当前状态 ✓")
            return
        }
        val current = CoverSettings.isCameraModeEnabled(this)
        if (current == target.turnOn) {
            CoverDebugLog.log(
                "Schedule",
                "启动收敛：按 ${target.timeText}（${target.weekdayText}）应为" +
                    "${if (target.turnOn) "开" else "关"}，与当前一致，无需改 ✓"
            )
            return
        }
        CoverDebugLog.log(
            "Schedule",
            "启动收敛：按 ${target.timeText}（${target.weekdayText}）把运动相机模式 " +
                "${if (current) "开" else "关"} → ${if (target.turnOn) "开" else "关"}"
        )
        CoverSettings.setCameraModeEnabled(this, target.turnOn)
    }

    /** 计划是否适用于给定日期（空 weekdays = 每天） */
    private fun weekdayAllowed(plan: CameraModeSchedule, cal: java.util.Calendar): Boolean {
        if (plan.weekdays.isEmpty()) return true
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val iso = if (dow == java.util.Calendar.SUNDAY) 7 else dow - 1
        return plan.weekdays.contains(iso)
    }

    /** 60s AlarmManager 看门狗：把被系统悄悄停掉的自己再叫起来 */
    private fun registerAlarmWatchdog() {
        runCatching {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getService(
                this,
                100,
                Intent(this, CoverKeeperService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                WATCHDOG_INTERVAL_MS,
                pi
            )
        }.onFailure { Log.w(TAG, "注册看门狗失败", it) }
    }

    // ---------------- 触发路径 1：副屏点亮 ----------------

    private fun registerDisplayListener() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = changed(displayId)
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) = changed(displayId)

            private fun changed(displayId: Int) {
                if (displayId != CoverDisplay.coverDisplayId(this@CoverKeeperService)) return
                val display = runCatching { dm.getDisplay(displayId) }.getOrNull() ?: return

                // ★ 只看 OFF→ON 的**边沿**。
                //   onDisplayChanged 在我们界面自己引起显示变化（转场/亮度/旋转）时也会回调，
                //   不判边沿就会形成"拉起 → 显示变化 → 再拉起"的自激环，界面反复跳动。
                val nowOn = display.state == Display.STATE_ON
                val wasOn = lastCoverDisplayState == Display.STATE_ON
                lastCoverDisplayState = display.state
                if (nowOn && !wasOn) {
                    // 副屏重新点亮 = 一段新的使用会话：解除悬浮窗的"让位"状态
                    CoverOverlayState.clearSuppress()
                    pullToCover("副屏点亮")
                }
            }
        }
        displayListener = listener
        runCatching { dm.registerDisplayListener(listener, null) }
    }

    // ---------------- 触发路径 2：折叠动作 ----------------

    private fun startFoldDetector() {
        val detector = FoldDetector(this) { state ->
            if (state == FoldDetector.State.FOLDED || state == FoldDetector.State.HALF_FOLDED) {
                pullToCover("折叠=$state")
            }
        }
        foldDetector = detector
        detector.start()
    }

    // ---------------- 触发路径 3：副屏前台不是我们 ----------------

    private fun startForegroundPoller() {
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                // ★ 这里保持用"生效值" `isAutoPullEnabled()`（= 意愿 && 相机模式 && 屏幕闸）：
                //   在非 Carousel 界面上它已经是 false ⇒ 本循环直接 continue，
                //   正是"非运动相机界面不拉起"要的效果 ✓（settings-console 把闸折进了这个 getter ✓）。
                //   不在这里落盘：本循环 1.5s 一次，打日志会刷屏 ✗（真正被拦时的原因见 pullToCover ✓）。
                if (!CoverSettings.isAutoPullEnabled(this@CoverKeeperService)) continue
                if (!ShizukuShell.isUsable) continue      // 没授权就交给无障碍那条路
                val coverId = CoverDisplay.coverDisplayId(this@CoverKeeperService)
                if (coverId == Display.DEFAULT_DISPLAY) continue

                val top = CoverAppLauncher.queryTopResumed() ?: continue
                val (pkg, display) = top
                if (display != coverId) continue
                if (pkg == packageName) continue

                val triggers = CoverSettings.triggerPackages(this@CoverKeeperService)
                if (pkg in homePackages || pkg in triggers) {
                    pullToCover("前台=$pkg@$display")
                }
            }
        }
    }

    // ---------------- 拉起 ----------------

    private fun pullToCover(reason: String) {
        // ★「不主动弹出」模式下不做自动拉起：自动拉起会把我们的界面重新推到副屏前台，
        //   前台判定随之翻回"我们自己"，悬浮窗就会盖到用户正在用的应用上面。
        if (CoverSettings.isOverlayPassive(this)) {
            CoverDebugLog.log("Keep", "pull($reason): 不主动弹出模式，跳过拉起")
            return
        }
        // ★ 第六轮（settings-console 收紧语义后）：`isAutoPullEnabled()`
        //   现在 = `isAutoPullArmed() && overlayAllowedOnCurrentScreen()` ✓（把"屏幕闸"折进了生效值 ✓）。
        //   所以这里**先判 `isAutoPullArmed`**（意愿 + 运动相机模式开着）——
        //   这样日志上的原因才是准确的：到底是"用户没开/M模式关"，还是"本项设置要求只在主界面拉起" ✓。
        //   （若先判 `isAutoPullEnabled`，在桌面上被屏幕闸挡住时会被写成"自动拉起未开启" ✗，误导排查 ✓）
        if (!CoverSettings.isAutoPullArmed(this)) {
            CoverDebugLog.log("Keep", "pull($reason): 自动拉起未启用（意愿关 / 运动相机模式关），跳过")
            return
        }

        // ★★ 第六轮第 1b 项兼容规则：「非运动相机界面不使用悬浮窗」开启时，
        //   「回到桌面自动拉起」**只对运动相机主界面（Carousel）生效** ✓ ——
        //   即"回到桌面"若落在桌面/应用列表等界面，**不要**把我们的界面拉回副屏 ✓
        //   （否则就和"桌面不用悬浮窗"自相矛盾 ✗）。
        //   判据复用同一个共享判断：`CoverSettings.overlayAllowedOnCurrentScreen()`；
        //   读不到状态时它按"允许"处理（CoverScreenGate 默认 true）✓。
        if (!runCatching { CoverSettings.overlayAllowedOnCurrentScreen(this) }.getOrDefault(true)) {
            CoverDebugLog.log(
                "Keep",
                "pull($reason): 「非运动相机界面不使用悬浮窗」已开启且当前不在" +
                    "运动相机界面（主界面/相簿/视频）" +
                    "（CoverScreenGate=${CoverScreenGate.currentScreenIsCarousel}）→ 跳过自动拉起"
            )
            return
        }

        // ★★☠ 紧急修复（Lead 第 2 点：**禁止任何"补位拉起"**）：
        //   我们自己的界面（任一 Screen）**已经在副屏前台**时，绝不拉起 ✗✗ ——
        //   拉起会让 singleTask 的我们的界面走 `onNewIntent` ⇒ **导航栈重置**
        //   ⇒ 用户看到的正是"一进应用列表就被弹回运动相机主界面" ✗
        //   （与 1b 的"非 Carousel 收起"凑成 `收起 → 拉起 → 再收起` 死循环 ✓）。
        //   判据用使用记录里**副屏前台活动是不是我们自己**（权威 ✓，与 syncOverlayWindow 同一信号 ✓）。
        runCatching { CoverForeground.refresh(this) }
        if (CoverForeground.coverPackage == packageName) {
            CoverDebugLog.log(
                "Keep",
                "pull($reason): 我们自己的界面已在副屏前台（coverPackage=$packageName）" +
                    "→ 跳过补位拉起 ✓（避免 onNewIntent 重置导航栈 / 死循环）"
            )
            return
        }
        val coverId = CoverDisplay.coverDisplayId(this)
        if (coverId == Display.DEFAULT_DISPLAY) {
            CoverDebugLog.log("Keep", "pull($reason): 无副屏，跳过")
            return
        }

        // ★ 最重要的一道闸：界面已经在副屏前台，就绝不要再拉起。
        //   否则"拉起 → 显示变化/焦点变化 → 再拉起"会自激，界面反复跳动。
        if (CoverPresence.isResumedOn(coverId)) {
            CoverDebugLog.log("Keep", "pull($reason): 副屏界面已在前台，跳过")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastPullAt < PULL_DEBOUNCE_MS) return      // 防抖
        lastPullAt = now

        // 先把屏幕点亮：息屏时只靠 Activity 的 turnScreenOn 有可能赶不上
        // （启动请求被系统推迟），这里主动亮屏一把，成功率更高。
        wakeScreenBriefly()

        val canOverlay = Settings.canDrawOverlays(this)
        scope.launch {
            val result = CoverAppLauncher.launchSelfToCover(this@CoverKeeperService, coverId)
            Log.d(TAG, "pull($reason) overlay=$canOverlay -> $result")
        }
    }

    /**
     * 短暂点亮屏幕（5 秒后自动释放）。
     * SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP 是能真正把息屏屏幕点亮的组合，
     * 需要 WAKE_LOCK 权限（已声明）。
     */
    private fun wakeScreenBriefly() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                "sportscam:cover-wake"
            )
            lock.acquire(5_000L)
            Log.d(TAG, "已请求点亮屏幕（5s）")
        }.onFailure { Log.w(TAG, "点亮屏幕失败", it) }
    }
}
