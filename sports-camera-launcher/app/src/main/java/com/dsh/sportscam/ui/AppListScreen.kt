package com.dsh.sportscam.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.dsh.sportscam.cover.CoverAppLauncher
import com.dsh.sportscam.cover.CoverDebugLog
import com.dsh.sportscam.cover.CoverKeeperService
import com.dsh.sportscam.cover.CoverSettings
import com.dsh.sportscam.cover.ShizukuShell
import com.dsh.sportscam.desktop.DesktopItem
import com.dsh.sportscam.desktop.DesktopStore
import com.dsh.sportscam.device.IntentLauncher
import com.dsh.sportscam.ui.common.CompactMenuDialog
import com.dsh.sportscam.ui.common.showToast
import com.dsh.sportscam.ui.theme.Accent
import com.dsh.sportscam.ui.theme.SportsCamBlack
import com.dsh.sportscam.ui.theme.TorchAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/**
 * 应用列表条目。
 *
 * ⚠ 必须标 [Immutable]：`ImageBitmap` 在 Compose 眼里不是稳定类型，
 * 不标注的话 `AppEntry` 会被判定为 unstable → `AppCell` 永远无法 skip →
 * 列表任何一次重组都会把所有可见格子全部重组，快速滑动就卡。
 */
@Immutable
data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: ImageBitmap?
)

// ---------------------------------------------------------------------------
// 「滑到顶端后继续往下拉 → 返回桌面」的阈值（与 SquareHomeScreen 上拉那套对称）
// ---------------------------------------------------------------------------

/**
 * 触发「返回桌面」的**手指位移**阈值（≥60dp）。
 *
 * 注意：跟手时视觉位移只有手指位移的一半（见 [PULL_DOWN_DAMPING]），
 * 所以这里比的是**手指**走了多远，而不是屏幕上的位移 —— 与用户"再往下拉一点"的手感一致。
 */
private const val PULL_DOWN_THRESHOLD_DP = 60f

/** 走"快速下滑"分支时的最小位移（防止把轻点/抖动当成下拉） */
private const val PULL_DOWN_FLICK_MIN_DP = 24f

/** 快速下滑分支的速度阈值（px/s，向下为正）——与上拉的 900 保持一致 */
private const val PULL_DOWN_VELOCITY_THRESHOLD = 900f

/** 松手后的"滑出 / 回弹"动画时长（ms），和上拉进列表同一套 180ms */
private const val PULL_DOWN_ANIM_MS = 180

/** 跟手的阻尼：屏幕上的位移 = 手指位移 × 这个系数（橡皮筋感，别整屏跟着手指跑） */
private const val PULL_DOWN_DAMPING = 0.5f

/** 手指位移上限（再拉就不涨了），防止拉出屏幕外太多 */
private const val PULL_DOWN_MAX_DP = 160f

/**
 * **长按兜底**阈值（ms）：按住超过它、且手指没越过 touchSlop、而 `combinedClickable`
 * 又没回调过长按 → 由 [AppCell] 里的观察者补一次「长按」。
 *
 * 取 450 的理由：系统 `ViewConfiguration.getLongPressTimeout()` 标准值是 500ms，
 * 450 落在"正常轻点（<450ms）"与"系统长按（≥500ms）"之间 ——
 * 轻点照旧是点击 ✓，真正的长按一定能拿到菜单 ✓，而 400ms 那种"手抖的短按"不会被误判成长按 ✓。
 */
private const val LONG_PRESS_FALLBACK_MS = 450L

/**
 * 应用列表的可提升状态 —— 由上层（SportsCamApp）持有。
 *
 * 关键：`queryLaunchableApps()` 要遍历 PackageManager 并把每个应用的图标解码成
 * Bitmap，几百个应用会明显卡一下（就是每次进列表都转圈的原因）。
 * 把结果与图标一起缓存在这一层，界面重建也不会丢，第二次进入直接秒开。
 */
class AppListUiState {
    var apps: List<AppEntry> by mutableStateOf(emptyList())
    var loading: Boolean by mutableStateOf(false)
    var loaded: Boolean by mutableStateOf(false)

    /** 自增即触发重新扫描（顶栏的刷新按钮用） */
    var reloadTick: Int by mutableIntStateOf(0)
}

