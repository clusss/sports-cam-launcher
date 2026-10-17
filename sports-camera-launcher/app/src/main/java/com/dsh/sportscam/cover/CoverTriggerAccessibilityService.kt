package com.dsh.sportscam.cover

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.resume

/**
 * 自动拉起：副屏上出现「桌面 / 指定触发应用」时，把启动器拉回副屏。
 *
 * 为什么必须用无障碍服务：Android 10+ 限制后台启动 Activity，
 * 而**无障碍服务被视为"用户正在交互"，是稳定的合法例外**。
 *
 * 关键实现点（来自《超级小副屏》反编译结论）：
 *  - 只监听 TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED；
 *  - accessibilityFlags 里的 FLAG_REQUEST_MULTI_WINDOW_ACCESS 是折叠屏副屏场景的关键位
 *    （见 res/xml/accessibility_service_config.xml）；
 *  - 用事件的 displayId 判断"这事发生在哪块屏"——这是唯一能知道副屏上现在是什么的稳定办法。
 *    这里用**反射**读取，避免不同 ROM 上该 API 可见性差异导致编译/运行问题。
 */
class CoverTriggerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CoverTriggerA11y"
        private const val PULL_DEBOUNCE_MS = 4_000L

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var instance: CoverTriggerAccessibilityService? = null

        /**
         * 拉取"副屏最上层的应用窗口属于谁"。
         *
         * 这是判定悬浮窗该不该出现的**权威信息**：直接问系统窗口列表，
         * 不依赖事件流（事件会被自家窗口、SystemUI、后台面板互相顶替而抖动）。
         * 服务没在跑时返回 null。
         */
        fun refreshForegroundFromWindows(): String? {
            val svc = instance
            if (svc == null) {
                diag("拉取失败: 无障碍实例为空（服务未连接？）")
                return null
            }
            return svc.pullCoverForeground()
        }

        /**
         * 无障碍截屏（**不需要 Shizuku**）。
         *
         * `AccessibilityService.takeScreenshot(displayId, …)` 自 API 30 起支持指定显示器，
         * 正好用来抓副屏。前提：本服务的配置里有 `android:canTakeScreenshot="true"`
         * （见 res/xml/accessibility_service_config.xml），且服务处于开启状态。
         *
         * 返回的是**硬件位图**（HARDWARE）—— 它不能直接 compress 成 PNG，
         * 调用方需要先 `copy(ARGB_8888, false)` 转成软件位图（[CoverScreenshot] 已处理）。
         *
         * @return 成功返回 bitmap；服务未连接 / 系统版本过低 / 失败返回 null
         */
        suspend fun captureDisplay(displayId: Int): android.graphics.Bitmap? {
            val svc = instance ?: run {
                diag("截屏失败: 无障碍实例为空")
                return null
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                diag("截屏失败: 系统版本低于 Android 11（无 takeScreenshot）")
                return null
            }
            return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                runCatching {
                    svc.takeScreenshot(
                        displayId,
                        executor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(
                                screenshot: AccessibilityService.ScreenshotResult
                            ) {
                                val bmp = runCatching {
                                    android.graphics.Bitmap.wrapHardwareBuffer(
                                        screenshot.hardwareBuffer,
                                        screenshot.colorSpace
                                    )
                                }.getOrNull()
                                runCatching { screenshot.hardwareBuffer.close() }
                                executor.shutdown()
                                if (cont.isActive) cont.resume(bmp)
                            }

                            override fun onFailure(errorCode: Int) {
                                executor.shutdown()
                                diag("takeScreenshot 失败 code=$errorCode")
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                }.onFailure {
                    executor.shutdown()
                    diag("takeScreenshot 异常: ${it.message}")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        // 诊断日志：只在内容变化或超过 5 秒时落盘，避免 350ms 一次的刷屏
        private var lastDiag: String? = null
        private var lastDiagAt = 0L
        private fun diag(message: String) {
            val now = System.currentTimeMillis()
            if (message == lastDiag && now - lastDiagAt < 5_000L) return
            lastDiag = message
            lastDiagAt = now
            CoverDebugLog.log("FgPull", message)
        }
    }

    /**
     * 副屏最上层**别的应用**的包名。
     *
     * 只看 `TYPE_APPLICATION` 窗口（忽略状态栏、悬浮窗、输入法等），
     * 按 layer 从高到低取第一个 —— 那就是用户正在用的应用。
     * 自家窗口跳过：我们自己的悬浮窗永远在最上层，不跳过就永远是"自己"。
     */
    private fun pullCoverForeground(): String? = runCatching {
        val coverId = CoverDisplay.coverDisplayId(this)
        if (coverId == Display.DEFAULT_DISPLAY) {
            diag("拉取失败: 找不到副屏（coverId=0）")
            return@runCatching null
        }

        val all = windows
        if (all == null) {
            diag("拉取失败: windows 为 null（缺 flagRetrieveInteractiveWindows？）")
            return@runCatching null
        }
        if (all.isEmpty()) {
            diag("拉取失败: windows 为空列表")
            return@runCatching null
        }

        val known = all.filter { windowDisplayId(it) != -1 }
        val candidates = if (known.isNotEmpty()) known.filter { windowDisplayId(it) == coverId } else all

        // 诊断：把每个窗口的 type / displayId / layer / 包名原样打出来
        val detail = all.joinToString("; ") { w ->
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull()
            "type=${w.type} disp=${windowDisplayId(w)} layer=${w.layer} pkg=$pkg"
        }

        val apps = candidates
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }

        val picked = apps.asSequence()
            .mapNotNull { w -> runCatching { w.root?.packageName?.toString() }.getOrNull() }
            .firstOrNull { it != packageName }

        diag(
            "拉取: cover=$coverId 全部=${all.size} 副屏=${candidates.size} 应用=${apps.size} 选中=$picked | $detail"
        )
        picked
    }.onFailure { diag("拉取异常: ${it::class.java.simpleName} ${it.message}") }.getOrNull()

    private fun windowDisplayId(w: AccessibilityWindowInfo): Int =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) w.displayId else -1
        }.getOrDefault(-1)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastPullAt = 0L
    private var homePackages: Set<String> = emptySet()
    private var lastTargetPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        instance = this
        ShizukuShell.init(applicationContext)
        // ★ 必须过滤掉"伪 HOME"包（com.android.settings 等）：
        //   vivo 的应用锁解锁界面就是 com.android.settings/…secret.PasswordActivity，
        //   若"设置"被当成桌面包，自动拉起会在解锁界面刚出现时把启动器拉上来盖住它。
        homePackages = CoverForeground.sanitizeHomePackages(queryHomePackages())
        CoverForeground.onServiceConnected(homePackages)
        Log.d(TAG, "onServiceConnected, homePackages=$homePackages")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val pkg = e.packageName?.toString() ?: return

        val coverId = CoverDisplay.coverDisplayId(this)
        val eventDisplayId = displayIdOfEvent(e)

        val fromCover = coverId != Display.DEFAULT_DISPLAY &&
            (eventDisplayId == -1 || eventDisplayId == coverId)

        // ★ 记录副屏前台是谁 —— 但**排除自家包**。
        //
        //   实测踩到的坑：我们的 Activity 与悬浮窗本身会持续产生窗口事件，
        //   一旦把它记成"副屏前台"，"桌面是否在前台"就永远为真 ——
        //   表现是"设置已经打开了，悬浮窗还盖在上面拦着"（日志里能看到
        //   settings 与 cn.pashiguoke.subscreen 反复互相顶替）。
        //   这里要的是"最上层的**别的**应用"。
        if (fromCover && pkg != packageName) {
            CoverForeground.onCoverWindow(pkg, coverId)
        }

        if (pkg == packageName) return                       // 自己，忽略
        if (!fromCover) return

        // 事件来自哪块屏？只有副屏上的事件才需要处理
        if (eventDisplayId != -1 && eventDisplayId != coverId) return

        // ★ 悬浮窗模式：前台一变就立刻同步一次（不等 350ms 轮询）。
        //   这里**不再**清"让位"抑制 —— 事件流很嘈杂（自家窗口、SystemUI、面板会互相顶替），
        //   拿它解抑制会让刚让位给第三方应用的悬浮窗立刻冒回来。
        //   解抑制改由保活服务按权威前台值（使用记录）判断。
        if (CoverSettings.isOverlayModeEnabled(this)) {
            CoverKeeperService.syncOverlaySoon(this)
        }

        if (!CoverSettings.isAutoPullEnabled(this)) return

        val triggers = CoverSettings.triggerPackages(this)
        val isHome = pkg in homePackages
        if (!isHome && pkg !in triggers) return

        if (lastTargetPackage == pkg && System.currentTimeMillis() - lastPullAt < PULL_DEBOUNCE_MS) {
            return
        }
        lastTargetPackage = pkg
        pullToCover("$pkg@$eventDisplayId")
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        instance = null
        // 无障碍掉了 → 截屏能力失效 → **自动关闭截屏开关**，并刷新通知（那行按钮随之消失）
        autoDisableScreenshot()
        // 服务没了就失去"副屏前台是谁"的信息源：清掉数据，
        // 让悬浮窗判断退回"不限制前台"的旧行为，而不是拿着一份过期数据乱判。
        CoverForeground.onServiceDisconnected()
        scope.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        instance = null
        autoDisableScreenshot()
        CoverForeground.onServiceDisconnected()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 无障碍服务断开时自动关闭「通知栏截屏按钮」开关。
     *
     * 原因：截屏用 `AccessibilityService.takeScreenshot` 实现（见 [CoverScreenshot]），
     * 服务一断就必然失败 —— 与其留一个点了没反应的按钮，不如自动关掉，
     * 通知栏那行按钮也随之消失（用户要求）。
     */
    private fun autoDisableScreenshot() {
        runCatching {
            if (CoverSettings.isScreenshotEnabled(this)) {
                CoverSettings.setScreenshotEnabled(this, false)
                CoverDebugLog.log("Shot", "无障碍服务断开 → 自动关闭截屏开关")
                OrientationNotification.show(this)
            }
        }
    }

    // ------------------------------------------------------------------

    private fun pullToCover(reason: String) {
        // ★「不主动弹出」模式下**不做自动拉起**。
        //   自动拉起会把我们的界面重新拉到副屏前台（实测：第三方应用到前台后 43ms
        //   我们的 Activity 又被拉起，前台判定随之翻回"我们自己"，悬浮窗就盖了上去）。
        //   它与"不打扰别的应用"在设计上直接冲突，所以该模式下整体关闭。
        if (CoverSettings.isOverlayPassive(this)) {
            Log.d(TAG, "pull($reason) 跳过：不主动弹出模式")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastPullAt < PULL_DEBOUNCE_MS) return
        lastPullAt = now

        val coverId = CoverDisplay.coverDisplayId(this)
        if (coverId == Display.DEFAULT_DISPLAY) return

        scope.launch {
            val result = CoverAppLauncher.launchSelfToCover(this@CoverTriggerAccessibilityService, coverId)
            Log.d(TAG, "pull($reason) -> $result")
        }
    }

    /**
     * 事件所属显示器。
     *
     * 先反射读 `AccessibilityEvent.getDisplayId()`（隐藏 API）；
     * 读不到就**退一步按 windowId 去窗口列表里查** —— 这一步很关键：
     * 之前只靠反射，失败时一律返回 -1 并被当作"可能是副屏"，
     * 于是主屏（无面板）上的应用也被记成"副屏前台"，
     * 导致悬浮窗在副屏上判断错误（该弹不弹、该收不收）。
     */
    private fun displayIdOfEvent(event: AccessibilityEvent): Int {
        runCatching {
            AccessibilityEvent::class.java.getMethod("getDisplayId").invoke(event) as? Int
        }.getOrNull()?.let { if (it >= 0) return it }

        runCatching {
            windows?.firstOrNull { it.id == event.windowId }?.displayId
        }.getOrNull()?.let { if (it >= 0) return it }

        return -1
    }

    /** 当前设备上所有「桌面」应用（用于判断副屏是不是回到了桌面） */
    private fun queryHomePackages(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}
