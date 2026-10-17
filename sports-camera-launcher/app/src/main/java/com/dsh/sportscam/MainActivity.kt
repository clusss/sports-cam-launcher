package com.dsh.sportscam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.app.KeyguardManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.dsh.sportscam.cover.CoverAppLauncher
import com.dsh.sportscam.cover.CoverDebugLog
import com.dsh.sportscam.cover.CoverDisplay
import android.view.KeyEvent
import com.dsh.sportscam.cover.CoverKeeperService
import com.dsh.sportscam.cover.CoverOverlayState
import com.dsh.sportscam.cover.CoverPresence
import com.dsh.sportscam.cover.CoverSettings
import com.dsh.sportscam.cover.CoverScreenGate
import com.dsh.sportscam.cover.OverlayResultHost
import com.dsh.sportscam.cover.VolumeGuard
import com.dsh.sportscam.cover.OrientationNotification
import com.dsh.sportscam.cover.ShizukuShell
import com.dsh.sportscam.cover.rememberCoverDisplayId
import com.dsh.sportscam.device.FlashlightController
import com.dsh.sportscam.device.IntentLauncher
import com.dsh.sportscam.media.MediaItem
import com.dsh.sportscam.model.LauncherItems
import com.dsh.sportscam.ui.AppListScreen
import com.dsh.sportscam.ui.TrashScreen
import com.dsh.sportscam.ui.WallpaperEditorScreen
import com.dsh.sportscam.ui.AppListUiState
import com.dsh.sportscam.ui.CarouselScreen
import com.dsh.sportscam.ui.ConsoleScreen
import com.dsh.sportscam.ui.AboutScreen
import com.dsh.sportscam.ui.DisclaimerScreen
import com.dsh.sportscam.ui.DisplaySettingsScreen
import com.dsh.sportscam.ui.FolderScreen
import com.dsh.sportscam.ui.MediaBrowseScreen
import com.dsh.sportscam.ui.MediaBrowseUiState
import com.dsh.sportscam.ui.MediaViewerScreen
import com.dsh.sportscam.ui.PrivateAppsScreen
import com.dsh.sportscam.ui.ScreenshotRecordScreen
import com.dsh.sportscam.ui.SquareHomeScreen
import com.dsh.sportscam.ui.common.EdgeGestureExclusion
import com.dsh.sportscam.ui.common.PasswordGate
import com.dsh.sportscam.ui.common.PasswordGateHost
import com.dsh.sportscam.ui.common.StatusBarSwipeBlocker
import com.dsh.sportscam.ui.common.rememberPasswordGate
import com.dsh.sportscam.ui.common.showToast
import com.dsh.sportscam.ui.common.showToastDelayed
import com.dsh.sportscam.ui.theme.SportsCamBlack
import com.dsh.sportscam.ui.theme.SportsCamTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 启动器的界面宿主（基类）。
 *
 * 为什么要有基类 + 两个子类：
 * 《超级小副屏》的稳定启动靠的是「入口 Activity 用默认 affinity，副屏界面 Activity 用
 * **独立 taskAffinity**」——这样把界面投到副屏时会形成**独立的任务栈**，
 * 而不是复用主屏上那个已经存在、处于后台的任务（后者正是"被唤起却停在后台"的根因）。
 *
 * 两个子类：
 *  - [MainActivity]       入口（manifest 里用默认 affinity），负责把副屏界面投放过去；
 *  - [CoverHomeActivity]  副屏界面（manifest 里 taskAffinity=cn.pashiguoke.subscreen.cover，
 *                         并注册 SECONDARY_HOME）。
 *
 * 职责：
 *  - 沉浸式：隐藏状态栏 / 导航栏；主界面屏蔽左右边缘返回手势
 *  - 锁屏状态下也能直接显示（showWhenLocked + turnScreenOn + requestDismissKeyguard）
 *  - 实时跟踪自己所在的显示器，必要时把自己投到副屏 / 拉回前台
 *  - 维持 Shizuku 通道；离开是否关手电筒由设置决定
 */
open class BaseLauncherActivity : ComponentActivity() {

    private lateinit var flashlight: FlashlightController

    /**
     * 本 Activity 所在的 Display（副屏时 != DEFAULT_DISPLAY）。
     *
     * 反编译报告指出的缺口 #1：原来只在 onCreate 读一次，
     * 被搬到别的屏 / 重建之后用的全是过期值。现在改成 Compose state，
     * 并在 onResume、onConfigurationChanged、DisplayListener 里刷新。
     */
    private val displayIdState = mutableIntStateOf(Display.DEFAULT_DISPLAY)

    private var displayListener: DisplayManager.DisplayListener? = null

    /**
     * "重新进入"信号。副屏界面是 singleTask，从快捷启动再次进来时**不会重建 Activity**，
     * 只会走 onNewIntent / onRestart —— Compose 里的导航栈会保留在上次的界面。
     * 按需求每次进入都要回到主界面，所以这里发一个自增信号让界面重置。
     *
     * ⚠ 第三轮收口：只有**真的停在别处**时才自增 —— 已经在主页（且栈里只有主页）时不发，
     *   因为那种"重置"与现状结构相等、没有可见价值，却会重建整棵桌面 Compose 树（闪一下）。
     *   判定见 [shouldSignalReentryHome]。
     */
    val reentryTick = mutableIntStateOf(0)