/**
 * 全屏应用列表（长按「相簿 #3」进入）。
 *
 * 点击 = 把应用送到副屏；
 * 长按 = 呼出菜单（添加到桌面 / 从桌面移除 / Shizuku 搬栈开关 / 直接打开 / 卸载该应用）；
 * 顶栏刷新按钮 = 悬浮窗模式下是「重挂悬浮窗 + 重扫应用列表」，非悬浮窗模式下**只重扫应用**
 * （见 [AppListHeader] 的 onRefresh 注释）；
 * **滑到顶端后继续往下拉（≥60dp 松手）→ 返回桌面**（跟手 + 180ms 滑出/回弹，
 * 见 [AppListScreen] 体内 `pullDownConnection` 的注释）。
 *
 * 性能上做了三件事：
 *  1. [AppEntry] 标 `@Immutable`，让 `AppCell` 具备 skip 能力；
 *  2. 状态文字 `status` 只在顶栏的组成作用域里读取（通过 lambda 传入），
 *     避免"点一下应用就整屏重组、顺带把正在滑的网格也重组"；
 *  3. 点击/长按回调用 remember 固定引用，配合上面的稳定性，格子不会因父级重组而失效。
 */
@Composable
fun AppListScreen(
    coverDisplayId: Int,
    uiState: AppListUiState,
    onBack: () -> Unit,
    onOpenConsole: () -> Unit,
    /**
     * 「滑到顶端再往下拉」松手且过阈值时调用 = **返回桌面**。
     *
     * 默认复用 [onBack]（上层 MainActivity 里它就是 `pop`）—— 从桌面拉起来的应用列表
     * pop 回去正好就是桌面，所以不传也能用、不用改 MainActivity 的签名。
     * 若希望"无论从哪进来都回桌面"（例如从相簿 #3 三击那个隐藏入口进来的），
     * 上层显式传一个把导航栈落到 `Screen.Desktop` 的 lambda 即可覆盖。
     */
    onPullDownToDesktop: () -> Unit = onBack
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("") }
    var menuTarget by remember { mutableStateOf<AppEntry?>(null) }
    var preferTick by remember { mutableIntStateOf(0) }
    val desktopVersion = DesktopStore.versionState().intValue

    // 已加载过就不再重新扫描（这就是"下次打开秒开"的实现）；点刷新会自增 reloadTick
    LaunchedEffect(uiState.reloadTick) {
        if (uiState.loaded && uiState.reloadTick == 0) return@LaunchedEffect
        uiState.loading = true
        uiState.apps = withContext(Dispatchers.IO) { queryLaunchableApps(context) }
        uiState.loaded = true
        uiState.loading = false
    }

    val preferSet = remember(preferTick) { CoverSettings.preferShizukuApps(context) }
    val desktopSet = remember(desktopVersion) {
        DesktopStore.items(context, com.dsh.sportscam.cover.CoverDisplay.isPortraitNow(context))
            .filterIsInstance<DesktopItem.App>()
            .map { it.packageName }.toSet()
    }

    /**
     * 「重挂悬浮窗」只在**悬浮窗模式**下才有意义。
     *
     * ⚠ 刻意**不用 remember**：每次重组都重新读一次 prefs（内存里的 map，代价可忽略）。
     * 用户从本页进控制台改完设置再返回时，导航栈变化会触发重组，按钮语义立刻跟着变；
     * 非悬浮窗模式下这个按钮就只是普通的「重扫应用」，不会再出现"重挂悬浮窗"字样/动作。
     */
    val overlayMode = CoverSettings.isOverlayModeEnabled(context)

    // ---- 稳定的回调：用 remember 固定引用，避免每个格子都拿到新 lambda ----
    val onItemClick: (AppEntry) -> Unit = remember(coverDisplayId) {
        { app ->
            scope.launch {
                status = "正在送到副屏：${app.label}…"
                status = launchAppToCover(context, app.label, app.packageName, coverDisplayId)
            }
        }
    }
    val onItemLongClick: (AppEntry) -> Unit = remember { { app -> menuTarget = app } }

    // ★ 私密应用不进普通总应用列表（列表侧过滤；桌面侧由 desktop-ui 负责）。
    //   **[privateAppsEffective] 每次都重新读**（不 remember 死缓存）：它内部读一个进程内版本号，
    //   所以在私密界面加了/移了、或在控制台改了功能开关之后，本界面立刻重组 → 过滤实时生效 ✓
    //   （与上面 `overlayMode` 那处的做法一致；用的是 CoverSettings 的**生效值**，
    //     功能总开关关掉时它就是空集，被隐藏的应用会立刻回到列表）。
    val privateSet = privateAppsEffective(context)

    // 「滑到顶端再往下拉 → 返回桌面」整套（nestedScroll + 跟手 + 180ms 滑出/回弹 + 提示条）
    // 已抽到 [PullDownToDesktopBox]（私密应用界面复用同一套，避免两份实现各自退化）。
    PullDownToDesktopBox(
        onCommit = onPullDownToDesktop,
        blocked = menuTarget != null,
        modifier = Modifier
            .fillMaxSize()
            .background(SportsCamBlack)
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        AppListHeader(
            appCount = { uiState.apps.count { it.packageName !in privateSet } },
            statusText = { status },
            loading = uiState.loading,
            overlayMode = overlayMode,
            onBack = onBack,
            onRefresh = {
                if (overlayMode) {
                    // ★ 悬浮窗模式：这一个按钮承担**两个动作**（用户点一次，两件事都做）：
                    //   ① 重挂悬浮窗（CoverKeeperService.recreateOverlay）：悬浮窗模式下点列表里的
                    //      应用偶尔被 vivo 拦下来（弹「翻开手机以继续」），实测和窗口"新鲜度"有关，
                    //      移除并重新挂一次就能恢复；
                    //   ② 重扫应用列表（reloadTick 自增 → 上面的 LaunchedEffect 重新 queryLaunchableApps）。
                    CoverKeeperService.recreateOverlay(context)
                    uiState.reloadTick++
                    status = "已重挂悬浮窗，正在重扫应用…"
                } else {
                    // ★ 非悬浮窗模式：没有悬浮窗可重挂 → 只做"重扫应用"（文案/动作都不提重挂）
                    uiState.reloadTick++
                    status = "重新扫描中…"
                }
            },
            onOpenConsole = onOpenConsole
        )

        // ---------------- 顶部搜索框（按应用名或包名过滤） ----------------
        var query by remember { mutableStateOf("") }
        // 先按"私密应用"过滤，再按搜索词过滤 —— 私密应用**不进**普通列表的这一层，
        // 是列表侧的最终兜底（桌面侧由 desktop-ui 负责）
        val nonPrivateApps = remember(uiState.apps, privateSet) {
            uiState.apps.filterNot { it.packageName in privateSet }
        }
        val shownApps = remember(query, nonPrivateApps) {
            val q = query.trim()
            if (q.isEmpty()) {
                nonPrivateApps
            } else {
                nonPrivateApps.filter {
                    it.label.contains(q, ignoreCase = true) ||
                        it.packageName.contains(q, ignoreCase = true)
                }
            }
        }
        AppListSearchField(query = query, onQueryChange = { query = it })

        if (uiState.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = shownApps,
                    key = { it.packageName },
                    contentType = { "app" }
                ) { app ->
                    AppCell(
                        app = app,
                        preferShizuku = app.packageName in preferSet,
                        inDesktop = app.packageName in desktopSet,
                        onClick = onItemClick,
                        onLongClick = onItemLongClick
                    )
                }
            }
        }
    }
    }   // ↑ 应用列表主体（Column）+ [PullDownToDesktopBox] 结束（提示条在它内部渲染）

    // ---------------- 长按菜单 ----------------
    menuTarget?.let { app ->
        val inDesktop = DesktopStore.containsApp(context, app.packageName)
        CompactMenuDialog(
            title = app.label,
            subtitle = app.packageName,
            onDismiss = { menuTarget = null },
            actions = listOf(
                (if (inDesktop) "从桌面移除" else "添加到桌面") to {
                    if (inDesktop) {
                        DesktopStore.removeApp(context, app.packageName)
                    } else {
                        val portrait = com.dsh.sportscam.cover.CoverDisplay.isPortraitNow(context)
                        val cols = if (portrait) 2 else 4
                        val rows = DesktopStore.PER_PAGE / cols
                        val ok = DesktopStore.addApp(context, app.packageName, portrait, cols, rows)
                        if (!ok) showToast(context, "桌面已满，放不下")
                    }
                    menuTarget = null
                },
                (if (CoverSettings.isPreferShizuku(context, app.packageName))
                    "改回原生启动" else "始终用 Shizuku 搬栈") to {
                    CoverSettings.setPreferShizuku(
                        context, app.packageName,
                        !CoverSettings.isPreferShizuku(context, app.packageName)
                    )
                    preferTick++
                    menuTarget = null
                },
                "打开到副屏" to {
                    onItemClick(app)
                    menuTarget = null
                },
                // ★ 卸载：首选 Shizuku 静默卸载（pm uninstall --user 0），没有 Shizuku 时
                //   退化成系统卸载确认弹窗（ACTION_DELETE）。本应用自己禁止卸载 ——
                //   把启动器卸掉，这台设备就再也回不到正常界面了。
                "卸载该应用" to {
                    menuTarget = null
                    val pkg = app.packageName
                    val label = app.label
                    scope.launch {
                        status = "正在卸载：$label…"
                        when (val r = uninstallApp(context, pkg, coverDisplayId)) {
                            is UninstallResult.Silent -> {
                                // 静默卸载成功：先从列表里摘掉（立刻可见），再后台重扫一遍做权威校对
                                uiState.apps = uiState.apps.filterNot { it.packageName == pkg }
                                // 桌面上若还摆着它的图标，会变成点不开的死快捷方式 → 顺手清掉
                                DesktopStore.removeApp(context, pkg)
                                uiState.reloadTick++
                                status = "已卸载：$label"
                                showToast(context, "已卸载：$label")
                            }
                            is UninstallResult.Dispatched -> {
                                status = "请在系统弹窗里确认卸载：$label"
                                showToast(context, "请在系统弹窗里确认卸载")
                            }
                            is UninstallResult.Failed -> {
                                status = "卸载失败：${r.reason}"
                                showToast(context, "卸载失败：${r.reason}")
                            }
                        }
                    }
                }
            )
        )
    }
}

