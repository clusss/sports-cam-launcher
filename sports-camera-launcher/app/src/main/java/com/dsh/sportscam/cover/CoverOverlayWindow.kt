package com.dsh.sportscam.cover

// ============================================================================
// ⚠️⚠️  悬浮窗模式（overlay mode）—— 已知问题较多，改动前必读  ⚠️⚠️
// ============================================================================
// 本文件实现"把启动器画成 TYPE_APPLICATION_OVERLAY(2038) 常驻悬浮窗"这一模式
// （控制台 → ③ 运动相机防查 → 「悬浮窗防退出模式」，**默认关闭**）。
//
// 该模式能防住手势退出，但已知问题明显多于普通模式，历次踩坑都只在它下面复现：
//   1) 点应用没反应 / 弹「翻开手机以继续」：vivo 拦截在副屏**直接**启动应用
//      （shell 的 `am start --display 副屏` 与应用内 setLaunchDisplayId 都会被拦，
//        且**任务根本不创建**）→ 必须"先在大屏启动，再 move-stack 投屏到小屏"；
//   2) 「拔数据线就失败」：某条修复路径依赖 Shizuku，而 Shizuku 会随 adb 断开停止
//      → 修复方案必须**不依赖 Shizuku** 才可靠；
//   3) 「开一次能行、过一会又不行」：vivo 的拦截与**悬浮窗窗口的"新鲜度"**有关，
//      窗口存活久了它发起的启动就被拦 → 必要时重建窗口（仅作失败后的重试手段）；
//   4) 「点应用后闪一下回到应用列表 / 回到主界面」：**启动前无条件重建悬浮窗**会把窗口
//      连同界面一起销毁重建（导航状态同时被重置）→ 重建只能放在**失败后的重试路径**，
//      绝不能放主路径（`CoverAppLauncher.launchPackageToCover` 现在就是这么做的）；
//   5) 悬浮窗上下文里**不能**弹系统 Dialog / Toast（窗口类型 2 / 2005 与 2038 冲突会崩
//      进程）→ 必须用同树浮层（CompactMenuDialog / ConfirmOverlay）与自绘 CoverToast。
//
// 因此：默认关闭；控制台文案已标注"已知问题较多，非必要不建议开启"。
// **新增逻辑请务必在"关闭悬浮窗模式"的情况下也回归一遍。**
// ============================================================================

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dsh.sportscam.R
import com.dsh.sportscam.SportsCamApp
import com.dsh.sportscam.device.FlashlightController
import com.dsh.sportscam.ui.theme.SportsCamBlack

/**
 * 锁屏之上的副屏界面（悬浮窗模式）。
 *
 * ============================ 为什么需要它 ============================
 * 真机上抓到的副屏窗口 z-order（序号越小越靠上）：
 *
 *   #5  type=2009  FlipSystemUIWindow
 *   #6  type=2017  FlipKeyguardWindow            ← vivo 自绘的副屏锁屏
 *   #8  type=2038  cn.pashiguoke.subscreen       ← 应用悬浮窗：在锁屏【之上】✓
 *   #9  type=1     CoverHomeActivity             ← 普通 Activity 窗口：在锁屏【之下】✗
 *
 * 结论（也是前面所有失败的最终原因）：
 * vivo 的副屏锁屏是**自绘的系统窗口**（type 2017），层级高于普通 Activity 窗口。
 * 因此 `android:showWhenLocked`、`WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED`、
 * `KeyguardManager.requestDismissKeyguard()` 对它**全都无效**（已逐项实测：
 * 日志里反复出现"请求解除锁屏（keyguard 仍锁定）"，而锁屏纹丝不动）。
 *
 * 唯一能出现在它之上的，是 **TYPE_APPLICATION_OVERLAY** 悬浮窗
 * —— 这正是原版《超级小副屏》在副屏上常驻一个 `type=2038` 窗口的原因。
 *
 * 所以：锁屏时把启动器界面画进悬浮窗；解锁后收起，交回给普通的 Activity 窗口。
 * ====================================================================
 */
class CoverOverlayWindow(private val appContext: Context) {
    private val flashlight = FlashlightController(appContext)
    private var root: OverlayComposeView? = null

