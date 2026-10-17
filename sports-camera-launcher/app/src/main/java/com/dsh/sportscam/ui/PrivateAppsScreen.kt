package com.dsh.sportscam.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.sportscam.cover.CoverDebugLog
import com.dsh.sportscam.cover.CoverDisplay
import com.dsh.sportscam.cover.CoverSettings
import com.dsh.sportscam.desktop.DesktopItem
import com.dsh.sportscam.desktop.DesktopStore
import com.dsh.sportscam.ui.common.CompactMenuDialog
import com.dsh.sportscam.ui.common.PrivateAccessGate
import com.dsh.sportscam.ui.common.showToast
import com.dsh.sportscam.ui.theme.Accent
import com.dsh.sportscam.ui.theme.SportsCamBlack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===========================================================================
// 「私密应用」集合：读 / 写 的唯一入口（两个列表都走这里）
//
// 为什么还要包一层：`CoverSettings` 里存的是 **SharedPreferences**，它不是 Compose 状态 ——
// 光读 prefs 的话，"在私密界面里加了一个"不会让**已经组合着**的总应用列表重组 ✗。
// 所以这里加一个进程内的 `version` 版本号：本文件里加/减私密应用后自增，
// 两个界面在组合期读一下版本号 → 加/减完立刻重组、过滤实时生效 ✓
// （与 DesktopStore.versionState 同一套思路）。
//
// ⚠ 用哪个集合（CoverSettings 的注释写得很明确）：
//   · [privateAppsEffective] = `CoverSettings.privateAppPackages()`（等分别名 `privateApps()`）
//     —— **生效值**，功能总开关关掉就是空集；总应用列表的过滤、桌面隐藏用它；
//   · [privateAppsManaged]   = `CoverSettings.privateAppsPreferred()` —— **原始值**，不看总开关；
//     本管理界面（列私密应用 / 勾选添加）用它，这样总开关关掉时仍然能管理那些标记。
// ===========================================================================

/** 私密集合的进程内版本号（加/减后自增 → 相关界面重组） */
private val privateAppsVersion = mutableIntStateOf(0)

/** **生效**的私密包名集合（列表过滤 / 桌面隐藏用）。在组合期读 → 集合一变就重组 */
internal fun privateAppsEffective(context: Context): Set<String> {
    privateAppsVersion.intValue
    return CoverSettings.privateAppPackages(context)
}

/** **原始**私密包名集合（管理界面用，不看总开关）。在组合期读 → 集合一变就重组 */
internal fun privateAppsManaged(context: Context): Set<String> {
    privateAppsVersion.intValue
    return CoverSettings.privateAppsPreferred(context)
}

/** 标记 / 取消标记一个私密应用（写 prefs + 自增版本号） */
internal fun setAppPrivate(context: Context, packageName: String, isPrivate: Boolean) {
    if (isPrivate) {
        CoverSettings.addPrivateApp(context, packageName)
    } else {
        CoverSettings.removePrivateApp(context, packageName)
    }
    notifyPrivateAppsChanged()
}

/**
 * **别处**（例如控制台的「选择私密应用」浮层）直接改过 `CoverSettings` 里的私密集合后，
 * 调一下这个，让**已经组合着**的总应用列表/私密列表立刻重组、过滤实时生效。
 * （本文件内的 [setAppPrivate] 已经会调它。）
 */
internal fun notifyPrivateAppsChanged() {
    privateAppsVersion.intValue++
}

/**
 * 「私密应用」界面 —— 外观与交互**和总应用列表一模一样**（同一个 [AppListHeader] /
 * [AppCell] / [AppListSearchField] / [PullDownToDesktopBox] / [CompactMenuDialog]），
 * 差别只有四处：
 *
 *  1. **进入必须输密码**：整屏包在 settings-console 的 [PrivateAccessGate] 里 ——
 *     每次进入都重新问（界面一销毁、下次进来是新的组合），没设过密码则只给"去控制台设密码"的引导、
 *     **不放行**；浮层是同树浮层，不是系统 Dialog（2038 悬浮窗里弹 Dialog 会崩进程）。
 *  2. 主列表列出**已标记为私密**的应用（`CoverSettings.privateAppsPreferred`）——这些应用不出现在
 *     普通总应用列表与桌面里（列表侧过滤在 [AppListScreen]，桌面侧由 desktop-ui 负责）。
 *  3. 右上角「＋」= 进入**添加选择态**：列出当前未标记的应用，点一下即加入；那颗按钮变成「✓」。
 *     长按菜单里**没有**「添加到桌面」，换成「移除私密应用」（其余项照旧：Shizuku 搬栈 / 打开到副屏 / 卸载）。
 *  4. **下拉返回桌面**（与总应用列表同一套 [PullDownToDesktopBox]，阈值/动画/提示条完全一致）。
 *     ⚠ 也刻意**不注册任何"上拉"手势**：用户明确要求"在私密界面里再上拉不应回到普通列表"；
 *     上拉只属于桌面（[SquareHomeScreen]）。
 */