/**
 * 「滑到顶端后继续往下拉 → 返回桌面」的外壳（总应用列表 / 私密应用列表**共用**）。
 *
 * 为什么用 **nestedScroll** 而不是 pointerInput：
 *   · 嵌套滚动只在"子级（网格）**没吃掉**的那部分"上报（`onPostScroll` 的 `available`），
 *     也就是**只在列表已经到顶、没得再滚的时候**才有非零值 → 列表本身的滚动、
 *     点击、长按（combinedClickable）完全不受影响；
 *   · pointerInput 要在 Initial 阶段抢事件，容易把长按/点击弄脏（上拉那套在桌面能用，
 *     是因为桌面本来就没有可滚动的内容）。
 *
 * 判定：`available.y > 0` ⇔ 手指在往下拽、而网格已经把这段还回来了（到顶了）。
 *   手指位移累计 ≥ 60dp（或"快速下滑"：≥24dp 且向下速度 ≥900px/s）→ 松手算提交。
 *
 * 反馈：内容 1:2 阻尼下移 + 顶部冒出一条提示条（提示条的位移/透明度在**绘制阶段**读，不每帧重组）；
 *   松手后 180ms 滑出（提交）或回弹（未提交），动画时长与 SquareHome 上拉那套一致。
 *
 * @param onCommit 松手且过阈值时调用。⚠ **必须真的离开本界面**（例如 pop 回桌面），
 *                 否则内容会停在"滑出屏幕"的位置上（本函数不负责复位）。
 * @param blocked  true 时完全不响应下拉（例如有全屏浮层打开）
 * @param content  正常内容（会被整体平移 / 淡出）
 */