    val isShowing: Boolean get() = root != null

    // ------------------------------------------------------------------
    // 状态栏「事件驱动」隐藏用的诊断计数器（见 attachStatusBarWatch）
    // ------------------------------------------------------------------

    /** 收到的 WindowInsets 分发次数 */
    private var insetsEvents = 0

    /** 其中"statusBars 处于可见"的次数 —— 用户手势把状态栏顶出来的证据 */
    private var insetsVisibleEvents = 0

    /** 最近一次"因为事件而立刻再 hide"的时间（节流用） */
    private var lastEventHideAt = 0L

    /** 最近一次落盘重申日志的时间（节流用，见 reassertStatusBar） */
    private var lastReassertLogAt = 0L

    /** 重入保护：hide() 在 insets 分发里可能同步触发下一次分发 */
    private var inInsetsCallback = false

    fun show(coverDisplayId: Int) {
        if (root != null) return
        if (!Settings.canDrawOverlays(appContext)) {
            CoverDebugLog.log(TAG, "show: 没有悬浮窗权限，放弃")
            return
        }

        val dm = appContext.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = runCatching { dm?.getDisplay(coverDisplayId) }.getOrNull()
        if (display == null) {
            CoverDebugLog.log(TAG, "show: 找不到副屏 $coverDisplayId")
            return
        }

        // ★ 关键：必须用 createWindowContext(TYPE_APPLICATION_OVERLAY) 拿"窗口上下文"。
        //   只做 createDisplayContext 的话，Android 11+ 上 addView 会**静默丢弃**窗口
        //   —— 不抛异常、日志显示"已添加"，但窗口根本不存在（本工程实测踩过）。
        //   这也是同工程里 ScreenOrientationOverlay 能成功挂出副屏窗口的原因。
        var displayContext: Context = appContext.createDisplayContext(display)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext = runCatching {
                displayContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
                )
            }.getOrDefault(displayContext)
        }

        val themed = ContextThemeWrapper(displayContext, R.style.Theme_SportsCam)
        val view = OverlayComposeView(themed, coverDisplayId, flashlight).apply {
            // 沉浸式：把状态栏/导航栏藏起来。悬浮窗一旦盖住副屏，
            // 底下的 vivo 桌面仍可能把状态栏顶出来，那条状态栏会盖在我们上面并吃掉点击。
            @Suppress("DEPRECATION")
            systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 起个名字：dumpsys 里一眼能认出这是启动器悬浮窗（不是方向锁定那个 0x0 窗口）
            title = "cover-launcher-overlay"
            // 让窗口自身能感知横竖屏/尺寸变化（悬浮窗不会触发 Activity 重建）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val wm = displayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            CoverDebugLog.log(TAG, "show: 拿不到 WindowManager")
            return
        }
        runCatching { wm.addView(view, params) }
            .onSuccess {
                root = view
                view.attachLifecycle()
                CoverOverlayState.overlaying = true
                insetsEvents = 0
                insetsVisibleEvents = 0
                lastEventHideAt = 0L
                // ★★ 事件驱动地隐藏状态栏（第二轮 D2，用户实测"只有第一次手势修好了"）：
                //   先挂监听，再施加一次隐藏 —— 顺序很重要，否则第一次的"顶出来"会漏掉。
                attachStatusBarWatch(view)
                // ★ 隐藏状态栏（用户实测：vivo 的"切后台/回桌面"手势会把顶部状态栏顶出来
                //   并常驻）。Activity 那半由 CoverHomeActivity 的 applyImmersiveMode() 负责；
                //   但悬浮窗是**独立的 2038 窗口**，盖在上面时状态栏是否显示由**它自己**决定，
                //   所以这里必须再对悬浮窗的 view 隐藏一次，否则悬浮窗一出来状态栏又常驻。
                applyHideStatusBar(view, "show 后首次施加")
                CoverDebugLog.log(TAG, "show: 悬浮窗已添加 display=$coverDisplayId")
                // 落盘确认它真的挂上了（"addView 没抛异常"并不等于窗口存在）
                view.postDelayed({
                    val attached = view.isAttachedToWindow
                    CoverDebugLog.log(
                        TAG,
                        "show: 校验 attached=$attached " +
                            "size=${view.width}x${view.height} shown=${view.isShown}"
                    )
                    // ★ 第三轮：`attached=false` 要当**失败**处理，不能只打一行日志。
                    //   真机日志里出现过 `show: 悬浮窗已添加` 之后 ~700ms 就是
                    //   `show: 校验 attached=false`（窗口没真正挂上，或已被随后的 hide 摘掉）。
                    //   以前这里不改状态 → root 指向一个"僵尸 view"、isShowing 恒为 true，
                    //   挂出/收起状态机被卡住，接着就是成对的 hide/show 抢来抢去
                    //   （用户看到"图标/名称反复消失又出现"）。
                    //   现在：只要还是当前这个 root，就清掉它 + 复位 overlaying，
                    //   让下一次 syncOverlayWindow() 干净地重新挂一遍。
                    if (!attached && root === view) {
                        root = null
                        CoverOverlayState.overlaying = false
                        CoverDebugLog.log(
                            TAG,
                            "show: 校验失败（attached=false）→ 清空 root，等下一次干净挂出"
                        )
                    }
                }, 1_200L)
            }
            .onFailure {
                CoverDebugLog.log(TAG, "show: addView 失败 ${it.message}")
            }
    }

    /**
     * 在悬浮窗的 view 上挂"状态栏又冒出来了就立刻按回去"的监听（第二轮 D2）。
     *
     * ============================ 为什么必须事件驱动 ============================
     * 用户实测（第二轮反馈）：打开悬浮窗模式后**第一次**用 vivo 的"切后台 / 回桌面"
     * 手势，状态栏短暂出现后消失 ✓（上一轮的修复），但**之后再怎么手势都不生效** ✗。
     *
     * 含义很明确：`hide()` 是**一次性请求**，被系统在第一次手势后清掉了
     * （手势 = 用户主动要求临时显示系统栏，会覆盖我们先前那次 hide 请求）；
     * 之后只靠"1 秒一次重申"会留下最长 1 秒的窗口，而且如果
     * `WindowInsetsController` 的请求因为"窗口没变化"被 ROM 判为 no-op，就**再也按不回去**。
     *
     * 所以改成：**监听窗口的 insets 分发**，一旦发现 `statusBars` 变成可见，
     * 在同一次分发里立刻再 `hide()` 一次（事件驱动 > 周期轮询）。
     * 1 秒重申（[reassertStatusBar]）保留作为兜底。
     *
     * 注意两点：
     *  · 监听器**原样返回 insets**（不消费）——我们的目的是"把系统栏藏起来"，
     *    不是把 insets 吃掉；返回消费后的 insets 会让底下的 Activity 布局错乱；
     *  · 回调里调用 `hide()` 可能同步触发下一次分发 → 用 [inInsetsCallback] 防重入，
     *    并用 [lastEventHideAt] 做 100ms 节流（否则可能形成回调风暴）。
     * ==========================================================================
     */
    private fun attachStatusBarWatch(view: android.view.View) {
        runCatching {
            view.setOnApplyWindowInsetsListener { v, insets ->
                if (!inInsetsCallback) {
                    inInsetsCallback = true
                    runCatching {
                        insetsEvents++
                        // ⚠ WindowInsets.isVisible(int) 是 API 30+，minSdk 29 上直接调用会崩
                        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            runCatching {
                                insets.isVisible(android.view.WindowInsets.Type.statusBars())
                            }.getOrDefault(false)
                        } else {
                            false
                        }
                        if (visible) {
                            insetsVisibleEvents++
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastEventHideAt > 100L) {
                                lastEventHideAt = now
                                applyHideStatusBar(v, "insets 事件发现状态栏可见")
                                // ★ 再来一发：有些 ROM 在"临时显示"动画结束时会**又**把状态栏
                                //   摆回来，只 hide 一次会被随后的动画覆盖。150ms 后补一次
                                //   （不写日志，避免刷屏）。
                                v.postDelayed({ runCatching { applyHideStatusBar(v, "") } }, 150L)
                                CoverDebugLog.log(
                                    TAG,
                                    "[StatusBar] insets 可见 → 立刻再 hide（+150ms 补一发）" +
                                        "（第 $insetsVisibleEvents 次可见事件 / 共 $insetsEvents 次分发）"
                                )
                            }
                        }
                    }
                    inInsetsCallback = false
                }
                insets
            }
        }.onFailure { CoverDebugLog.log(TAG, "挂 insets 监听失败: ${it.message}") }

        // 第二路信号（老 API / 部分 ROM 只走这条）：`systemUiVisibility` 变化回调。
        // 在 R+ 上我们是用 WindowInsetsController 隐藏的，这条**可能永不触发** ——
        // 保留它只是为了"多一路探测"，触发时同样立刻再 hide 一次（并落盘）。
        runCatching {
            @Suppress("DEPRECATION")
            view.setOnSystemUiVisibilityChangeListener { vis ->
                @Suppress("DEPRECATION")
                val fullscreen = (vis and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
                if (!fullscreen) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastEventHideAt > 100L) {
                        lastEventHideAt = now
                        applyHideStatusBar(view, "systemUiVisibility 变化（状态栏可见）")
                        CoverDebugLog.log(
                            TAG,
                            "[StatusBar] systemUiVisibility 变化 → 立刻再 hide（vis=$vis）"
                        )
                    }
                }
            }
        }.onFailure { CoverDebugLog.log(TAG, "挂 systemUiVisibility 监听失败: ${it.message}") }
    }

    /**
     * 隐藏状态栏（作用于**悬浮窗自己**的窗口）。
     *
     * 为什么悬浮窗还要单独做一遍：Activity 那半（`CoverHomeActivity.applyImmersiveMode()`）
     * 管不了这条 2038 窗口 —— 窗口盖在最上面时，状态栏显不显示由**顶层窗口**决定。
     * 而 vivo 的"切后台/回桌面"手势会把状态栏顶出来并常驻（用户实测），
     * 所以不仅要在 show() 时隐藏，还要能被**周期性重申**（见 [reassertStatusBar]）。
     */
    private fun applyHideStatusBar(view: android.view.View, why: String = "") {
        hideStatusBarNow(view)
        if (why.isNotEmpty()) statusBarDiag(why)
    }

    /**
     * 实际执行"把状态栏藏起来"：**现代 API + legacy 标志一起施加**。
     *
     * ★ 为什么两个都要（第二轮 D2 真机日志实测，证据见下）：
     *   装机日志（2026-10-10 23:40~23:51，604 次采样）：
     *     [StatusBar] reassert hid=true visible=true attached=true …  ← 状态栏一直是"可见"
     *     [StatusBar] systemUiVisibility 变化 → 立刻再 hide（vis=2）  ← 系统把 FULLSCREEN 位清掉了
     *   含义：
     *     · 现代 `WindowInsetsController.hide()` 在这条 2038 覆盖窗上**没有生效**
     *       （hid=true 只代表 controller 非空、调用没抛异常）；
     *     · 用户说的"第一次手势时状态栏短暂显示后消失"其实是
     *       `show()` 里**那一次** legacy 标志（systemUiVisibility=FULLSCREEN|IMMERSIVE_STICKY…）做的；
     *       手势之后系统把 FULLSCREEN 位清掉了（vis 变成 2 = 只剩 HIDE_NAVIGATION），
     *       而当时周期重申只走现代 API → **再也按不回去** ✗，与用户实测完全吻合。
     *   本应用 **targetSdk = 29**，legacy 标志在 Android 13/14 上**依然生效**
     *   （系统只对 targetSdk >= 30 的应用忽略它们），所以这条是真正能按回去的路。
     */
    private fun hideStatusBarNow(view: android.view.View) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.windowInsetsController?.apply {
                    hide(android.view.WindowInsets.Type.statusBars())
                    systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            @Suppress("DEPRECATION")
            val want = view.systemUiVisibility or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            if (view.systemUiVisibility != want) {
                view.systemUiVisibility = want
            }
        }.onFailure { CoverDebugLog.log(TAG, "隐藏状态栏失败: ${it.message}") }
    }

    /**
     * 状态栏可见性诊断：读**当时**的真实值并落盘。
     *
     * 为什么必须落盘（用户要求）：宿主（桌面）到底有没有执行我们的 hide，
     * 只看代码是看不出来的 —— 用户能从
     * `/sdcard/Android/data/cn.pashiguoke.subscreen/files/cover_debug.log`
     * 里直接搜 `[StatusBar]` 看出"限制到底有没有被执行、执行时状态栏是可见还是已隐藏"。
     */
    private fun statusBarDiag(why: String) {
        val view = root ?: return
        val visible = statusBarsVisible(view)
        val controller = runCatching { view.windowInsetsController != null }.getOrDefault(false)
        CoverDebugLog.log(
            TAG,
            "[StatusBar] reassert hid=${controller} visible=${visible?.toString() ?: "未知"}" +
                " attached=${view.isAttachedToWindow}" +
                " 事件=${insetsEvents}次(可见=${insetsVisibleEvents}次) 原因=$why"
        )
    }

    /**
     * 状态栏此刻是否可见；拿不到返回 null。
     * ⚠ `WindowInsets.isVisible(int)` 是 API 30 才有的方法，minSdk 29 必须判版本，
     *   否则在 Android 10 设备上会 NoSuchMethodError（本工程 minSdk 29）。
     */
    private fun statusBarsVisible(view: android.view.View): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.statusBars())
        }.getOrNull()
    }

    /** 供保活循环周期性重申"隐藏状态栏"（用户手势会把状态栏顶回来） */
    fun reassertStatusBar() {
        val view = root ?: return
        if (!view.isAttachedToWindow) return
        // ⚠ 落盘节流：这段重申跑在 1s 的快循环里（350ms 循环 + 1s 节流），
        //   如果每次都写一行，cover_debug.log（上限 256KB）大约一小时就会被刷爆，
        //   而排查 `[Launch]`（D3）恰恰要靠这份日志 —— 所以：
        //   ① 状态栏**确实可见**（= 用户手势顶出来了，最需要取证）→ 一定落盘；
        //   ② 否则每 10s 至少留一行"心跳"，证明重申还在跑。
        val visible = statusBarsVisible(view)
        val now = android.os.SystemClock.elapsedRealtime()
        val shouldLog = visible == true || (now - lastReassertLogAt > 10_000L)
        if (shouldLog) lastReassertLogAt = now

        runCatching { hideStatusBarNow(view) }

        if (shouldLog) {
            CoverDebugLog.log(
                TAG,
                "[StatusBar] reassert hid=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.R}" +
                    " visible=$visible attached=${view.isAttachedToWindow}" +
                    " 事件=${insetsEvents}次(可见=${insetsVisibleEvents}次) 原因=1s 周期重申" +
                    "（现代+legacy 双通道已重新施加）"
            )
        }
    }

    fun hide(reason: String) {        CoverOverlayState.overlaying = false
        val view = root ?: return
        root = null
        runCatching {
            val dm = appContext.getSystemService(android.hardware.display.DisplayManager::class.java)
            // 用哪个 WindowManager 移除并不重要，用应用自身的即可
            appContext.getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
        }.onFailure { CoverDebugLog.log(TAG, "hide: 移除失败 ${it.message}") }
        runCatching { view.detachLifecycle() }
        CoverDebugLog.log(TAG, "hide: 已移除（$reason）")
    }
}