    override fun onRestart() {
        super.onRestart()
        if (shouldSignalReentryHome()) {
            reentryTick.intValue++
            CoverDebugLog.log(LOG_TAG, "onRestart: 重新进入 → 通知界面回到主界面")
        } else {
            CoverDebugLog.log(LOG_TAG, "onRestart: 重新进入，但已在主页（或未开启回主页）→ 不重置导航栈")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (shouldSignalReentryHome()) {
            reentryTick.intValue++
            CoverDebugLog.log(LOG_TAG, "onNewIntent: 再次被拉起 → 通知界面回到主界面")
        } else {
            CoverDebugLog.log(
                LOG_TAG,
                "onNewIntent: 再次被拉起，但已在主页 → 不重置（避免重建整棵桌面 Compose 树）"
            )
        }
    }

    /**
     * 是否值得发一次"回到主界面"信号（[reentryTick] 自增）。
     *
     * 为什么要判（第三轮收口）：真机日志显示**同一次 onCreate 会把界面投到副屏两次**，
     * 第二次触发 `onNewIntent`；而重置导航栈本身对用户**没有可见价值**（他已经在主页），
     * 却会让 `SportsCamApp` 里那条 `LaunchedEffect(reentryTick, …)` 重建整棵桌面树 ——
     * 表现就是"桌面图标/名称闪一下、反复重载"。
     *
     * 判定（全部满足才**不**发信号）：
     *  · 当前栈里**只有一页**；且
     *  · 这一页就是该「运动相机模式」对应的主页
     *    （开 → [Screen.Carousel] 运动相机主界面；关 → [Screen.Desktop] 桌面）。
     *   此时 `stack = listOf(home)` 与现状**结构相等**（[Screen] 全是 data object / data class），
     *   重置等于什么都没做。
     * 另外：设置里关掉「退出应用后回到主界面」时也不发（界面侧本来就会跳过重置）。
     *
     * ⚠ [reentryTick] 的语义不变：**真的停在别的界面**时照旧自增、照旧回主页。
     */
    private fun shouldSignalReentryHome(): Boolean {
        if (!CoverSettings.isReturnToHomeOnReentry(this)) return false
        val stack = LauncherNavState.stack.value
        if (stack.isEmpty()) return true        // 还没初始化 → 交给界面按设置定初始页
        // 第十五轮：主屏大屏模式的"主页"是控制台，与桌面/主界面无关 ✓
        val home = LauncherNavState.initialScreen(CoverSettings.isCameraModeEnabled(this))
        return !(stack.size == 1 && stack.first() == home)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Shizuku 通道：注册 binder 存活/断开监听并刷新状态
        ShizukuShell.init(applicationContext)

        // ★ 锁屏相关声明**只给副屏界面**（对齐原版 SubActivity）。
        //
        //   入口跳板刻意**不要** showWhenLocked / turnScreenOn（见 manifest），
        //   并且运行时也不能调 setShowWhenLocked —— 因为 vivo 的快捷启动(SmartKey)
        //   会根据"目标 Activity 是否自己声明了 showWhenLocked"来决定**要不要弹指纹验证**：
        //     · 声明了 → 认为应用自己能处理锁屏 → 直接启动、不弹指纹（我们之前就是这样，
        //                于是卡死在 vivo 自绘锁屏之下）
        //     · 没声明 → 先弹指纹，验证通过再启动（原版小副屏的 MainActivity 就是这种）
        //   所以入口必须"看起来像个普通主屏应用"，把验证交给系统。
        if (!isEntryTrampoline()) {
            showWhenLockedAndDismissKeyguard()
        }

        applyImmersiveMode()

        // 副屏常亮，避免息屏后手势失效
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        flashlight = FlashlightController(applicationContext)
        displayIdState.intValue = resolveDisplayId()
        registerDisplayListener()
        // ★ 第十五轮：先判定"主屏大屏 / 副屏启动器"（初始页、放大系数、自投放都要用）
        detectUiMode()

        // 落盘调试日志（ROM 会吞掉我们的 Log.d，这是唯一能看到内部决策的途径）
        CoverDebugLog.init(applicationContext)
        CoverDebugLog.log(
            LOG_TAG,
            "onCreate class=${javaClass.name} displayId=${displayIdState.intValue} " +
                "savedInstanceState=${savedInstanceState != null}"
        )

        // ★ 入口跳板：**同步**投放（绝不能走 decorView.post —— 详见
        //   placeCoverHomeImmediately 的说明：入口可能在 10ms 内就被系统 pause，
        //   挂在 View 上的 runnable 将永远不会执行）。
        //   副屏界面自身：延后一帧再决定要不要自我投放。
        //
        // ★ 第十五轮：**主屏大屏模式 → 一律不投放** ✓
        //   用户在主屏（或用电脑投屏看主屏）点开应用时，要就地看到「控制台」，
        //   而不是被扔到副屏去 ✗（跳板兜底界面本来就有，所以不投放也不会黑屏 ✓）。
        if (isEntryTrampoline()) {
            if (LauncherUiMode.isMainScreen) {
                CoverDebugLog.log(LOG_TAG, "placeNow: 主屏大屏模式 → 不投放副屏，就地渲染控制台")
            } else {
                placeCoverHomeImmediately()
            }
        } else {
            maybePlaceSelfOnCover()
        }

        // ★ 被唤起到后台、界面没上来时，主动把自己搬到前台
        scheduleFrontAssert()

        // 保活服务：之前只有手动开开关才会起，导致"通知要先进应用才出现"。
        // 现在只要开关是开的，进应用就把它保障起来。
        // ⚠ 第十五轮：主屏大屏模式下**不启动保活**（那是副屏专属逻辑：会挂悬浮窗/自动拉起 ✗）
        if (!LauncherUiMode.isMainScreen && CoverSettings.isKeeperEnabled(this)) {
            CoverKeeperService.start(this)
        }

        if (!shouldShowUi()) {
            // 跳板：不渲染界面，onCreate 里那段投放逻辑会把自己送去副屏并自毁
            Log.d(TAG, "入口跳板模式：不渲染界面，直接把副屏界面投过去")
            return
        }

        setContent {
            val torchOn by flashlight.isOn.collectAsState()
            val torchAvailable by flashlight.available.collectAsState()

            // ★ 方向已合并为「整块副屏的方向」（控制台/通知栏只有一套）：
            //   本应用不再自己锁方向，而是**跟随显示器** —— 副屏被锁成什么方向，
            //   本应用就跟着转成什么方向。这样"改副屏方向"必然可见、也只有一个真相来源。
            LaunchedEffect(Unit) {
                runCatching {
                    if (requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }

            // ⚠ 这里原来还有一句 `LaunchedEffect(Unit) { maybePlaceSelfOnCover() }`，
            //   注释写的是"兜底：onResume 再查一次"——**已删除**（第三轮收口，真机日志铁证）：
            //   ① onResume 里从来没有再查（注释与实现不符），它实际是在**同一次 onCreate 里
            //      把本应用界面投到副屏第二次**（日志：00:04:23.334 与 .345 相隔 29ms 两次
            //      `place: 投放结果=Success`）；
            //   ② 第二次投放会触发 `onNewIntent` → 导航栈重置 → **整棵桌面 Compose 树重建**
            //      （图标/名称闪一下）= 用户报的"桌面反复重载"。
            //   投放去重已由 `CoverAppLauncher.launchCoverHomeToCover` 自己负责
            //   （presence 已 resumed 就跳过 / 600ms 内重复投放丢弃），这里不需要重复兜底。

            SportsCamApp(
                flashlightOn = torchOn,
                flashlightAvailable = torchAvailable,
                displayId = displayIdState.intValue,
                reentryTick = reentryTick.intValue,
                returnToHomeOnReentry = CoverSettings.isReturnToHomeOnReentry(this),
                onToggleTorch = {
                    val ok = flashlight.toggle()
                    if (!ok) {
                        Toast.makeText(this, "手电筒不可用", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    /**
     * 允许在锁屏之上显示 + 点亮屏幕 + 主动请求解除锁屏。
     *
     * 息屏状态下被快捷方式（vivo 音量下长按 / SmartKey）拉起时，如果只靠 manifest 的
     * showWhenLocked/turnScreenOn，系统往往只在锁屏上放一段启动动画就把界面挡在后面。
     * 原版《超级小副屏》的 SubBaseActivity 就是显式调用这三个 API 才做到"直接进界面"。
     */
    private fun showWhenLockedAndDismissKeyguard() {
        // API 27+ 的官方写法（本工程 minSdk 29，必然可用）
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        // 闪屏/无密码锁屏：直接亮屏解锁
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        // 有密码的锁屏：请求系统解除（会弹一次解锁，但不会把界面挡死）
        requestKeyguardDismissNow()
    }

    /**
     * 请求解除锁屏。
     *
     * ⚠ `requestDismissKeyguard` 在"Activity 不在前台"时是 **no-op**，
     * 所以只在 onCreate 调一次并不保险（息屏快捷启动时 Activity 刚创建，
     * 常常还没到前台就被 pause 了）。onResume 里会再试一次。
     */
    private fun requestKeyguardDismissNow() {
        runCatching {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
            if (!km.isKeyguardLocked) return
            CoverDebugLog.log(LOG_TAG, "请求解除锁屏（keyguard 仍锁定）")
            km.requestDismissKeyguard(this, null)
        }.onFailure {
            Log.w(TAG, "requestDismissKeyguard 失败", it)
            CoverDebugLog.log(LOG_TAG, "requestDismissKeyguard 失败: ${it.message}")
        }
    }

    /** onResume 时的重试：此时我们确实在前台，解除请求才可能生效 */
    private fun retryDismissKeyguardIfLocked() = requestKeyguardDismissNow()

    /** 当前 Activity 所在 Display；拿不到返回 null（不要当成主屏！） */
    @Suppress("DEPRECATION")
    private fun selfDisplayOrNull(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            runCatching { windowManager.defaultDisplay }.getOrNull()
        }

    /** 取得当前 Activity 所在屏幕 id（API 30+ 用 getDisplay()） */
    private fun resolveDisplayId(): Int =
        selfDisplayOrNull()?.displayId ?: Display.DEFAULT_DISPLAY

    private fun refreshDisplayId() {
        displayIdState.intValue = resolveDisplayId()
    }

    /**
     * 第十五轮：判定自己跑在**主屏**还是**副屏**，并把结果写到 [LauncherUiMode]。
     *
     * 必须在 `setContent {}` 与任何"自投放"之前调用一次（初始页与放大系数都依赖它）。
     * `onResume` 再调一次以覆盖"活动被挪到另一块屏"的情况；只有首次/变化时才打日志。
     */
    private fun detectUiMode() {
        val selfId = resolveDisplayId()
        val coverId = CoverDisplay.coverDisplayId(this)
        // 副屏 id 拿不到（没有副屏的设备）= 默认屏，按主屏模式处理 ✓
        val isMain = coverId == Display.DEFAULT_DISPLAY || selfId != coverId
        val scale = if (isMain) LauncherUiMode.MAIN_SCREEN_DENSITY_SCALE else 1f
        val firstRun = !LauncherUiMode.detected
        val changed = LauncherUiMode.isMainScreen != isMain
        LauncherUiMode.isMainScreen = isMain
        LauncherUiMode.densityScale = scale
        LauncherUiMode.detected = true
        if (firstRun || changed) {
            if (isMain) {
                CoverDebugLog.log(
                    "Main",
                    "检测到主屏 display=$selfId → 显示控制台（大屏模式 sizeScale=$scale）"
                )
            } else {
                CoverDebugLog.log("Main", "副屏 display=$selfId → 正常启动器")
            }
        }
    }

    /**
     * 把**副屏界面 Activity** 投到副屏。
     *
     * 《超级小副屏》的模式：在主屏被拉起后，立刻用
     * `ActivityOptions.setLaunchDisplayId(副屏id)` + `FLAG_ACTIVITY_NEW_TASK`
     * 把自己的副屏界面放到副屏。这条路径**不经过 ROM 的"副屏打开第三方应用"白名单**。
     *
     * ★ 关键：[CoverHomeActivity] 在 manifest 里带**独立 taskAffinity**
     * （对齐原版 SubActivity 的 `cn.pashiguoke.subscreen.cover`），
     * 所以投放时会在副屏上形成一个**独立任务栈**，
     * 而不是复用主屏上那个已经存在、处于后台的任务
     * —— 后者正是"被唤起却停在后台、要手动上拉后台管理"的根因。
     *
     * 用 `decorView.post` 延后一帧：`View.post` 只依赖消息队列、不依赖窗口有没有 Surface，
     * 所以即使 Activity 被起在看不见的主屏上（Compose 可能根本不组合）也能执行。
     */
    private fun maybePlaceSelfOnCover() {
        // ★ 第十五轮：主屏大屏模式**绝不**自投放（否则用户在电脑上点一下就被扔到副屏 ✗）
        if (LauncherUiMode.isMainScreen) {
            CoverDebugLog.log(LOG_TAG, "maybePlace: 主屏大屏模式，跳过自投放")
            return
        }
        if (!CoverSettings.isAutoPlaceOnStart(this)) {
            CoverDebugLog.log(LOG_TAG, "maybePlace: 自动投屏设置已关闭，跳过")
            return
        }
        CoverDebugLog.log(LOG_TAG, "maybePlace: 已排入 decorView.post")
        runCatching { window.decorView.post { placeSelfOnCoverIfNeeded() } }
    }

    /** 投放成功后是否收掉自己：入口 Activity 收掉，副屏界面 Activity 留着。 */
    protected open fun shouldFinishAfterPlacement(): Boolean = false

    /**
     * 是否只是"跳板"：入口 Activity 返回 true —— 它不做任何界面，
     * 唯一职责就是把副屏界面投到副屏然后自毁。
     */
    protected open fun isEntryTrampoline(): Boolean = false

    /** 是否渲染界面（跳板不渲染） */
    protected open fun shouldShowUi(): Boolean = true

    /**
     * 是否把自己登记进 [CoverPresence]（"启动器界面正在某块屏上前台"）。
     *
     * ⚠ 只有**副屏界面**能登记。入口跳板不能登记 ——
     * 跳板自己也可能起在副屏上，若它也登记，[placeSelfOnCoverIfNeeded] 里那句
     * "副屏已在前台就别再投放"就会误判成"界面已经在了"，于是跳板直接退场、
     * 什么都不投放，副屏上就永远出不来界面。
     */
    protected open fun tracksPresence(): Boolean = false

    /**
     * 入口跳板：**在 onCreate 里同步投放**，绝不经过 `decorView.post`。
     *
     * 为什么不能用 post（真机实测的教训）：
     * 息屏快捷启动时，入口 Activity 被创建后 **10 毫秒**就会被系统 pause 掉
     * （日志：`onCreate 18:51:51.728` → `onPause 18:51:51.776`），
     * 而挂在 `decorView` 上的 runnable 在 View 被 detach 后会进入 run queue
     * 等待"下次 attach" —— 那个下次永远不会来。
     * 结果就是投放代码**一次都没执行**：应用被拉起了，但什么都没发生。
     *
     * `lifecycleScope.launch` 跑在 `Dispatchers.Main.immediate` 上，
     * 在第一个挂起点之前是同步执行的，而 `launchNative` 就是同步调用，
     * 所以 `startActivity` 会在同一个主线程消息里发出，抢在 pause 之前。
     */
    private fun placeCoverHomeImmediately() {
        val coverId = CoverDisplay.coverDisplayId(this)
        CoverDebugLog.log(LOG_TAG, "placeNow: 进入 coverId=$coverId presence=${CoverPresence.debugState()}")
        if (coverId == Display.DEFAULT_DISPLAY) {
            CoverDebugLog.log(LOG_TAG, "placeNow: 没有副屏，跳过")
            return
        }

        lifecycleScope.launch {
            val tokenBefore = CoverPresence.token()
            // 下面这两行是同步执行的（Main.immediate），会立刻发出 startActivity
            val result = CoverAppLauncher.launchCoverHomeToCover(this@BaseLauncherActivity, coverId)
            CoverDebugLog.log(LOG_TAG, "placeNow: 投放结果=$result tokenBefore=$tokenBefore")

            // 悬浮窗覆盖 vivo 锁屏这条路**已实测走不通**（用户确认）：
            // vivo 副屏锁屏是自绘系统窗口，应用悬浮窗虽然层级在上，
            // 但实际使用体验不对，且不属于正确解法。正确解法是让系统的快捷启动
            // 先弹指纹验证（见 onCreate 里对 isEntryTrampoline 的注释）。
            CoverDebugLog.log(LOG_TAG, "placeNow: 完成（锁屏验证交给系统快捷启动流程）")

            if (!shouldFinishAfterPlacement()) {
                CoverDebugLog.log(LOG_TAG, "placeNow: 非跳板，不退场")
                return@launch
            }

            // 只有确认副屏界面真的 resume（令牌变化）才退场
            var coverTookOver = false
            for (attempt in 0 until VERIFY_RETRIES) {
                delay(VERIFY_STEP_MS)
                if (CoverPresence.token() != tokenBefore) {
                    coverTookOver = true
                    break
                }
                if (attempt == 1) {
                    CoverDebugLog.log(LOG_TAG, "placeNow: 等待 ${(attempt + 1) * VERIFY_STEP_MS}ms 未接管，补投一次")
                    runCatching { CoverAppLauncher.launchCoverHomeToCover(this@BaseLauncherActivity, coverId) }
                }
            }

            if (coverTookOver) {
                CoverDebugLog.log(LOG_TAG, "placeNow: 副屏界面已接管（${CoverPresence.debugState()}），跳板退场")
                runCatching { finish() }
            } else {
                CoverDebugLog.log(LOG_TAG, "placeNow: 超时未被接管（${CoverPresence.debugState()}），跳板保持自身界面")
            }
        }
    }

    private fun placeSelfOnCoverIfNeeded() {
        if (isFinishing || isDestroyed) {
            CoverDebugLog.log(LOG_TAG, "place: 已 finishing/destroyed，跳过")
            return
        }

        val coverId = CoverDisplay.coverDisplayId(this)
        CoverDebugLog.log(
            LOG_TAG,
            "place: 进入 class=${javaClass.simpleName} coverId=$coverId " +
                "selfDisplay=${selfDisplayOrNull()?.displayId} trampoline=${isEntryTrampoline()} " +
                "finishAfter=${shouldFinishAfterPlacement()} presence=${CoverPresence.debugState()}"
        )
        if (coverId == Display.DEFAULT_DISPLAY) {
            CoverDebugLog.log(LOG_TAG, "place: 没有副屏，跳过")
            return
        }

        // ★ 这里**故意不再**用 CoverPresence 做"已在副屏前台就提前退场"的判断。
        //   存活性标记一旦残留（锁屏 / 息屏 / 被搬屏 / 生命周期重复投递的时序都会导致），
        //   入口跳板就会在 2ms 内自毁，界面永远出不来 —— 这在真机上连续复现了三次
        //   （18:41:04 / 18:41:17 / 18:42:21，以及 18:47:07 三次）。
        //   现在入口跳板**无条件投放**：重复投放 singleTask 的副屏界面最多只是
        //   "把它置顶一次"（系统日志里是 setFocusedTask-alreadyTop），不会抖动。
        //
        //   副屏界面自己：只有确实被起在主屏上时才需要自我投放。
        if (!isEntryTrampoline()) {
            val selfDisplay = selfDisplayOrNull()
            if (selfDisplay?.displayId != Display.DEFAULT_DISPLAY) {
                CoverDebugLog.log(LOG_TAG, "place: 副屏界面已在副屏，跳过")
                return
            }
        }

        lifecycleScope.launch {
            val tokenBefore = CoverPresence.token()
            val result = CoverAppLauncher.launchCoverHomeToCover(this@BaseLauncherActivity, coverId)
            CoverDebugLog.log(LOG_TAG, "place: 投放结果=$result tokenBefore=$tokenBefore")

            if (!shouldFinishAfterPlacement()) {
                CoverDebugLog.log(LOG_TAG, "place: 非跳板，投放后不退场")
                return@launch
            }

            // ★★ 只有**确认副屏界面真的 resume 了**（令牌变化）才退场。
            //    `startActivity` 没抛异常 ≠ 启动成功，系统会静默丢弃这次启动；
            //    令牌变化是进程内唯一可靠的"它真的起来了"信号。
            var coverTookOver = false
            for (attempt in 0 until VERIFY_RETRIES) {
                delay(VERIFY_STEP_MS)
                if (CoverPresence.token() != tokenBefore) {
                    coverTookOver = true
                    break
                }
                if (attempt == 1) {
                    CoverDebugLog.log(LOG_TAG, "place: 等待 ${(attempt + 1) * VERIFY_STEP_MS}ms 未接管，补投一次")
                    runCatching { CoverAppLauncher.launchCoverHomeToCover(this@BaseLauncherActivity, coverId) }
                }
            }

            if (coverTookOver) {
                CoverDebugLog.log(LOG_TAG, "place: 副屏界面已接管（${CoverPresence.debugState()}），跳板退场")
                runCatching { finish() }
            } else {
                CoverDebugLog.log(LOG_TAG, "place: 超时未被接管（${CoverPresence.debugState()}），跳板保留自身界面")
            }
        }
    }

    /**
     * 被唤起到后台、界面没上来时，主动把自己搬到前台。
     *
     * ⚠ 这里**不能**只看 `hasWindowFocus()`：副屏上我们自己的 0×0 方向锁定悬浮窗
     *   （TYPE_APPLICATION_OVERLAY）会持有窗口焦点，导致该判断恒为 false，
     *   于是这个"保险"会变成"反复把自己重排到前台"的自激环 —— 界面反复跳动。
     *   现在只在**本 Activity 确实不在前台**（没有 RESUMED 或窗口不可见）时才动手，
     *   而且只做一次。
     */
    private fun scheduleFrontAssert() {
        val decor = window.decorView
        runCatching {
            decor.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                val inFront = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && decor.isShown
                if (inFront) return@postDelayed
                moveSelfToFront()
            }, 1_500L)
        }
    }

    /** 把自己的任务搬到前台。只做 moveTaskToFront，不再 startActivity 重排（那会抖动）。 */
    private fun moveSelfToFront() {
        val myTaskId = taskId
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
            @Suppress("DEPRECATION")
            am.moveTaskToFront(myTaskId, android.app.ActivityManager.MOVE_TASK_NO_USER_ACTION)
            Log.d(TAG, "把任务 #$myTaskId 搬到前台（之前不在前台）")
        }.onFailure { Log.w(TAG, "moveTaskToFront 失败", it) }
    }

    /** 副屏热插拔 / 旋转时同步 displayId */
    private fun registerDisplayListener() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = refreshDisplayId()
            override fun onDisplayRemoved(displayId: Int) = refreshDisplayId()
            override fun onDisplayChanged(displayId: Int) = refreshDisplayId()
        }
        displayListener = listener
        runCatching { dm.registerDisplayListener(listener, null) }
    }

    /**
     * 沉浸式：隐藏系统栏 + 允许滑动临时唤出。
     *
     * ★★ 现代 API + **legacy 兜底**（2026-10 真机实测，由 recorder-statusbar 在副屏上抓到）：
     *   这台设备/vivo ROM 的副屏上，现代 `WindowInsetsController.hide()` **不生效** ——
     *   落盘日志 `[StatusBar] reassert hid=true visible=true` 连续出现 604 次，
     *   说明调用成功但系统栏仍然是可见的；而 legacy `systemUiVisibility`
     *   （FULLSCREEN | HIDE_NAVIGATION | IMMERSIVE_STICKY）**确实有效**。
     *   本应用 targetSdk=29（<30），平台仍然尊重 legacy 标志 ✓，所以两条都下：
     *   现代 API 负责新 ROM 的正路，legacy 负责这台机器的实际生效路径
     *   （[com.dsh.sportscam.cover.CoverOverlayWindow] 里本来也是用 legacy 标志的）。
     *
     * `protected open`：副屏界面（[CoverHomeActivity]）要在此之上**再加**一条针对
     * 状态栏的显式隐藏（任务书第 9 项："运动相机主界面永久隐藏状态栏"）。
     */
    protected open fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            // 下拉/上滑只临时显示，且自动隐藏
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // ★ legacy 兜底（见上面的实测结论）。@Suppress 只压废弃警告，不影响行为；
        //   写法与 [com.dsh.sportscam.cover.CoverOverlayWindow] 里那段 legacy 设置保持一致。
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    /**
     * 「状态栏常驻隐藏」的周期性重申（任务书第 9 项）。
     *
     * 为什么光靠 onCreate / onWindowFocusChanged 不够：副屏上用 vivo 的"切后台 / 回桌面"
     * 手势时，系统会把顶部状态栏**顶出来并常驻**（用户实测：要再手势一次才隐藏）。
     * 所以只要本界面在前台，就每秒重申一次（一次调用极便宜，且窗口没变化时是无副作用的）。
     *
     * 停止时机：onPause / onDestroy —— 我们不在前台时不要跟别的应用抢系统栏。
     * ⚠ 说明：基类本来就是**全局隐藏** systemBars（所有界面一致），这里保持这个语义，
     *   不按"是否停在主界面"区分 —— 任务书允许这种简化。
     */
    private val immersiveReassert = object : Runnable {
        override fun run() {
            runCatching { applyImmersiveMode() }
            immersiveHandler.postDelayed(this, IMMERSIVE_REASSERT_MS)
        }
    }
    private val immersiveHandler = Handler(Looper.getMainLooper())

    private fun startImmersiveReassert() {
        immersiveHandler.removeCallbacks(immersiveReassert)
        if (isEntryTrampoline()) return      // 跳板只负责投放，界面在副屏界面那边
        immersiveHandler.post(immersiveReassert)
    }

    private fun stopImmersiveReassert() {
        immersiveHandler.removeCallbacks(immersiveReassert)
    }

    override fun onResume() {
        super.onResume()
        // ★ 第七轮：我们的界面回到前台 → 「非运动相机界面不使用悬浮窗」的判据置 true
        //   （判据语义见 CoverScreenGate 的注释：白名单 = 我们自己的任意一页都算"在前台"）
        CoverScreenGate.currentScreenIsCarousel = true
        // 回到前台时重读屏幕 id，别用过期的值去启动别的应用
        refreshDisplayId()
        // ★ 第十五轮：屏幕可能被挪动过（主屏 ↔ 副屏），重新判定一次界面模式
        //   （只影响"后续"的初始页/放大系数；已经在显示的界面不会突然跳变 ✓）
        detectUiMode()
        // 告诉保活服务"我的界面已经在某块屏上前台了"，避免它重复拉起造成界面跳动
        // （只有副屏界面登记，跳板不登记，见 tracksPresence 的说明）
        // ⚠ 第十五轮：主屏大屏模式不登记 presence（那是副屏专用机制 ✗）
        if (!LauncherUiMode.isMainScreen && tracksPresence()) {
            CoverPresence.onResumed(displayIdState.intValue)
        }
        CoverDebugLog.log(
            LOG_TAG,
            "onResume class=${javaClass.simpleName} displayId=${displayIdState.intValue} " +
                "tracks=${tracksPresence()} presence=${CoverPresence.debugState()}"
        )

        // 锁屏还锁着就再请求一次解除：onCreate 里那一次可能因为 Activity 还没真正
        // 到前台而无效（`requestDismissKeyguard` 在非前台 Activity 上是 no-op）。
        // 入口跳板不参与（它把锁屏验证交给系统的快捷启动流程）。
        if (!isEntryTrampoline()) retryDismissKeyguardIfLocked()
        ShizukuShell.refresh(applicationContext)
        // 通知权限可能是刚刚才被打开的：补发一次方向开关通知，
        // 避免"启动时发过一次、因权限未开被丢弃，之后再也没出现"
        if (CoverSettings.isOrientationNotifEnabled(this)) {
            OrientationNotification.show(this)
        }
        // ★ 任务书第 9 项：前台期间周期性重申"隐藏状态栏"（vivo 手势会把状态栏顶出来常驻）
        startImmersiveReassert()
    }

    override fun onPause() {
        // 不在前台就别再重申系统栏了，免得跟别的应用抢
        stopImmersiveReassert()
        // ★ 第七轮：我们的界面**整体**不在前台了 → 判据置 false（于是"非运动相机界面不使用
        //   悬浮窗"才会生效：别在别的应用/裸桌面上盖悬浮窗）。
        //   ⚠ **悬浮窗还画着我们的界面时不能算"离开"**：悬浮窗模式下我们的界面正是画在 2038 里，
        //     Activity 常常是 paused 状态（底下是 vivo 桌面）—— 那一刻若置 false，
        //     保活就会把用户正在看的界面收掉，再被"回到桌面自动拉起"拉回来 ⇒ 死循环锁死 ✗✗。
        if (!CoverOverlayState.overlaying) {
            CoverScreenGate.currentScreenIsCarousel = false
        }
        // 计数式清除：不带 displayId，任何一次 pause 都会减一，绝不会漏清
        if (tracksPresence()) CoverPresence.onPaused()
        CoverDebugLog.log(
            LOG_TAG,
            "onPause class=${javaClass.simpleName} tracks=${tracksPresence()} presence=${CoverPresence.debugState()}"
        )
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manifest 里声明了 configChanges，系统不会重建 Activity —— 手动同步
        refreshDisplayId()
        applyImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 从相机 / 相册 / 其他应用返回后，系统栏可能被重新显示，这里再收一次
        if (hasFocus) applyImmersiveMode()
    }

    /**
     * 主界面静音时**吃掉音量键**。
     *
     * 为什么在这里拦有效：我们的悬浮窗是 `FLAG_NOT_FOCUSABLE`，副屏上的按键焦点
     * 一直在**底下的 Activity** 上，所以音量键会先送到这里 —— 返回 true 消费掉，
     * 系统就不会弹出音量条了。离开主界面（[VolumeGuard.active] 为假）就放行。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (VolumeGuard.active &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            // 顺手再压一次 0，防止有人先按了别的键把音量抬起来
            VolumeGuard.silenceNow(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        // 悬浮窗模式：把本 Activity 的结果注册表借给悬浮窗界面
        // （相册权限申请、小组件系统授权、通知权限都要用它；借不到时悬浮窗侧退化为空实现，不会崩）
        runCatching { OverlayResultHost.registry = activityResultRegistry }
    }

    override fun onStop() {
        super.onStop()
        // ★ 第七轮：整个界面都不在前台了（且悬浮窗也没在画我们的界面）→ 判据置 false。
        //   与 onPause 同条件，双保险；悬浮窗还显示着我们的界面时**绝不**置 false
        //   （否则就是把用户正在看的界面收掉 → 收起/拉起死循环）。
        if (!CoverOverlayState.overlaying) {
            CoverScreenGate.currentScreenIsCarousel = false
        }
        // 折叠 / 息屏 / 切到别的应用都会走到这里。作为副屏桌面，"折叠即 stop"很常见，
        // 那时灭灯往往不是用户想要的 —— 因此是否关灯交给设置决定。
        if (CoverSettings.isTorchOffOnLeave(this)) {
            flashlight.turnOff()
        }
        runCatching {
            if (OverlayResultHost.registry === activityResultRegistry) {
                OverlayResultHost.registry = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopImmersiveReassert()
        displayListener?.let { listener ->
            runCatching {
                (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.unregisterDisplayListener(listener)
            }
        }
        flashlight.release()
    }

    companion object {
        private const val TAG = "LauncherActivity"

        /** 落盘日志用的统一 tag */
        const val LOG_TAG = "Cover"

        /** 投放后验证"是否真的起来了"的次数与间隔（startActivity 不抛异常 ≠ 成功） */
        private const val VERIFY_RETRIES = 6
        private const val VERIFY_STEP_MS = 250L

        /** 「隐藏状态栏」周期性重申的间隔（任务书第 9 项；只在副屏界面前台时跑） */
        private const val IMMERSIVE_REASSERT_MS = 1_000L
    }
}

// ======================================================================
// 两个具体 Activity
// ======================================================================

/**
 * 应用入口（应用抽屉 / 快捷启动 / 其他应用跳转都走这里）。
 *
 * 用**默认 taskAffinity**。它自己的职责只有一个：
 * 如果自己不在副屏上，就把 [CoverHomeActivity] 投到副屏，然后收掉自己。
 * —— 这样副屏上出现的是带独立 affinity 的 [CoverHomeActivity]，
 * 会形成独立任务栈并被正常推到前台。
 */
open class MainActivity : BaseLauncherActivity() {
    override fun shouldFinishAfterPlacement(): Boolean = true
    override fun isEntryTrampoline(): Boolean = true
    // 注意：故意**不**关闭 shouldShowUi()。
    // 万一投放失败（比如没有副屏、Shizuku 不可用），入口 Activity 仍然要有界面兜底，
    // 否则会停在黑屏。
}

/**
 * 副屏界面（manifest 里 `taskAffinity="cn.pashiguoke.subscreen.cover"`，
 * 并注册 `SECONDARY_HOME`）。
 *
 * 独立 affinity 的作用：`NEW_TASK` + `setLaunchDisplayId` 投放时，
 * 系统在副屏上为它**新建一个任务栈**，而不是去复用主屏上那个后台任务。
 * 这正是《超级小副屏》用 `SubActivity + taskAffinity=cn.pashiguoke.subscreen.cover`
 * 做到的稳定启动。
 */
open class CoverHomeActivity : BaseLauncherActivity() {
    override fun tracksPresence(): Boolean = true

    /**
     * ★ 运动相机主界面「状态栏永久隐藏」（任务书第 9 项）。
     *
     * 现象：悬浮窗模式下用 vivo 的"切后台 / 回桌面"手势时，顶部状态栏会**常驻显示**，
     * 要再手势一次才隐藏 —— 根因是原来只在 onCreate / onConfigurationChanged /
     * onWindowFocusChanged 各收一次，之后没人再管。
     *
     * 这里在基类（隐藏整块 systemBars）之上，再对**本 window**显式隐藏 statusBars，
     * 并把行为设成"滑动只临时显示、随后自动隐藏"；再配合基类里的每秒重申循环
     * （[BaseLauncherActivity.immersiveReassert]），就做到了常驻隐藏。
     *
     * ⚠ **顺序有讲究**：先做本类的现代 API 隐藏 statusBars，最后才调 `super`。
     *   因为基类末尾会落 **legacy** `systemUiVisibility` 标志 —— 而真机实测证明
     *   这台机器的副屏上只有 legacy 那条路径真正生效（现代 `hide()` 调用成功但无效），
     *   所以必须让 legacy 成为**最后一次写入**，避免被现代 API 覆盖掉。
     *
     * ⚠ 悬浮窗（2038）那条路由 `CoverOverlayWindow` 自己处理（已由 recorder-statusbar 补上
     *   legacy 标志）。语义上基类本来就是**全局隐藏**，这里不按界面区分。
     */
    override fun applyImmersiveMode() {
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.statusBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        // 最后落 legacy 标志（现代 API 只在这台机器上"看起来成功"）
        super.applyImmersiveMode()
    }

    /**
     * 按键 / 触摸都算"用户还在操作" → 重置自动息屏计时。
     * 配合 [com.dsh.sportscam.cover.CoverScreenTimeout] 的看门狗：
     * 悬浮窗模式下系统不判空闲，屏幕不会自己灭，需要我们自己数时间。
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        com.dsh.sportscam.cover.CoverScreenTimeout.markInteraction()
    }
}

// ======================================================================
// Compose 层：单 Activity + 简单状态导航
// ======================================================================

/**
 * 界面栈中的一页。
 *
 * ⚠ 第十轮起是 **`internal`（不再是 `private`）**：desktop-ui 的桌面要能引用
 *   `Screen.Folder(id)`（文件夹入口由它发事件）—— 同模块内可见即可 ✓。
 *   `push/pop` 仍然只在 `SportsCamApp` 内部，外部只能通过各界面的回调进来（既有约定 ✓）。
 */
internal sealed interface Screen {
    data object Carousel : Screen
    data object Gallery : Screen
    data object Videos : Screen
    data object AppList : Screen

    /** SquareHome 式桌面（隐藏入口：相簿右上角「xx 项」连点三次） */
    data object Desktop : Screen
    data object Console : Screen
    data object DisplaySettings : Screen

    /** 最近删除管理（**入口只在控制台**） */
    data object Trash : Screen

    /**
     * 「截屏与录屏」设置（第二轮 B3）。
     *
     * 入口：控制台 ② 辅助与测试功能 → 「截屏与录屏」；界面本体是
     * `ui/ScreenshotRecordScreen.kt`（① 页原来的「通知栏截屏按钮」已迁进去）。
     */
    data object ScreenshotRecord : Screen

    /**
     * 「私密应用」界面（第七轮）。
     *
     * 入口：桌面上的**锁形图标**（[com.dsh.sportscam.desktop.DesktopItem.PrivateAppsEntry]，由
     * `SquareHomeScreen(onOpenPrivateApps=…)` 发出）+ 控制台 ④ 隐私与安全里的选择/管理。
     * 界面本体是 `ui/PrivateAppsScreen.kt`（list-viewer）。
     * ⚠ 密码门在**界面内部**（`PrivateAccessGate(onExit = onBack)`）：每次进入必弹一次、
     *   没设过密码只给引导不放行 ✓。所以**入口这里刻意不再弹**（否则连输两遍 ✗）。
     */
    data object PrivateApps : Screen

    /**
     * 「桌面壁纸编辑器」（第十轮第 4 项）。
     *
     * 入口：控制台 ① 界面与个性化 →「桌面壁纸」。
     * 界面本体 `ui/WallpaperEditorScreen.kt`（recorder-statusbar 新建，签名约定
     * `@Composable fun WallpaperEditorScreen(onBack: () -> Unit)`）。
     */
    data object Wallpaper : Screen

    /**
     * 「桌面文件夹」（第十轮第 5 项）。
     *
     * 入口：桌面上**文件夹图标**点击（`SquareHomeScreen` 由 desktop-ui 发事件；
     * 接线方式：`SquareHomeScreen(onOpenFolder = { id -> push(Screen.Folder(id)) })`）。
     * 界面本体 `ui/FolderScreen.kt`（list-viewer），签名 `FolderScreen(folderId, onBack)`。
     *
     * ⚠ 必须是 **data class**（带 `id`）—— 同一个界面承载不同文件夹，`data class` 的
     *   值相等性让 Compose 能正确识别"是否换了个文件夹"（`data object` 做不到 ✗）。
     */
    data class Folder(val id: Long) : Screen

    /**
     * 「制作者与开源地址」（第十四轮）。
     *
     * 入口：控制台母界面**最下方**「关于 → 制作者与开源地址」。
     * 界面本体在 `ui/ConsoleScreen.kt` 里（`AboutScreen`：作者 / 鸣谢 / GitHub / 免责提示）。
     */
    data object About : Screen

    /**
     * 「免责声明与用户守则」（第十四轮）。
     *
     * 入口：[About] 界面最下方的「免责声明与用户守则」。
     * 界面本体在 `ui/ConsoleScreen.kt` 里（`DisclaimerScreen`，正文常量 `DISCLAIMER_TEXT`）。
     */
    data object Disclaimer : Screen

    data class Viewer(val items: List<MediaItem>, val index: Int, val isVideo: Boolean) : Screen
}

/**
 * 第十五轮：本进程当前跑在**哪块屏**上，以及大屏模式要用的放大系数。
 *
 * 用户需求（原话）："给大屏写一个大屏专用界面，试图在大屏启动时大屏界面显示应用控制台"。
 *  —— 他用电脑投屏看手机**主屏**，希望主屏打开本应用时直接看到「控制台」，
 *     而不是副屏那套启动器（方便在大屏上配置）。
 *
 * 判定规则（[BaseLauncherActivity.detectUiMode] 里算）：
 *  · 自己所在 display == `CoverDisplay.coverDisplayId()` → **副屏**：一切照旧 ✓；
 *  · 否则（含"设备根本没有副屏"）→ **主屏大屏模式**：初始页 = 控制台 + 界面放大 ✓。
 *
 * ⚠ 副屏行为必须 100% 不变 —— 所以这个模式只影响三个地方：
 *   初始导航页、[Density] 放大系数、以及"自投放/保活"这类副屏专属动作是否跳过。
 */
internal object LauncherUiMode {
    /** 当前 Activity 是否跑在**主屏**（大屏模式） */
    @Volatile
    var isMainScreen: Boolean = false

    /** 大屏模式下的布局放大系数（1.3~1.6 之间；副屏模式恒为 1f） */
    @Volatile
    var densityScale: Float = 1f

    /** 是否已经检测过一次（用于只在首次/变化时打日志） */
    @Volatile
    var detected: Boolean = false

    /** 主屏模式放大系数（Lead 要求 1.3~1.6；1080×2400 投到电脑大屏 1.4 实测适中） */
    const val MAIN_SCREEN_DENSITY_SCALE: Float = 1.4f
}

/**
 * 启动器「导航栈」的**进程级副本**。
 *
 * 为什么要它：悬浮窗（[com.dsh.sportscam.cover.CoverOverlayWindow]）与 Activity 是
 * 两棵**独立**的 Compose 树，且悬浮窗在"让位 → 重挂"（每次打开应用都会发生）时
 * 会被整体重建。导航栈若只存在树内（remember），重建后就回到轮播 ——
 * 表现为"回到启动器时自动回主界面"，且**关掉『退出应用回到主界面』也没用**（用户实测）。
 */
/**
 * 启动器「导航栈」——**两棵 Compose 树共享的同一个状态对象**。
 *
 * 为什么必须是"共享状态"而不是"各自 remember + 进程级副本"：
 *   悬浮窗（[com.dsh.sportscam.cover.CoverOverlayWindow]）与 Activity 是两棵**独立**的
 *   Compose 树，且：
 *     · 悬浮窗显示时，Activity 那棵树会在很靠前的位置**提前 return**（不渲染，
 *       以免同一个界面被画两遍），于是它内部的 `remember` 值是很早之前算出来的一次性值；
 *     · 等悬浮窗被移除、Activity 重新接手渲染时，它用的就是这个**陈旧值**（= 轮播）→
 *       表现为"关掉『退出应用后回到主界面』也没用，还是会回到主界面"（用户实测反馈）。
 *   把状态做成**同一个 `MutableState`** 后，两棵树读写同一份数据，谁在渲染都一致。
 *
 * 注意：这是进程级（object）状态，进程被杀才会重置 —— 这符合"退出应用后回到主界面"
 * 由设置项控制、而不是由界面重建控制的预期。
 */
private object LauncherNavState {
    /**
     * 共享导航栈（初始 = 主界面轮播）。
     *
     * ⚠ 真正的初始页由 [ensureInitialized] 在**首次组合时**按「运动相机模式」定：
     *   开 → [Screen.Carousel]（运动相机主界面）；关 → [Screen.Desktop]（桌面）。
     *   这里先给一个安全默认值，保证任何提前读取 `stack` 的地方都不会崩。
     */
    val stack = androidx.compose.runtime.mutableStateOf<List<Screen>>(listOf(Screen.Carousel))

    /** 是否已按设置初始化过（进程内只做一次） */
    private var initialized = false

    /**
     * 首次进入时决定**启动页**（任务书第 1 项）。
     *
     * 为什么必须延迟到运行时：初值依赖 SharedPreferences（要 Context），
     * 而这里是 object 的字段初始化，拿不到 Context。
     *
     * ⚠ 两棵 Compose 树（Activity / 悬浮窗）读的是**同一个** stack，
     *   所以谁先组合谁初始化；`initialized` 保证只写一次，语义不会被谁先谁后改变。
     */
    fun ensureInitialized(cameraModeEnabled: Boolean) {
        if (initialized) return
        initialized = true
        stack.value = listOf(initialScreen(cameraModeEnabled))
    }

    /**
     * 初始页（第十五轮）：
     *  · **主屏大屏模式** → [Screen.Console]（用户要求：大屏上直接看控制台 ✓）；
     *  · **副屏** → 按「运动相机模式」决定（开 → 主界面；关 → 桌面），与以前完全一致 ✓。
     */
    fun initialScreen(cameraModeEnabled: Boolean): Screen =
        if (LauncherUiMode.isMainScreen) Screen.Console else homeScreen(cameraModeEnabled)

    /** "回到主页"该回哪一页：运动相机模式开 → 主界面；关 → 桌面 */
    fun homeScreen(cameraModeEnabled: Boolean): Screen =
        if (cameraModeEnabled) Screen.Carousel else Screen.Desktop
}

/**
 * 供控制台调用：切换「运动相机模式」时**立刻同步共享导航栈的底页**（任务书第 1 项）。
 *
 * 为什么需要：初始页只在"首次组合 / 重新进入"时按设置决定（见 [LauncherNavState.ensureInitialized]）。
 * 用户在一次会话中途于控制台关掉模式后，导航栈底部可能还停在运动相机主界面 ——
 * 他一路返回就会落回主界面，与"关闭后进桌面"不一致。
 *
 * ⚠ 这里**只换底页**（`stack[0]`），保留用户当前停留的界面：控制台不会被踢掉、
 *   消息也还看得见；返回到底时才是桌面。写的是同一个共享状态对象，两棵树语义不变。
 */
internal fun syncNavStackHomeToCameraMode(cameraModeEnabled: Boolean) {
    // 第十五轮：主屏大屏模式下底页恒为「控制台」，不参与"桌面/主界面"切换 ✓
    if (LauncherUiMode.isMainScreen) return
    val target = LauncherNavState.homeScreen(cameraModeEnabled)
    val current = LauncherNavState.stack.value
    if (current.isEmpty()) {
        LauncherNavState.stack.value = listOf(target)
        return
    }
    if (current.first() == target) return
    LauncherNavState.stack.value = listOf(target) + current.drop(1)
}

@Composable
internal fun SportsCamApp(
    flashlightOn: Boolean,
    flashlightAvailable: Boolean,
    displayId: Int,
    onToggleTorch: () -> Unit,
    /** 每次"重新进入"自增；收到新值时（若开启了"回到主界面"）把导航栈重置回主界面 */
    reentryTick: Int = 0,
    /** 设置项：退出应用后是否回到主界面（关掉则保留上次界面） */
    returnToHomeOnReentry: Boolean = true,
    /** 本界面是否正跑在悬浮窗里（悬浮窗宿主用 true；此时不能因为"悬浮窗在显示"而停渲染） */
    isOverlayHost: Boolean = false,
    /** 悬浮窗模式下额外要收起自己的钩子（见 [com.dsh.sportscam.cover.CoverOverlayWindow]） */
    onRequestHideOverlay: (() -> Unit)? = null
) {
    // ★ 悬浮窗正在显示时，普通 Activity 的这个界面**停止渲染**。
    //   否则同一个启动器会被渲染两遍（Activity 一遍 + 悬浮窗一遍），副屏上明显掉帧。
    //   悬浮窗宿主自己（isOverlayHost=true）不走这个分支。
    if (!isOverlayHost && CoverOverlayState.overlaying) {
        Box(Modifier.fillMaxSize().background(SportsCamBlack))
        return
    }

    // 固定 fontScale：保证 682x422 的紧凑布局不被系统字体放大破坏
    // ★ 第十五轮：主屏大屏模式把 density 整体放大（1.4×）—— 字和按钮一起变大，投到大屏上好点 ✓
    val baseDensity = LocalDensity.current
    val mainScreenScale = if (LauncherUiMode.isMainScreen) LauncherUiMode.densityScale else 1f
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = baseDensity.density * mainScreenScale,
            fontScale = 1f
        )
    ) {
        SportsCamTheme {
            val context = LocalContext.current
            // 风格（强调色）：每次组合同步一次（很便宜），控制台改完立即可见
            com.dsh.sportscam.ui.theme.Accent.sync(context)
            // ★★ 导航栈 = **两棵树共享的同一个状态**（见 LauncherNavState 注释）。
            //   用属性委托接上后，下面所有 `stack` 的读/写都直接作用于共享状态，
            //   于是"悬浮窗重建 / 让位后 Activity 重新接手渲染"都不会再丢界面。
            var stack by LauncherNavState.stack

            // ★ 启动页按「运动相机模式」定（任务书第 1 项）：
            //   开 → 运动相机主界面；关 → 桌面（退回普通启动器）。
            //   必须在下面任何 `stack` 读取之前完成；idempotent，重复组合无副作用。
            LauncherNavState.ensureInitialized(CoverSettings.isCameraModeEnabled(context))

            // 重新进入（singleTask 复用实例 → onNewIntent/onRestart）时回到主界面。
            // 由「退出应用后是否回到主界面」这个设置决定：关掉则保留上次停留的界面。
            // ★ "主界面"是哪一页同样由运动相机模式决定（关 = 桌面，见任务书第 1 项）。
            LaunchedEffect(reentryTick, returnToHomeOnReentry) {
                if (reentryTick > 0 && returnToHomeOnReentry) {
                    // 第十五轮：主屏大屏模式"回家" = 控制台（initialScreen 内部已按模式分流 ✓）
                    stack = listOf(LauncherNavState.initialScreen(CoverSettings.isCameraModeEnabled(context)))
                }
            }
            val current = stack.last()

            // ★★ 【第七 ~ 九轮：这个闸值的历史（读完再改，别重犯）】★★
            //
            //  ✅ **当前状态（第九轮定稿）**：`when` 把**我们自己导航栈里的每一页**都判为 true，
            //     并且 `CoverSettings.overlayAllowedOnCurrentScreen()` 已按用户要求**恒返回 true**
            //     ⇒ 这一位**对行为没有任何影响**了，保留它只是为了不动这段代码 / 不做无谓 churn。
            //     那个"非运动相机界面不使用悬浮窗"的**设置项已删除**（控制台 ① 页不再有它）。
            //
            //  ❌ 历史事故（真机日志实证 10 次循环）：
            //     · 悬浮窗模式下，应用列表/控制台这些界面**本身就是画在 2038 悬浮窗里的**
            //       （悬浮窗是它们的宿主）⇒ 收起它 = 把用户正在看的页面**自己**关掉；
            //     · 副屏随之露出 vivo 桌面（用户看到"闪一下桌面"），而 onResume 又把闸值置回 true
            //       ⇒ 保活立刻重挂 ⇒ `hide → 挂出 → show` 死循环，用户被弹回运动相机主界面 ✗✗。
            //     · 结论：**只要还会 hide，就一定会被 show 弹回** ⇒ 这条需求在现有架构下做不到，
            //       所以第九轮按用户要求**删掉设置项**，而不是继续加宽限期/白名单。
            //
            //  ⚠ 出口（第九轮第 3 项，用户最终决定）：**不再提供任何逃生通道**。
            //     两处通知退出口（方向通知的退出药丸 / 保活通知的退出 action）已按用户要求删除，
            //     视频页右滑方案也已撤回；关闭「悬浮窗防退出模式」的唯一正常路径是
            //     **控制台 ③ 页那个开关**（页面内开关，不算逃生口）。
            //
            //  ⚠ 这个 `when` 是**穷尽**的表达式：以后新增 Screen，编译器会强制你在这里明确
            //    "它算不算运动相机界面" —— 这正是我们要的保护，**不要加 `else`** ✓。
            //  ⚠ "我们的界面整体不在前台"由 Activity 的 onResume/onPause(+onStop) 负责置值，
            //    且**悬浮窗还画着我们的界面时绝不置 false**（否则又会回到锁死）。
            SideEffect {
                CoverScreenGate.currentScreenIsCarousel = when (current) {
                    // ★★ 第八轮·二次修正（用户实测**又**被锁死后，有 10 次循环的日志实证）★★
                    //   结论：**我们自己的任何页面都必须算"运动相机界面"（true）** ✗→✓
                    //   循环链（cover_debug.log 原始记录，重复 10 次）：
                    //     02:03:55.030 [Overlay] hide: 已移除                      ← 按页面闸收起
                    //     02:03:55.746 [Cover]  onResume class=CoverHomeActivity   ← 我们成前台
                    //     02:03:56.806 [Keep]   overlay: 挂出（桌面在前台）…
                    //     02:03:56.835 [Overlay] show: 悬浮窗已添加 display=1       ← 又弹回来
                    //     ⇒ onNewIntent → reentry 把导航栈重置回主界面 ⇒ 用户卡在运动相机界面 ✗✗
                    //   根因：**悬浮窗本身就是这些页面的宿主** ✗ —— 收起它 = 把用户正在看的页面
                    //   自己关掉；副屏随之露出 vivo 桌面（用户看到"闪一下桌面"），
                    //   而 onResume 又把闸值置回 true ⇒ 保活立刻重挂 ⇒ 死循环。
                    //   ⇒ 因此"非运动相机界面不使用悬浮窗"**不能**用来收起我们自己的页面，
                    //     它只能表达"别长时间盖在**别的应用**上"（那种情形由保活的前台判定
                    //     frontOk + 去抖负责，与这里无关）。
                    is Screen.Carousel,
                    is Screen.Desktop,
                    is Screen.AppList,
                    is Screen.Console,
                    is Screen.Gallery,
                    is Screen.Videos,
                    is Screen.Viewer,
                    is Screen.Trash,
                    is Screen.DisplaySettings,
                    is Screen.ScreenshotRecord,
                    is Screen.PrivateApps,
                    // 第十轮：壁纸编辑器 / 桌面文件夹 也是我们自己的界面
                    is Screen.Wallpaper,
                    is Screen.Folder,
                    // 第十四轮：制作者与开源地址 / 免责声明 也是我们自己的界面
                    is Screen.About,
                    is Screen.Disclaimer -> true
                }
            }

            // 主界面静音：只在「运动相机」主界面生效，切到相册/桌面/控制台/查看界面就恢复。
            // ★ 除了切屏时压一次，还必须**持续**压 —— 只靠"音量变化广播"不够：
            //   后台播放音乐时音量可能本来就不是 0，且没有任何"变化事件"可监听，
            //   结果就是回到主界面后不静音、要按一下音量键才生效（用户实测反馈）。
            LaunchedEffect(current) {
                val mute = CoverSettings.isMuteOnCarousel(context) && current is Screen.Carousel
                VolumeGuard.setActive(context, mute)
                while (mute) {
                    kotlinx.coroutines.delay(500)
                    VolumeGuard.silenceNow(context)
                }
            }

            // 副屏 id：运行时枚举 + 随热插拔/旋转更新（不再硬编码 1）
            val coverDisplayId by rememberCoverDisplayId()

            // 大图 / 视频查看是「覆盖层」：它下面的列表界面保持在场、不被销毁，
            // 这样关掉大图返回时，相簿的滚动位置与已加载数据都不会丢。
            val baseScreen: Screen = if (current is Screen.Viewer && stack.size >= 2) {
                stack[stack.size - 2]
            } else {
                current
            }

            val push: (Screen) -> Unit = { stack = stack + it }
            val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }

            // ★ 启动器密码（四位数字，默认不启用）——第二轮 B1：**按用途**判断。
            //   三个入口各查自己的开关（PasswordGate.Purpose.Console / AppList / DesktopEdit）；
            //   总开关关掉时三者全不拦。拦截点在这里的两个 push，桌面"进入编辑模式"
            //   的拦截在 SquareHomeScreen（由 desktop-ui 用同一个 API 接线）。
            //   没启用 / 该用途没勾保护时 guard 直接执行动作（零影响）；浮层宿主在最外层 Box 里。
            val passwordGate = rememberPasswordGate()
            val guardedPush: (Screen, PasswordGate.Purpose, String) -> Unit =
                { screen, purpose, title ->
                    passwordGate.guard(context, purpose, title) { push(screen) }
                }

            // ⚠ 第七轮：**私密应用**的密码门不在这里 —— 它在界面本体
            //   `ui/PrivateAppsScreen.kt` 内部的 `PrivateAccessGate(onExit = onBack)` 里
            //   （每次进入必弹、没设密码只引导不放行 ✓）。这里若再拦一次，用户会连输两遍 ✗。

            // 「相簿 #2」双击清理后台是挂起操作（Shizuku 要 IO）
            val cleanScope = rememberCoroutineScope()

            // 相簿 / 视频列表的状态（数据 + 滚动位置）提到这一层持有，
            // 界面重建也不会丢：关掉大图返回后仍停在上一个位置。
            val galleryUiState = remember { MediaBrowseUiState() }
            val videosUiState = remember { MediaBrowseUiState() }
            // 应用列表同理：扫描 + 图标解码很慢，缓存住才能"下次打开秒开"
            val appListUiState = remember { AppListUiState() }

            // 只有主界面拦截返回键；Home 场景下栈内只有一页时不处理
            BackHandler(enabled = stack.size > 1) { pop() }

            // 主界面屏蔽左右边缘返回手势；相册/应用列表等全屏界面保留返回手势
            EdgeGestureExclusion(enabled = current is Screen.Carousel)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SportsCamBlack)
            ) {
                Crossfade(targetState = baseScreen, label = "screen") { screen ->
                    when (screen) {
                        is Screen.Carousel -> CarouselScreen(
                            flashlightOn = flashlightOn,
                            flashlightAvailable = flashlightAvailable,
                            onExecute = { index ->
                                when (index) {
                                    // 拍摄
                                    LauncherItems.CAMERA_INDEX -> {
                                        val chosen = CoverSettings.cameraPackage(context)
                                        if (chosen.isNotEmpty()) {
                                            // 用户指定了相机应用 → 直接把它拉到副屏（含让位逻辑）
                                            CoverOverlayState.suppress()
                                            cleanScope.launch {
                                                val r = com.dsh.sportscam.cover.CoverAppLauncher
                                                    .launchPackageToCover(
                                                        context,
                                                        chosen,
                                                        displayId,
                                                        preferShizuku = CoverSettings
                                                            .isPreferShizuku(context, chosen)
                                                    )
                                                if (r !is com.dsh.sportscam.cover.CoverAppLauncher.Result.Success) {
                                                    showToast(context, "打不开所选相机应用")
                                                }
                                            }
                                        } else {
                                            // 常驻悬浮窗会盖住一切：先让它让位，再启动相机
                                            CoverOverlayState.suppress()
                                            val ok = IntentLauncher.openCamera(context, displayId)
                                            if (!ok) showToast(context, "未找到可用的相机应用（Open Camera 未安装？）")
                                        }
                                    }
                                    // 相簿 #1
                                    LauncherItems.ALBUM1_INDEX -> push(Screen.Gallery)
                                    // 相簿 #2 / #3
                                    // 相簿#2 的单击已经由轮播层延后 400ms（要区分双击清后台），
                                    // 这里就直接弹，避免叠加成 800ms。
                                    LauncherItems.ALBUM2_INDEX -> showToast(context, "暂无照片")
                                    LauncherItems.ALBUM3_INDEX -> showToastDelayed(context, "暂无照片")
                                    // 视频 #1
                                    LauncherItems.VIDEO1_INDEX -> push(Screen.Videos)
                                    // 视频 #2 / #3
                                    LauncherItems.VIDEO2_INDEX,
                                    LauncherItems.VIDEO3_INDEX -> showToastDelayed(context, "暂无视频")
                                    // 手电筒
                                    LauncherItems.TORCH_INDEX -> onToggleTorch()
                                }
                            },
                            // 隐藏入口：相簿 #3 居中时点右下角热区三下 → 应用列表
                            // ★ 密码启用时：进总应用列表前要先输密码（任务书第 4 项）
                            onAlbum3TripleTap = {
                                guardedPush(
                                    Screen.AppList,
                                    PasswordGate.Purpose.AppList,
                                    "输入密码打开应用列表"
                                )
                            },
                            // 相簿 #2 双击 → 清理后台进程（不提示，也绝不清理自己）
                            onAlbum2DoubleTap = {
                                cleanScope.launch {
                                    val n = com.dsh.sportscam.cover.CoverBackgroundCleaner.clear(context)
                                    CoverDebugLog.log("Cover", "双击相簿2清理后台：结果=$n")
                                }
                            },
                            // 长按「拍摄」→ 启动「第二层应用」（控制台可开关 + 单选应用）
                            onLongPressCamera = {
                                if (!CoverSettings.isCameraLongPressEnabled(context)) {
                                    showToast(context, "长按拍摄未启用（控制台·界面与个性化）")
                                } else {
                                    val pkg = CoverSettings.cameraLongPressPackage(context)
                                    if (pkg.isEmpty()) {
                                        showToast(context, "未选择长按启动的应用")
                                    } else {
                                        // 常驻悬浮窗会盖住一切：先让它让位，再启动目标应用
                                        CoverOverlayState.suppress()
                                        cleanScope.launch {
                                            val r = com.dsh.sportscam.cover.CoverAppLauncher.launchPackageToCover(
                                                context = context,
                                                packageName = pkg,
                                                coverDisplayId = displayId,
                                                preferShizuku = CoverSettings.isPreferShizuku(context, pkg)
                                            )
                                            if (r !is com.dsh.sportscam.cover.CoverAppLauncher.Result.Success) {
                                                showToast(context, "长按启动失败（$pkg）")
                                            }
                                        }
                                    }
                                }
                            }
                        )

                        is Screen.Gallery -> MediaBrowseScreen(
                            titlePrefix = "相簿 1 · ",
                            titleSuffix = "DCIM/Camera",
                            isVideo = false,
                            // targetSdk 29 → 主路径是 READ_EXTERNAL_STORAGE；
                            // 同时申请 Android 13 的新媒体权限，哪条生效用哪条
                            permissions = listOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.READ_MEDIA_IMAGES,
                                // 删除（移入"最近删除"）需要写权限；API 30+ 上该权限不再返回/申请，
                                // hasPermissions 对未声明有效的权限会直接视为已授予，见其实现
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ),
                            uiState = galleryUiState,
                            onBack = pop,
                            onOpenItem = { list, index -> push(Screen.Viewer(list, index, isVideo = false)) },
                            // 隐藏入口：连点右上角「xx 项」三次 → SquareHome 式桌面
                            onHiddenEntry = { push(Screen.Desktop) }
                        )

                        is Screen.Videos -> MediaBrowseScreen(
                            titlePrefix = "视频 1",
                            isVideo = true,
                            permissions = listOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.READ_MEDIA_VIDEO,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ),
                            uiState = videosUiState,
                            onBack = pop,
                            onOpenItem = { list, index -> push(Screen.Viewer(list, index, isVideo = true)) }
                        )

                        is Screen.AppList -> AppListScreen(
                            coverDisplayId = coverDisplayId,
                            uiState = appListUiState,
                            onBack = pop,
                            // ★ 密码启用时：进控制台前要先输密码（任务书第 4 项）
                            onOpenConsole = {
                                guardedPush(
                                    Screen.Console,
                                    PasswordGate.Purpose.Console,
                                    "输入密码进入控制台"
                                )
                            }
                        )

                        // SquareHome 式桌面：独立于总应用列表
                        is Screen.Desktop -> SquareHomeScreen(
                            appListUiState = appListUiState,
                            onBack = pop,
                            // ★ 密码保护「总应用列表」开启时：打开前要先输密码
                            onRequestAddApps = {
                                guardedPush(
                                    Screen.AppList,
                                    PasswordGate.Purpose.AppList,
                                    "输入密码打开应用列表"
                                )
                            },
                            // ★ 桌面上拉进应用列表（desktop-ui 新增）**显式**走同一个包装入口：
                            //   SquareHomeScreen 里该参数的默认值就是 onRequestAddApps（已带校验），
                            //   这里仍显式传一次，免得对面以后改默认值就静默绕过密码。
                            onOpenAppList = {
                                guardedPush(
                                    Screen.AppList,
                                    PasswordGate.Purpose.AppList,
                                    "输入密码打开应用列表"
                                )
                            },
                            // ★★ 第七轮：桌面上的**锁形图标**（DesktopItem.PrivateAppsEntry）→
                            //   私密应用界面。**这里刻意不弹密码** —— 界面本体
                            //   （`PrivateAppsScreen`）内部已经套了 `PrivateAccessGate(onExit=onBack)`：
                            //   每次进入必弹一次、没设密码只引导不放行 ✓。
                            //   ⚠ 若这里再弹一次，用户会**连着输两遍** ✗
                            //     （要改成入口弹就必须给界面传 skipAccessGate = true，二选一）。
                            onOpenPrivateApps = { push(Screen.PrivateApps) },
                            // 第十轮第 5 项：桌面**文件夹图标**点击 → 文件夹界面
                            //   （界面本体 ui/FolderScreen.kt，list-viewer；desktop-ui 已暴露
                            //   `onOpenFolder(folderId)`，默认空实现 ⇒ 不接线也不会崩）
                            //   ⚠ 文件夹入口**不加密码门**（用户没要求；文件夹里含私密应用是另一件事，
                            //     由 list-viewer 自己处理 ✓）
                            onOpenFolder = { id -> push(Screen.Folder(id)) },
                            // ★ 第十轮：桌面 1×1 **垃圾桶项**点击 → 最近删除
                            //   ⚠ 必须与「最近删除」其它入口**同一处** guardedPush + Purpose.Trash ✓ ——
                            //     否则用户勾了「保护最近删除」时，桌面垃圾桶会绕过密码门 ✗
                            onOpenTrash = {
                                guardedPush(
                                    Screen.Trash,
                                    PasswordGate.Purpose.Trash,
                                    "输入密码进入最近删除"
                                )
                            }
                        )

                        is Screen.Console -> ConsoleScreen(
                            onBack = pop,
                            onOpenDisplaySettings = { push(Screen.DisplaySettings) },
                            // ★ 第十轮：最近删除 = 第 4 个密码保护范围。
                            //   ⚠ 「最近删除」一共**两个入口**：本处（控制台 ④ 页）+ 桌面 1×1 垃圾桶项，
                            //     两处都走同一个 guardedPush + Purpose.Trash ✓（勾了「保护最近删除」都拦得住）
                            onOpenTrash = {
                                guardedPush(
                                    Screen.Trash,
                                    PasswordGate.Purpose.Trash,
                                    "输入密码进入最近删除"
                                )
                            },
                            // 第二轮 B3：② 页「截屏与录屏」→ 新界面
                            onOpenScreenshotRecord = { push(Screen.ScreenshotRecord) },
                            // 第八轮：④ 页「私密应用入口」→ 直接进私密应用界面
                            // ⚠ 这里**不加**密码门：该界面内部已套 PrivateAccessGate，
                            //   两边都拦会让用户连输两遍 ✗
                            onOpenPrivateApps = { push(Screen.PrivateApps) },
                            // 第十轮第 4 项：① 页「桌面壁纸」→ 壁纸编辑器
                            onOpenWallpaper = { push(Screen.Wallpaper) },
                            // 第十四轮：母界面最下方「制作者与开源地址」→ Screen.About
                            onOpenAbout = { push(Screen.About) },
                            // 第十五轮：主屏大屏模式标识（副屏恒 false ⇒ 界面零变化 ✓）
                            mainScreenMode = LauncherUiMode.isMainScreen
                        )

                        is Screen.DisplaySettings -> DisplaySettingsScreen(onBack = pop)

                        is Screen.Trash -> TrashScreen(onBack = pop)

                        // 截屏与录屏设置（第二轮 B3；界面本体由 recorder-statusbar 新建）
                        is Screen.ScreenshotRecord -> ScreenshotRecordScreen(onBack = pop)

                        // 桌面壁纸编辑器（第十轮第 4 项；界面本体 ui/WallpaperEditorScreen.kt，
                        // recorder-statusbar 新建 —— 本分支已按约定签名接入，文件到位即可编译 ✓）
                        is Screen.Wallpaper -> WallpaperEditorScreen(onBack = pop)

                        // 桌面文件夹（第十轮第 5 项；界面本体 ui/FolderScreen.kt，list-viewer）
                        // 入口：桌面上点文件夹图标（desktop-ui 发事件 → 我这边 push）
                        is Screen.Folder -> FolderScreen(folderId = screen.id, onBack = pop)

                        // 制作者与开源地址（第十四轮；界面本体在 ui/ConsoleScreen.kt 里）
                        is Screen.About -> AboutScreen(
                            onOpenDisclaimer = { push(Screen.Disclaimer) },
                            onBack = pop
                        )

                        // 免责声明与用户守则（第十四轮；正文 = ConsoleScreen.kt 的 DISCLAIMER_TEXT）
                        is Screen.Disclaimer -> DisclaimerScreen(onBack = pop)

                        // 私密应用（第七轮；界面本体 ui/PrivateAppsScreen.kt，list-viewer）
                        // ⚠ 密码门在界面内部（PrivateAccessGate）：这里**只负责导航**，
                        //   入口不再拦，避免用户连输两遍密码 ✗
                        is Screen.PrivateApps -> PrivateAppsScreen(
                            coverDisplayId = coverDisplayId,
                            uiState = appListUiState,
                            onBack = pop,
                            onOpenConsole = {
                                guardedPush(
                                    Screen.Console,
                                    PasswordGate.Purpose.Console,
                                    "输入密码进入控制台"
                                )
                            }
                        )

                        // 查看界面永远作为覆盖层单独渲染（见下方），不会成为 base
                        is Screen.Viewer -> Unit
                    }
                }