@Composable
internal fun PullDownToDesktopBox(
    onCommit: () -> Unit,
    blocked: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thresholdPx = with(density) { PULL_DOWN_THRESHOLD_DP.dp.toPx() }
    val flickMinPx = with(density) { PULL_DOWN_FLICK_MIN_DP.dp.toPx() }
    val maxTravelPx = with(density) { PULL_DOWN_MAX_DP.dp.toPx() }
    val thresholdVisualPx = thresholdPx * PULL_DOWN_DAMPING
    /** 手指位移 → 屏幕上的位移（阻尼） */
    val visualOf: (Float) -> Float = { travel -> travel * PULL_DOWN_DAMPING }

    var pullTravel by remember { mutableStateOf(0f) }        // 手指位移（px，向下为正）
    var pullDownActive by remember { mutableStateOf(false) } // 是否正在跟手
    var pullArmed by remember { mutableStateOf(false) }      // 是否已过阈值（只驱动提示文字，跨阈值才重组一次）
    var contentHeightPx by remember { mutableStateOf(0f) }
    val pullDownAnim = remember { Animatable(0f) }           // 松手后的滑出/回弹

    /**
     * 提示条的**显隐**（收敛成一个布尔）。
     *
     * 用 `derivedStateOf` 把"跟手 / 回弹动画的浮点值"折成布尔：动画每帧只会让这个派生值重算
     * （很便宜），**不会**每帧触发重组；只有真正翻转（开始下拉 / 完全收回）时才重组一次提示条。
     * 阈值取 0.01 而不是 0.5：回弹动画结束时才把节点移除，避免淡到一半"啪"地消失。
     */
    val hintVisible by remember {
        derivedStateOf { pullDownActive || pullDownAnim.value > 0.01f }
    }

    // 浮层打开时下拉不生效；回调/开关取"最新"那一份，避免 remember 里拿到旧值
    val blockedNow by rememberUpdatedState(blocked)
    val commitPullDown by rememberUpdatedState(onCommit)

    // remember 的 key 用这几个 px 阈值：控制台改过副屏分辨率/DPI 后本界面会重组，
    // 这里跟着重建连接，避免继续用旧 density 算出来的阈值
    val connection = remember(thresholdPx, flickMinPx, maxTravelPx) {
        object : NestedScrollConnection {
            /** 松手判定/动画期间不再累计（否则回弹那段时间里 fling 的余量会把位移顶起来） */
            var settling = false

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (settling || blockedNow || available.y <= 0f) return Offset.Zero
                pullDownActive = true
                pullTravel = (pullTravel + available.y).coerceAtMost(maxTravelPx)
                pullArmed = pullTravel >= thresholdPx
                // 消费掉"网格没吃下"的这段：免得系统 overscroll 的拉伸和我们的位移叠在一起
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                settling = true
                val travel = pullTravel
                val byDistance = travel >= thresholdPx
                val byVelocity = travel >= flickMinPx && available.y >= PULL_DOWN_VELOCITY_THRESHOLD
                val committed = byDistance || byVelocity
                val startVisual = visualOf(travel)

                scope.launch {
                    // 先把动画值对齐到松手瞬间的位移，再撤掉"跟手"标志 —— 否则会从 0 跳一下
                    pullDownAnim.snapTo(startVisual)
                    pullDownActive = false
                    pullArmed = false
                    if (committed) {
                        // 整列平滑滑出屏幕（180ms），到位后再回桌面。
                        // 高度还没测量到时用一个保守的大值，保证"整块滑出去"的观感
                        pullDownAnim.animateTo(
                            contentHeightPx.takeIf { it > 0f } ?: 1200f,
                            tween(PULL_DOWN_ANIM_MS)
                        )
                        pullTravel = 0f
                        settling = false
                        commitPullDown()
                    } else {
                        // 没过阈值：平滑回弹
                        pullDownAnim.animateTo(0f, tween(PULL_DOWN_ANIM_MS))
                        pullTravel = 0f
                        settling = false
                    }
                }
                // 不消费速度：未提交时不该影响列表后续的惯性滚动
                return Velocity.Zero
            }
        }
    }

    // 浮层打开时把已经拉出来的位移收回去（半拉着的状态不该留在屏幕上）
    LaunchedEffect(blocked) {
        if (blocked) {
            pullDownActive = false
            pullTravel = 0f
            pullArmed = false
            pullDownAnim.snapTo(0f)
        }
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { contentHeightPx = it.height.toFloat() }
                // 下拉 → 返回桌面：观察"网格吃不下的那段"（见上面注释）
                .nestedScroll(connection)
                // ★ 跟手/滑出只改**绘制**：状态在 draw 阶段读，不触发每帧重组；
                //   放在嵌套滚动节点内侧，命中区域不跟着位移走。
                .graphicsLayer {
                    val pulled = if (pullDownActive) visualOf(pullTravel) else pullDownAnim.value
                    translationY = pulled
                    val p = if (contentHeightPx > 0f) (pulled / contentHeightPx).coerceIn(0f, 1f) else 0f
                    // 轻微淡出，做出"整块被拉走"的层次（不花哨，副屏上不掉帧）
                    alpha = 1f - 0.25f * p
                }
        ) {
            content()
        }

        // 提示条钉在最上方（**不跟随**内容平移）
        PullDownHint(
            visible = { hintVisible },
            armed = { pullArmed },
            pulledPx = { if (pullDownActive) visualOf(pullTravel) else pullDownAnim.value },
            thresholdVisualPx = thresholdVisualPx,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp)
        )
    }
}