/**
 * 承载 Compose 界面的悬浮窗 View。
 *
 * `WindowManager.addView` 没有 Activity 的生命周期，而 Compose 需要
 * LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner 三件套，
 * 界面里还用了 `BackHandler`（需要 `OnBackPressedDispatcherOwner`），
 * 所以这里让 View 自己把这四个接口都实现掉，并挂到 ViewTree 上。
 *
 * ⚠ 一开始漏了 OnBackPressedDispatcherOwner，真机上表现为
 *   `IllegalStateException: No OnBackPressedDispatcherOwner was provided via
 *    LocalOnBackPressedDispatcherOwner` —— 悬浮窗添加成功、250ms 后整进程崩溃。
 */
private class OverlayComposeView(
    context: Context,
    private val coverDisplayId: Int,
    private val flashlight: FlashlightController
) : AbstractComposeView(context),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner,
    ActivityResultRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    /**
     * 有触摸 = 用户正在使用界面 → 重置「自动息屏」计时。
     *
     * 必须在这里记：悬浮窗是 NOT_FOCUSABLE 的独立窗口，触摸不会走到 Activity 的
     * `onUserInteraction()`，只能在自己的 View 里收（见 [CoverScreenTimeout]）。
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_DOWN) {
            CoverScreenTimeout.markInteraction()
        }
        return super.dispatchTouchEvent(ev)
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    /** 界面里 BackHandler 需要的调度器；悬浮窗收不到按键，这里主要是"别崩" */
    override val onBackPressedDispatcher = OnBackPressedDispatcher()

    /**
     * 界面里 `rememberLauncherForActivityResult`（相册权限 / 小组件授权 / 通知权限）
     * 需要它，否则会抛 `IllegalStateException` 直接崩进程。
     *
     * 悬浮窗自己没有 Activity，所以：
     *  · 优先借用**仍活着的启动器 Activity** 的 registry（能真正弹出系统界面并拿到结果）；
     *  · 借不到就退化成空实现 —— 不崩，但那些"申请授权"的入口点了不会有反应
     *    （对应界面会显示"未授权"提示，用户仍可去系统设置里手动开）。
     */
    override val activityResultRegistry: ActivityResultRegistry
        get() = OverlayResultHost.registry ?: NoopActivityResultRegistry

    init {
        savedStateController.performRestore(null)
        setViewTreeLifecycleOwner(this)
        setViewTreeViewModelStoreOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        setBackgroundColor(SportsCamBlack.toArgbInt())
    }

    fun attachLifecycle() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun detachLifecycle() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        // 旋转/尺寸变化时让 Compose 重新测量
        requestLayout()
    }

    @androidx.compose.runtime.Composable
    override fun Content() {
        val torchOn by flashlight.isOn.collectAsState()
        val torchAvailable by flashlight.available.collectAsState()
        // 显式提供两个 owner：
        //  · OnBackPressedDispatcherOwner —— 界面里的 BackHandler 需要；
        //  · ActivityResultRegistryOwner —— 界面里的 rememberLauncherForActivityResult
        //    （相册权限申请、小组件授权、通知权限）需要，
        //    漏掉它会抛 "No ActivityResultRegistryOwner was provided via
        //    LocalActivityResultRegistryOwner" 直接崩进程（实测踩过两次）。
        androidx.compose.runtime.CompositionLocalProvider(
            LocalOnBackPressedDispatcherOwner provides this,
            LocalActivityResultRegistryOwner provides this
        ) {
            SportsCamApp(
                flashlightOn = torchOn,
                flashlightAvailable = torchAvailable,
                displayId = coverDisplayId,
                onToggleTorch = { flashlight.toggle() },
                // ★★ 关键修复（2026-10）：
                //   悬浮窗宿主**必须**把这两个参数传下去！
                //   之前这里没传 → `returnToHomeOnReentry` 取默认值 **true** →
                //   悬浮窗每次重建（打开应用、重建悬浮窗、切换设置）都会强制回到轮播主界面，
                //   用户在控制台关掉「退出应用后回到主界面」也**完全没用**（实测反馈的 bug）。
                //
                //   悬浮窗是"就地重建"，不属于"重新进入应用"，所以 reentryTick 恒为 0，
                //   不触发那条"重新进入 → 回主界面"的重置逻辑。
                reentryTick = 0,
                returnToHomeOnReentry = CoverSettings.isReturnToHomeOnReentry(
                    androidx.compose.ui.platform.LocalContext.current
                ),
                isOverlayHost = true
            )
        }
    }
}