@Composable
fun PrivateAppsScreen(
    coverDisplayId: Int,
    uiState: AppListUiState,
    onBack: () -> Unit,
    onOpenConsole: () -> Unit,
    /**
     * **下拉返回桌面**。默认复用 [onBack]（上层 MainActivity 里就是 `pop`）；
     * 若本界面可能压在别的页面之上，建议上层显式传"落到 Screen.Desktop"的 lambda。
     */
    onPullDownToDesktop: () -> Unit = onBack,
    /**
     * 「在桌面创建一个锁图标入口」的**覆盖**回调（默认 null = 用内置实现）。
     *
     * 内置实现直接调 desktop-ui 的 `DesktopStore.addPrivateAppsEntry(context, portrait)`
     * （把 `DesktopItem.PrivateAppsEntry` 放进桌面第一个空位：成功 true / 桌面满 false）。
     * 上层若要额外做点什么（提示文案、别的落点策略），传一个 lambda 覆盖即可。
     */
    onCreateDesktopEntry: (() -> Unit)? = null,
    /**
     * **是否跳过界面内的密码门**（默认 false = 每次进入都由本界面弹密码）。
     *
     * 本界面默认自己在最外层套 [PrivateAccessGate]：**无论从哪条路进来都必弹一次**
     * （桌面锁图标 / 控制台 / 任何入口都不用各自再管）。
     * 若上层已经在**导航入口处**无条件弹过一次密码（`guardedPush` 那种），
     * 请传 `skipAccessGate = true`，免得用户连着输两遍 ✗。
     */
    skipAccessGate: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 已加载过就不再重新扫描（与总应用列表共用同一份缓存 → 进来就秒开）
    LaunchedEffect(uiState.reloadTick) {
        if (uiState.loaded && uiState.reloadTick == 0) return@LaunchedEffect
        uiState.loading = true
        uiState.apps = withContext(Dispatchers.IO) { queryLaunchableApps(context) }
        uiState.loaded = true
        uiState.loading = false
    }

    // ⚠ **每次重组都重新读**（本文件顶部那个版本号保证"加/减完立刻重组"）
    val managedSet = privateAppsManaged(context)

    var status by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<AppEntry?>(null) }
    var preferTick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    val preferSet = remember(preferTick) { CoverSettings.preferShizukuApps(context) }
    val desktopVersion = DesktopStore.versionState().intValue
    val desktopSet = remember(desktopVersion) {
        DesktopStore.items(context, CoverDisplay.isPortraitNow(context))
            .filterIsInstance<DesktopItem.App>()
            .map { it.packageName }.toSet()
    }

    // 主列表 = 已标记私密的应用；选择态 = 还没标记的应用（点一下即加入）
    val sourceApps = remember(uiState.apps, managedSet, adding) {
        if (adding) {
            uiState.apps.filterNot { it.packageName in managedSet }
        } else {
            uiState.apps.filter { it.packageName in managedSet }
        }
    }
    val shownApps = remember(query, sourceApps) {
        val q = query.trim()
        if (q.isEmpty()) {
            sourceApps
        } else {
            sourceApps.filter {
                it.label.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true)
            }
        }
    }

    val onItemClick: (AppEntry) -> Unit = remember(coverDisplayId, adding) {
        { app ->
            if (adding) {
                // 选择态：点一下即加入（加入后它从待选列表消失，普通列表/桌面也不再出现）
                setAppPrivate(context, app.packageName, true)
                status = "已加入私密：${app.label}"
                CoverDebugLog.log("PrivateApps", "点击（添加选择态）→ 加入私密：${app.packageName}")
            } else {
                // 浏览态：点一下 = 送到副屏。
                // ⚠ 长按"松手带出来的 click"在 AppCell 里就被吞掉了（见那里的长按兜底注释），
                //   所以这里只会收到**真正的单击**；这条日志就是"长按后不该出现启动"的判据：
                //   真机上长按某个应用后，日志里**不该**出现这行 + CoverAppLauncher 的 [Launch]。
                CoverDebugLog.log("PrivateApps", "单击 → 启动：${app.packageName}")
                scope.launch {
                    status = "正在送到副屏：${app.label}…"
                    status = launchAppToCover(context, app.label, app.packageName, coverDisplayId)
                }
            }
        }
    }
    // 选择态里长按不弹管理菜单（那几项此刻没有意义），避免误操作 —— 但给个明白的提示，
    // 别让用户以为"长按没反应"（长按松手带出来的 click 同样会被 AppCell 吞掉，不会误加）。
    val onItemLongClick: (AppEntry) -> Unit = remember(adding) {
        { app ->
            if (adding) {
                status = "添加选择态：点一下即加入（右上角 ✓ 结束）"
                CoverDebugLog.log("PrivateApps", "长按（添加选择态，按设计不弹菜单）：${app.packageName}")
            } else {
                CoverDebugLog.log("PrivateApps", "长按 → 打开管理菜单：${app.packageName}")
                menuTarget = app
            }
        }
    }

    // 真正的内容（"界面内密码门"和"入口已弹过密码"两种情况共用同一份）
    val content: @Composable () -> Unit = {
        PullDownToDesktopBox(
            onCommit = onPullDownToDesktop,
            blocked = menuTarget != null,
            modifier = Modifier
                .fillMaxSize()
                .background(SportsCamBlack)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                AppListHeader(
                    appCount = { shownApps.size },
                    statusText = { status },
                    loading = uiState.loading,
                    // 私密界面里的刷新只做"重扫应用"，不提重挂悬浮窗（与它的实际动作一致）
                    overlayMode = false,
                    onBack = onBack,
                    onRefresh = {
                        uiState.reloadTick++
                        status = "重新扫描中…"
                    },
                    onOpenConsole = onOpenConsole,
                    title = "私密应用",
                    onAdd = {
                        adding = !adding
                        query = ""
                        status = if (adding) "点一下应用即加入私密（右上角 ✓ 结束）" else ""
                    },
                    addActive = adding
                )

                AppListSearchField(query = query, onQueryChange = { query = it })

                // ---------------- 桌面入口（锁图标） ----------------
                // 只在浏览态显示（选择态里别添乱）。桌面侧那个项由 desktop-ui 的桌面模型负责，
                // 这里只是"按钮 + 调用点"。
                if (!adding) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable {
                                val override = onCreateDesktopEntry
                                if (override != null) {
                                    override()
                                } else {
                                    // desktop-ui 的实现：把 PrivateAppsEntry 放进桌面第一个空位
                                    val ok = DesktopStore.addPrivateAppsEntry(
                                        context,
                                        CoverDisplay.isPortraitNow(context)
                                    )
                                    showToast(
                                        context,
                                        if (ok) "已在桌面添加私密入口" else "桌面已经有这个入口了"
                                    )
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Accent.current,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "在桌面创建一个锁图标入口",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 10.sp
                        )
                    }
                }

                when {
                    uiState.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
                    }
                    shownApps.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (adding) {
                                "没有可以添加的应用了"
                            } else {
                                "还没有私密应用：点右上角「＋」添加"
                            },
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )
                    }
                    else -> LazyVerticalGrid(
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
        }

        // ---------------- 长按菜单（与总应用列表一致，只有第一项不同） ----------------
        menuTarget?.let { app ->
            CompactMenuDialog(
                title = app.label,
                subtitle = app.packageName,
                onDismiss = { menuTarget = null },
                actions = listOf(
                    // ★ 与普通列表的差异：没有"添加到桌面"，换成"移除私密应用"
                    "移除私密应用" to {
                        setAppPrivate(context, app.packageName, false)
                        status = "已移出私密：${app.label}"
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
                    // 卸载：与总应用列表同一套（Shizuku 静默 → 退系统确认弹窗；禁止卸载本应用）
                    "卸载该应用" to {
                        menuTarget = null
                        val pkg = app.packageName
                        val label = app.label
                        scope.launch {
                            status = "正在卸载：$label…"
                            when (val r = uninstallApp(context, pkg, coverDisplayId)) {
                                is UninstallResult.Silent -> {
                                    uiState.apps = uiState.apps.filterNot { it.packageName == pkg }
                                    // 桌面上若还摆着它的图标 → 顺手清掉（和总应用列表一样）
                                    DesktopStore.removeApp(context, pkg)
                                    // 它若被标记过私密，一起摘掉，免得留一个卸载不掉的幽灵包名
                                    setAppPrivate(context, pkg, false)
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

    if (skipAccessGate) {
        // 上层已经在导航入口处无条件弹过一次了 → 这里直接渲染，不再问第二遍
        content()
    } else {
        // ★ 每次进入都要重新输密码（PrivateAccessGate 内部保证：没密码不放行、同树浮层、
        //   不做"一次授权永久有效"）—— 放在**界面内**，所以任何入口进来都必弹一次 ✓
        PrivateAccessGate(
            onExit = onBack,
            subtitle = "每次进入私密应用都要输入启动器密码",
            // 没设过密码时，引导浮层上给一个「去设置密码」按钮 → 直接进控制台
            // （用户不用自己找路；控制台里那个总开关关着时不会重复问密码）。
            onNeedSetup = { onOpenConsole() }
        ) {
            content()
        }
    }
}