/**
 * 「下拉返回桌面」的提示条（小胶囊，钉在顶部中心）。总应用列表 / 私密应用列表共用。
 *
 * **它必须"没在拉的时候完全不存在"**（用户实测 bug：顶部常驻一个像灵动岛的半透明小框 ✗）。
 * 这里三件事一起保证：
 *  1. `visible()` 为 false 时**直接不组合**任何东西（不是靠 alpha 隐藏）—— 最硬的保证；
 *  2. `graphicsLayer { alpha }` 排在 `clip` / `background` **之前** ⚠：
 *     `Modifier.background()` 画在自己那个节点上、位于 graphicsLayer **外面**，
 *     写成 `.background(..).graphicsLayer { alpha = 0f }` 时，底和圆角照样会被画出来
 *     （只有里面的文字被透明掉）—— 这正是那个"常驻小框"的成因；
 *  3. 不挂 `pointerInput` / `clickable`：显示期间也绝不吃列表的触摸（不遮挡）。
 *
 * 参数全部用 **lambda** 传（和 [AppListHeader] 同一套做法）：状态读取发生在**本函数的作用域**，
 * 于是跟手/动画期间只会重组这个小胶囊，不会把整屏（几百个格子）拖下水。
 * 其中 `pulledPx()` 只在 `graphicsLayer` 的 **draw 阶段**被调用 —— 位移每帧变也不会触发重组。
 */