/** androidx.compose.ui.graphics.Color → android.graphics.Color */
private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )

/**
 * 悬浮窗模式下"暂时让位"的状态。
 *
 * 悬浮窗会盖住一切，所以从界面里启动别的应用时必须先收起它，
 * 否则用户在副屏上打开的应用会被我们挡着点不到。
 */
object CoverOverlayState {

    /**
     * 悬浮窗此刻是否正盖在副屏上（Compose 可观察）。
     *
     * 普通 Activity 的界面据此**停止渲染** —— 否则同一个启动器界面会被渲染两遍
     * （Activity 一遍 + 悬浮窗一遍），副屏上明显掉帧。
     */
    var overlaying by androidx.compose.runtime.mutableStateOf(false)
        internal set

    @Volatile
    private var suppressUntil: Long = 0L

    /**
     * 「粘住」标志（用于"不主动弹出"模式）。
     *
     * 语义：启动器**出现过一次**之后，悬浮窗就一直保持，不再因为
     * "我们自己被暂停"而消失 —— 这正是"防退出"的关键：
     * 用户做返回/上滑手势时我们的界面会 onPause，如果还按"启动器是否在前台"判断，
     * 悬浮窗就会跟着收掉（实测反馈的 bug："任何退出手势都能使它退出"）。
     *
     * 解除条件只有两种（第九轮最终定稿）：
     *   · 从界面里启动别的应用（[suppress]）✓；
     *   · **关掉悬浮窗模式** ✓ —— 用户正常关闭的**唯一途径**是
     *     **控制台 ③ 页「悬浮窗防退出模式」开关** ✓。
     * ⚠ 用户最终决定「不提供任何逃生通道」✗：通知栏两处出口（方向通知「退出」药丸、
     *   保活通知「退出悬浮窗」action）都已删除 ✗，视频1 页右滑方案也已撤回 ✗。
     *   `CoverKeeperService.ACTION_EXIT_OVERLAY` 的处理器仍在（当前无 UI 入口，代码/测试可触发）✓。
     */
    @Volatile
    var sticky: Boolean = false
        private set