                // 大图 / 视频查看：画在最上层，下层列表保持原样（滚动位置不丢）
                if (current is Screen.Viewer) {
                    MediaViewerScreen(
                        items = current.items,
                        startIndex = current.index,
                        isVideo = current.isVideo,
                        displayId = displayId,
                        onBack = pop,
                        // 删除成功后：关闭查看界面 + 让下面的列表重新查询（被删的那项立即消失）
                        onDeleted = {
                            pop()
                            if (current.isVideo) videosUiState.reload() else galleryUiState.reload()
                        },
                        // ★ 第二轮 C1 接线：旋转保存成功后，让下面的相册/视频列表**重扫**，
                        //   网格方向/缩略图立即刷新（不用退出到主界面再重进）。
                        //   同一个 reload()（loaded=false + reloadTick++）已被 MediaBrowseScreen
                        //   的 LaunchedEffect(..., uiState.reloadTick) 订阅，所以会立刻重查。
                        onMediaChanged = {
                            if (current.isVideo) videosUiState.reload() else galleryUiState.reload()
                        }
                    )
                }

                // 顶部下拉通知栏拦截条（仅主界面）
                if (current is Screen.Carousel) {
                    StatusBarSwipeBlocker(modifier = Modifier.align(Alignment.TopCenter))
                }

                // ★ 启动器密码浮层（同树浮层，画在所有内容之上）。
                //   ⚠ 只用同树浮层，绝不能用系统 Dialog —— 悬浮窗（2038）上下文里会崩进程。
                PasswordGateHost(passwordGate)
            }
        }
    }
}