@Composable
internal fun PullDownHint(
    /** 是否显示（未下拉 / 已收回时 false） */
    visible: () -> Boolean,
    /** 是否已过阈值（决定文案："松手返回桌面" / "下拉返回桌面"） */
    armed: () -> Boolean,
    /** 当前**屏幕上**的位移（已含阻尼；draw 阶段读） */
    pulledPx: () -> Float,
    /** 位移达到多少算"完全淡入"（= 阈值 × 阻尼） */
    thresholdVisualPx: Float,
    modifier: Modifier = Modifier
) {
    if (!visible()) return
    Box(
        modifier = modifier
            // ⚠ 必须在 clip/background 之前（见上面的注释 2）
            .graphicsLayer {
                val pulled = pulledPx()
                alpha = if (thresholdVisualPx > 0f) {
                    (pulled / thresholdVisualPx).coerceIn(0f, 1f)
                } else {
                    1f
                }
            }
            .clip(RoundedCornerShape(9.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (armed()) "松手返回桌面" else "下拉返回桌面",
            color = Accent.current,
            fontSize = 10.sp
        )
    }
}

/**
 * 顶栏（总应用列表 / 私密应用列表共用）。
 * `appCount` / `statusText` 都用 lambda 传入 ——
 * 这样它们的状态读取发生在**本作用域内**，状态变化只重组顶栏，
 * 不会把下面的网格一起重组（快速滑动时的卡顿主要就来自这里）。
 *
 * [overlayMode] = 是否处于常驻悬浮窗模式，**只影响刷新按钮的语义**：
 *  · true  → 图标 contentDescription 提"重挂悬浮窗并重扫应用"（按钮确实做两件事）；
 *  · false → 只有"刷新应用列表"（重扫），不提重挂（见 C2 的需求）。
 *
 * [title] / [onAdd] / [addActive] 是给**私密应用列表**用的（都有默认值，总应用列表不受影响）：
 * [onAdd] 非空时右侧多一个「＋」（私密界面用它进入"添加私密应用"的选择态），
 * [addActive] = true 时那颗按钮显示成「✓」（再点一下结束选择）。
 */
@Composable
internal fun AppListHeader(
    appCount: () -> Int,
    statusText: () -> String,
    loading: Boolean,
    overlayMode: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenConsole: () -> Unit,
    title: String = "应用列表",
    onAdd: (() -> Unit)? = null,
    addActive: Boolean = false
) {
    val text = statusText()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (text.isNotEmpty()) text else if (loading) "加载中…" else "点击＝送到副屏 · 长按＝菜单",
            // 状态/失败提示跟随「风格（强调色）」，不再是写死的手电筒黄
            color = if (text.isNotEmpty()) Accent.current else Color.White.copy(alpha = 0.4f),
            fontSize = 10.sp,
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        Text("${appCount()} 个", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
        if (onAdd != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    // 选择态里同一颗按钮变成「✓」= 结束添加
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (addActive) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = if (addActive) "结束添加" else "添加私密应用",
                    tint = if (addActive) Accent.current else Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onRefresh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                // 非悬浮窗模式下这个按钮只重扫应用，不能再自称"重挂悬浮窗"（C2）
                contentDescription = if (overlayMode) "重挂悬浮窗并重扫应用" else "刷新应用列表",
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenConsole),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "副屏控制台",
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

/**
 * 格子「点击 / 长按」的**唯一仲裁入口**（总应用列表、私密应用、文件夹界面都走它）。
 *
 * ⚠ 这里修的是实测 bug（**私密应用界面里长按某些应用不弹菜单、反而启动了应用 ✗**，
 * 而普通总应用列表看着正常 —— 三个界面共用本逻辑，所以修在这里、一起好）：
 *
 * 机制：`combinedClickable` 的长按靠"按住不动 500ms"的计时器，只要这次按压在计时期间
 * 被**上层（网格 / Pager 的滚动、父级 nestedScroll 的消费）打断或消费**，长按**不会回调**，
 * 但松手时仍可能被判成 `onClick` → **直接启动应用** ✗。
 * 下面的观察者在 **Initial 阶段只观察、不消费**（不抢 `combinedClickable` 的事件、
 * 也不影响滚动），记录「按住时长 + 是否越过 touchSlop」：
 *
 *   · 松手时若 **够久(≥450ms) + 几乎没动(< touchSlop) + 长按从没回调过**
 *     → **补一次 [onLongClick]**（菜单一定会出来 ✓）；
 *   · `onClick` 里只要"本次按压长按过"（含上面的兜底）就 **吞掉这次 click**（**绝不启动应用** ✓）。
 *
 * @param key         按下时长观察者的 key（一般传包名）：换应用时重启观察者
 * @param logTag      日志前缀（"AppList" / "Folder"…），真机上据此核对"长按后没有启动"
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.appItemGestures(
    key: String,
    logTag: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    // 「这一次按压有没有长按过」
    val longPressFired = remember { mutableStateOf(false) }
    return this
        .pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // 每按一次就复位：本次按压还没长按过
                longPressFired.value = false
                var maxMove = 0f
                var up: PointerInputChange? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    maxMove = maxOf(maxMove, (change.position - down.position).getDistance())
                    if (!change.pressed) {
                        up = change
                        break
                    }
                }
                val held = (up?.uptimeMillis ?: SystemClock.uptimeMillis()) - down.uptimeMillis
                // ★ 长按兜底：够久 + 没滑动 + combinedClickable 没回调过长按 → 我们补一次
                if (!longPressFired.value &&
                    held >= LONG_PRESS_FALLBACK_MS &&
                    maxMove < viewConfiguration.touchSlop
                ) {
                    longPressFired.value = true
                    CoverDebugLog.log(
                        logTag,
                        "长按兜底触发（combinedClickable 未回调）：$key 按住=${held}ms 位移=${maxMove.toInt()}px"
                    )
                    onLongClick()
                }
            }
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = {
                // ★ 长按过（含兜底）→ 这次 click 一定是"长按松手"带出来的 → 吞掉，绝不启动 ✗
                if (longPressFired.value) {
                    CoverDebugLog.log(logTag, "吞掉长按后的 click：$key")
                } else {
                    onClick()
                }
            },
            onLongClick = {
                longPressFired.value = true
                onLongClick()
            }
        )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppCell(
    app: AppEntry,
    preferShizuku: Boolean,
    inDesktop: Boolean,
    onClick: (AppEntry) -> Unit,
    onLongClick: (AppEntry) -> Unit
) {
    val shape = remember { RoundedCornerShape(10.dp) }

    Column(
        modifier = Modifier
            .clip(shape)
            // 点击/长按 + 长按兜底（见 [appItemGestures] 的注释）
            .appItemGestures(
                key = app.packageName,
                logTag = "AppList",
                onClick = { onClick(app) },
                onLongClick = { onLongClick(app) }
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            val icon = app.icon
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = app.label,
                    modifier = Modifier.size(40.dp).clip(shape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(shape)
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Android,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            // 已在桌面上的标记
            if (inDesktop) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(9.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF4CD964))
                )
            }
            // 「始终用 Shizuku 搬栈」标记
            if (preferShizuku) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(9.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(TorchAmber)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = app.label,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 查询所有可启动应用（主界面 Activity），按名称排序、去重、排除自身。
 *
 * 图标解码尺寸取 96px：网格里按 40dp 显示（本机 200dpi → 50px），
 * 桌面大图标按 ~90dp 显示（→ 112px），96px 是两者的折中，
 * 既够清晰又不至于让几百个图标把内存撑爆（96×96 ARGB ≈ 36KB/个）。
 */
internal fun queryLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved: List<ResolveInfo> = try {
        pm.queryIntentActivities(intent, 0)
    } catch (t: Throwable) {
        emptyList()
    }

    val seen = HashSet<String>()
    val collator = Collator.getInstance(Locale.CHINA)

    return resolved.asSequence()
        .filter { it.activityInfo != null }
        .filter { it.activityInfo.packageName != context.packageName }
        .filter { seen.add(it.activityInfo.packageName) }
        .map { info ->
            val label = try {
                info.loadLabel(pm).toString()
            } catch (t: Throwable) {
                info.activityInfo.packageName
            }
            val icon = try {
                info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            } catch (t: Throwable) {
                null
            }
            AppEntry(label = label, packageName = info.activityInfo.packageName, icon = icon)
        }
        .sortedWith(compareBy(collator) { it.label })
        .toList()
}

// ---------------------------------------------------------------------------
// 长按菜单的「卸载该应用」
// ---------------------------------------------------------------------------

/** 卸载动作的结果 */
internal sealed class UninstallResult {
    /** Shizuku 静默卸载成功（用户没看到任何系统弹窗） */
    object Silent : UninstallResult()

    /** 没有 Shizuku：已拉起系统的卸载确认弹窗，成败由用户在弹窗里决定 */
    object Dispatched : UninstallResult()

    /** 失败（reason 直接给用户看） */
    data class Failed(val reason: String) : UninstallResult()
}

/**
 * 卸载一个应用。
 *
 * 两条路：
 *  ① **有 Shizuku** → `pm uninstall --user 0 <pkg>`（shell(uid 2000) 身份，静默、成功率高，
 *     而且不会弹系统确认框 —— 副屏上弹框很容易被 vivo 弹到没有面板的主屏上去）；
 *  ② **没有 Shizuku** → `Intent(ACTION_DELETE, Uri.parse("package:<pkg>"))` 走系统卸载确认弹窗。
 *     ⚠ 用 ACTION_DELETE，**不能用已废弃的 ACTION_UNINSTALL_PACKAGE**（Android 8 起废弃）。
 *     这个 Intent 走 [IntentLauncher] 带 displayId 启动，尽量落在副屏上。
 *
 * 本应用**自己**不允许卸载：卸载掉启动器 = 这台设备再也没有正常桌面入口。
 */
internal suspend fun uninstallApp(
    context: Context,
    packageName: String,
    coverDisplayId: Int? = null
): UninstallResult {
    if (packageName == context.packageName) {
        return UninstallResult.Failed("不能卸载本应用")
    }

    // ① Shizuku 静默卸载（exec 内部已经切到 IO 线程）
    if (ShizukuShell.isUsable) {
        val out = ShizukuShell.exec("pm uninstall --user 0 $packageName")
        if (out != null && out.contains("Success")) return UninstallResult.Silent
        val detail = out?.trim()?.takeIf { it.isNotEmpty() } ?: "Shizuku 通道无回显"
        return UninstallResult.Failed(detail.take(120))
    }

    // ② 退回系统卸载确认弹窗（startActivity 必须在主线程上发起）
    return withContext(Dispatchers.Main) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
        if (IntentLauncher.launch(context, intent, coverDisplayId)) {
            UninstallResult.Dispatched
        } else {
            UninstallResult.Failed("没有 Shizuku，且系统卸载界面打不开")
        }
    }
}

/**
 * 把应用送到副屏（总应用列表 / 私密应用列表**共用**）。
 *
 * @return 给用户看的**状态文字**（失败时同时弹一次 CoverToast）
 */
internal suspend fun launchAppToCover(
    context: Context,
    label: String,
    packageName: String,
    coverDisplayId: Int
): String {
    val result = CoverAppLauncher.launchPackageToCover(
        context = context,
        packageName = packageName,
        coverDisplayId = coverDisplayId,
        preferShizuku = CoverSettings.isPreferShizuku(context, packageName)
    )
    return when (result) {
        is CoverAppLauncher.Result.Success ->
            "$label → 副屏（${result.via}${if (result.verified) "·已校验" else "·未校验"}）"
        is CoverAppLauncher.Result.Failure -> {
            showToast(context, result.reason)
            "$label 失败：${result.reason}"
        }
    }
}

/**
 * 顶部搜索框（按应用名或包名过滤）。总应用列表 / 私密应用列表共用。
 *
 * 做成独立 composable 而不是两份拷贝：两个界面的搜索行为要**完全一致**
 * （副屏很窄，样式/留白一旦分叉就会有一边溢出）。
 */
@Composable
internal fun AppListSearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    androidx.compose.foundation.text.BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = Color.White,
            fontSize = 12.sp
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Text
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner ->
            if (query.isEmpty()) {
                Text("搜索应用", color = Color.White.copy(alpha = 0.35f), fontSize = 12.sp)
            }
            inner()
        }
    )
}