    fun markSticky() {
        sticky = true
    }

    fun clearSticky() {
        sticky = false
    }

    /** 让悬浮窗在 [ms] 毫秒内不再挂出（默认 15 秒，够用户在新应用里操作） */
    fun suppress(ms: Long = 15_000L, reason: String = "") {
        // 从界面里启动别的应用 = 用户主动离开启动器，粘住状态必须解除，
        // 否则抑制期一过悬浮窗又冒出来盖住刚打开的应用。
        val hadSticky = sticky
        sticky = false
        suppressUntil = android.os.SystemClock.elapsedRealtime() + ms
        CoverDebugLog.log(
            "Overlay",
            "suppress(${ms}ms) reason=${reason.ifEmpty { "-" }} 清除粘住=$hadSticky " +
                "调用=${callerHint()}"
        )
    }

    /** 找出是谁调的 suppress —— 排查"莫名让位"时必须的信息 */
    private fun callerHint(): String = runCatching {
        Throwable().stackTrace
            .firstOrNull { frame ->
                !frame.className.startsWith("com.dsh.sportscam.cover.CoverOverlay")
            }
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            ?: "-"
    }.getOrDefault("-")

    /** 立即解除抑制（例如副屏重新点亮时） */
    fun clearSuppress() {
        suppressUntil = 0L
    }

    val suppressed: Boolean
        get() = android.os.SystemClock.elapsedRealtime() < suppressUntil
}

/**
 * 悬浮窗借用 Activity 的 ActivityResultRegistry 的桥梁。
 * 由 [com.dsh.sportscam.BaseLauncherActivity] 在 onStart/onStop 里挂上/摘掉。
 */
object OverlayResultHost {
    @Volatile
    var registry: androidx.activity.result.ActivityResultRegistry? = null
}

/** 借不到 Activity 时的兜底：什么都不做，但绝不崩 */
private object NoopActivityResultRegistry : androidx.activity.result.ActivityResultRegistry() {
    override fun <I : Any?, O : Any?> onLaunch(
        requestCode: Int,
        contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
        input: I,
        options: androidx.core.app.ActivityOptionsCompat?
    ) {
        CoverDebugLog.log("Overlay", "无 Activity 可用，忽略结果请求 requestCode=$requestCode")
    }
}

private const val TAG = "Overlay"
