package com.dsh.sportscam.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.dsh.sportscam.cover.CoverDebugLog
import com.dsh.sportscam.cover.rememberCoverDisplayId
import com.dsh.sportscam.desktop.DesktopStore
import com.dsh.sportscam.ui.common.CompactMenuDialog
import com.dsh.sportscam.ui.common.showToast
import com.dsh.sportscam.ui.theme.Accent
import com.dsh.sportscam.ui.theme.SportsCamSurface
import com.dsh.sportscam.ui.theme.SportsCamSurfaceAlt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 文件夹每页的应用数 = 2×2（与 `DesktopStore.folderPages` 的内部约定一致：每页 4 个） */
private const val FOLDER_PAGE_SIZE = 4

/** 文件夹名长度上限（窗口标题显示，太长会溢出；desktop-ui 落库时还会再 sanitize 一次） */
private const val FOLDER_NAME_MAX_LEN = 12

/**
 * 正方形窗口边长 = `min(可用宽, 可用高) × 这个比例`。
 *
 * 取 0.82：副屏短边 ≈338dp → 边长 ≈277dp（在 260~290dp 的建议区间内），
 * 面积约占界面 42%（≈一半）、且四周还能留出 28dp 以上的"点外面关闭"遮罩。
 */
private const val FOLDER_SIDE_FRACTION = 0.82f

/** 边长夹取范围（避免超小/超大屏上窗口过大或过小；副屏算出 277dp 落在区间中间） */
private val FOLDER_SIDE_MIN = 220.dp
private val FOLDER_SIDE_MAX = 320.dp

/** 窗口四周**至少**留出的遮罩宽度（两边合计 2× 它；"点窗口外关闭"要够好点） */
private val FOLDER_SCRIM_MIN = 28.dp

/**
 * 「文件夹」界面 —— 覆盖在桌面之上的**同树浮层**（不是系统 Dialog：悬浮窗 2038 里弹
 * `Dialog`/`AlertDialog` 会因窗口类型冲突**崩进程**，公共约定第 1 条）。
 *
 * 结构：
 *  · 全屏半透明遮罩：**点空白处 = 关闭**（窗口内部会先消费掉自己的点击，不会误关）；
 *  · 中间一个圆角窗口（标题栏 + 2×2 内容区 + 底部圆点指示器）：
 *      - 标题 = 文件夹名，**长按标题**或点右侧 ✎ 可重命名（同树浮层里的小输入面板）；
 *      - 内容 = 当前页的 4 个应用图标（2×2），**左右滑动切页**（HorizontalPager），
 *        页数 = ceil(应用数 / 4) ⇒ 超过 4 个自动多一页 ✓；
 *      - 点图标 = 启动到副屏；长按图标 = 菜单（「从文件夹移除」/「打开到副屏」）；
 *      - 空文件夹 → 「空文件夹：到编辑模式把应用拖进来」；
 *  · 返回键同样关闭（与桌面上的返回一致）。
 *
 * ⚠ 图标单元格子用共享的 [appItemGestures]：长按一定会弹菜单、且长按之后**绝不会**
 * 被当成点击而启动应用（那是第 8 项修过的 bug，文件夹这边不能再犯）。
 */
@Composable
fun FolderScreen(
    folderId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coverDisplayId by rememberCoverDisplayId()
    val scope = rememberCoroutineScope()

    // ---------------- 数据 ----------------
    // ★ 唯一真相 = desktop-ui 的 `DesktopStore`（文件夹内容存在桌面项自身里），本界面**不另存一份** ✗。
    //   先读一次版本号：桌面任何一次 save() 都会自增它 ⇒
    //   "在桌面侧把应用拖进文件夹 / 本界面移除了应用 / 改了名字"都会让本界面立刻重组、实时反映。
    val desktopVersion = DesktopStore.versionState().intValue

    // ★ 私密应用变更**不一定**会让 DesktopStore.versionState() 自增（那是桌面保存事件，不是设置事件），
    //   所以额外订阅"私密集合的进程内版本号"：`privateAppsEffective()` 内部读了它 ⇒ 在组合期调用即订阅 ✓
    //   （这里**只用它做订阅**，过滤本身由桌面侧的 folderApps() 负责 —— 不重复过滤 ✓）
    val privateAppsSignal = privateAppsEffective(context)

    // 进入界面时再主动重读一次（防御性）：保证"先在别处把应用设为私密、再打开文件夹"时
    // 拿到的也是最新内容，而不是任何一层可能残留的旧值。
    var entryTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { entryTick++ }

    val folderNameRaw = remember(desktopVersion, folderId) {
        DesktopStore.folderName(context, folderId)
    }
    val folderName = folderNameRaw.ifBlank { "文件夹" }

    // ⚠ 页码/内容一律用 `folderApps()` 的结果（**桌面侧已经过滤掉私密应用**）：
    //   · 不在本界面再过滤一遍（单一真相在桌面侧，重复过滤容易口径不一致）；
    //   · 页数也用它的 size 算 ⇒ 私密应用一隐藏，页数/圆点自动收缩，**不会多出空页**。
    val packages = remember(desktopVersion, privateAppsSignal, entryTick, folderId) {
        DesktopStore.folderApps(context, folderId)
    }

    // 文件夹已不存在（比如在桌面编辑模式里把整个文件夹删了）→ 自动退出，
    // 别停在一个已经不存在的文件夹界面上。
    LaunchedEffect(folderNameRaw.isBlank()) {
        if (folderNameRaw.isBlank()) onBack()
    }

    // 包名 → (名称, 图标)：只查文件夹里这几个，很便宜；放 IO 线程避免卡首帧。
    // 返回 List<AppEntry?>：**保持与 packages 一一对应**，某个包已卸载时给 null（界面上就是空槽），
    // 这样不会因为"少了一个应用"导致整页往后错位。
    val entries by produceState(initialValue = emptyList<AppEntry?>(), key1 = packages) {
        value = withContext(Dispatchers.IO) { loadFolderEntries(context, packages) }
    }

    // 页数：用 desktop-ui 的同一套算法，且按**文件夹里的包名数**算（不是"能解析出来的图标数"）
    // ⇒ 与桌面侧显示/翻页口径一致；为空也至少 1 页，用来显示空态提示。
    val pageCount = DesktopStore.folderPages(packages.size)
    val pagerState = rememberPagerState { pageCount }

    var renaming by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<AppEntry?>(null) }
    var status by remember { mutableStateOf("") }

    // 移除应用后页数可能变少 → 把当前页夹回合法范围（否则会停在已被删掉的空页上）
    LaunchedEffect(pageCount) {
        if (pagerState.currentPage > pageCount - 1) pagerState.scrollToPage(pageCount - 1)
    }

    // 返回键也关闭（悬浮窗宿主 CoverOverlayWindow 已提供 dispatcher owner；这里只在存在时挂，
    // 避免在没有 owner 的宿主里抛异常）
    val backOwner = LocalOnBackPressedDispatcherOwner.current
    if (backOwner != null) {
        BackHandler { onBack() }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // ★ 点窗口外 = 关闭。窗口内部会先消费掉自己的 down，
            //   所以这里 `awaitFirstDown()` 收不到窗口内的点击（Main 阶段是内层先消费）。
            .pointerInput(Unit) { detectTapGestures { onBack() } }
    ) {
        // ===================================================================
        // 正方形窗口（用户要求：做大 + 正方形 + 约占界面一半，和密码面板一样大好点）
        //
        // 边长 = min(可用宽, 可用高) × 0.82，再夹到 [220, 320]dp、并保证四周至少留 28dp 遮罩
        // （"点外面关闭"要好点，别占满）。
        //   副屏 682×422px ≈ 546×338dp → 边长会算成 ≈277dp：
        //     · 横屏：左右各留 (546-277)/2 ≈ 134dp，上下各留 (338-277)/2 ≈ 30dp；
        //     · 竖屏：左右各留 30dp，上下各留 134dp —— **横竖屏窗口一样大**，都不会溢出 ✓；
        //     · 面积 ≈ 277² / (546×338) ≈ 42%，比原来的 300×210 明显大、且是正方形；
        //     · 4 个格子从约 145×70dp 变成约 138×105dp（图标 44→58dp）⇒ 好点多了 ✓。
        // ===================================================================
        val side = (minOf(maxWidth, maxHeight) * FOLDER_SIDE_FRACTION)
            .coerceIn(FOLDER_SIDE_MIN, FOLDER_SIDE_MAX)
            .coerceAtMost(maxWidth - FOLDER_SCRIM_MIN * 2)
            .coerceAtMost(maxHeight - FOLDER_SCRIM_MIN * 2)
            .coerceAtLeast(160.dp)   // 极端小窗口下的兜底（正常副屏不会走到）

        // 内部尺寸都跟着边长走（如标题栏高、按钮大小、图标大小），换屏幕也等比缩放
        val titleBarHeight = side * 0.13f
        val titleFontSize = (side.value * 0.054f).sp   // 277dp → ≈15sp
        val actionButtonSize = side * 0.12f            // 277dp → ≈33dp
        val bigIconSize = side * 0.21f                 // 277dp → ≈58dp
        val bigLabelFontSize = (side.value * 0.04f).sp // 277dp → ≈11sp

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .size(side)
                .clip(RoundedCornerShape(18.dp))
                .background(SportsCamSurface)
                // 窗口自己吃掉点击（否则点窗口空白处会穿透到遮罩 = 误关）
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(bottom = 6.dp)
        ) {
            // ---------------- 标题栏 ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(titleBarHeight)
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = folderName,
                    color = Color.White,
                    fontSize = titleFontSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        // 长按标题也能重命名（与右侧 ✎ 等价，两个入口都留着）
                        .pointerInput(folderName) {
                            detectTapGestures(onLongPress = { renaming = true })
                        }
                )
                Spacer(Modifier.width(4.dp))
                // ✎ 重命名
                Box(
                    modifier = Modifier
                        .size(actionButtonSize)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { renaming = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "重命名文件夹",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(actionButtonSize * 0.5f)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (pageCount > 1) "${pagerState.currentPage + 1}/$pageCount" else "",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = bigLabelFontSize
                )
                Spacer(Modifier.width(4.dp))
                // × 关闭（与"点外面"等价，方便用户明确看到出口）
                Box(
                    modifier = Modifier
                        .size(actionButtonSize)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭文件夹",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(actionButtonSize * 0.54f)
                    )
                }
            }

            // "空文件夹"按**文件夹里的包名数**判断（不是"能解析出图标的数量"：
            // 全都被卸载时应显示 2×2 的空槽，而不是伪装成空文件夹）。
            if (packages.isEmpty()) {
                // ---------------- 空文件夹 ----------------
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "空文件夹：到编辑模式把应用拖进来",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = bigLabelFontSize
                    )
                }
            } else {
                // ---------------- 2×2 内容（左右滑切页） ----------------
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    // 只有一页时不需要拖动（避免无意义的滑动手感）
                    userScrollEnabled = pageCount > 1
                ) { page ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        for (row in 0 until 2) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                for (col in 0 until 2) {
                                    val index = page * FOLDER_PAGE_SIZE + row * 2 + col
                                    val app = entries.getOrNull(index)
                                    if (app != null) {
                                        FolderAppCell(
                                            app = app,
                                            modifier = Modifier.weight(1f),
                                            iconSize = bigIconSize,
                                            labelFontSize = bigLabelFontSize,
                                            onClick = {
                                                CoverDebugLog.log("Folder", "单击 → 启动：${app.packageName}")
                                                status = "正在送到副屏：${app.label}…"
                                                scope.launch {
                                                    status = launchAppToCover(
                                                        context, app.label, app.packageName, coverDisplayId
                                                    )
                                                }
                                            },
                                            onLongClick = {
                                                CoverDebugLog.log("Folder", "长按 → 菜单：${app.packageName}")
                                                menuTarget = app
                                            }
                                        )
                                    } else {
                                        // 空槽：占位保持 2×2 网格形状
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                // ---------------- 底部圆点指示器 ----------------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(pageCount) { i ->
                        val selected = i == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (selected) 7.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) Accent.current else Color.White.copy(alpha = 0.25f)
                                )
                        )
                    }
                }
            }

            // 状态文字（启动/移除的反馈，占位很低，不打扰）
            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    color = Accent.current.copy(alpha = 0.85f),
                    fontSize = bigLabelFontSize * 0.9f,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 2.dp)
                )
            }
        }
    }

    // ---------------- 长按菜单（复用共享的 CompactMenuDialog） ----------------
    menuTarget?.let { app ->
        CompactMenuDialog(
            title = app.label,
            subtitle = app.packageName,
            onDismiss = { menuTarget = null },
            actions = listOf(
                "从文件夹移除" to {
                    // desktop-ui 的语义：移除的同时把这个应用**放回桌面空位** ✓
                    // （所以文案是"已放回桌面"，比"已移除"更准确）
                    val ok = DesktopStore.removeFromFolder(context, folderId, app.packageName)
                    status = if (ok) "已放回桌面：${app.label}" else "移除失败：应用不在文件夹里"
                    CoverDebugLog.log("Folder", "从文件夹移除（放回桌面）：${app.packageName} ok=$ok")
                    if (ok) showToast(context, "已放回桌面：${app.label}")
                    menuTarget = null
                },
                "打开到副屏" to {
                    menuTarget = null
                    status = "正在送到副屏：${app.label}…"
                    scope.launch {
                        status = launchAppToCover(context, app.label, app.packageName, coverDisplayId)
                    }
                }
            )
        )
    }

    // ---------------- 重命名面板（同树浮层，绝不用系统 Dialog） ----------------
    if (renaming) {
        RenameFolderOverlay(
            initial = folderName,
            onConfirm = { newName ->
                val ok = DesktopStore.renameFolder(context, folderId, newName)
                status = if (ok) {
                    "已重命名为：${DesktopStore.folderName(context, folderId)}"
                } else {
                    "重命名失败：文件夹不存在"
                }
                CoverDebugLog.log("Folder", "重命名文件夹 $folderId → $newName ok=$ok")
                renaming = false
            },
            onDismiss = { renaming = false }
        )
    }
}

/**
 * 文件夹里的一个应用格子（**跟着正方形窗口放大**：图标 ≈58dp、名字 ≈11sp，
 * 比总应用列表的 40dp 图标大一圈 —— 用户反馈"现在太难点击了"，这里就是要明显变大）。
 *
 * 手势走共享的 [appItemGestures]：长按兜底 + 长按后吞 click（见那里的注释）。
 */
@Composable
private fun FolderAppCell(
    app: AppEntry,
    modifier: Modifier = Modifier,
    iconSize: Dp,
    labelFontSize: TextUnit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .appItemGestures(
                key = app.packageName,
                logTag = "Folder",
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = app.label,
                modifier = Modifier
                    .size(iconSize)
                    .clip(RoundedCornerShape(14.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(iconSize)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Android,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(iconSize * 0.55f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.label,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = labelFontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 重命名文件夹的小面板（**同树浮层**：悬浮窗 2038 里弹系统 Dialog 会崩进程）。
 * 点面板外 / 「取消」= 取消；名字为空时「确定」不可点。
 */
@Composable
private fun RenameFolderOverlay(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SportsCamSurfaceAlt)
                // 面板自己吃掉点击，避免点输入框时被外层当作"点外面"
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text("重命名文件夹", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(FOLDER_NAME_MAX_LEN) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            text = "文件夹名",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 12.sp
                        )
                    }
                    inner()
                }
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", fontSize = 12.sp)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = { onConfirm(text) },
                    enabled = text.isNotBlank()
                ) {
                    Text("确定", fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * 包名 → [AppEntry]（名称 + 96px 图标），**与入参一一对应**：解析不出来的（已卸载/被隐藏）给 null。
 *
 * 用 `PackageManager` 直接查而不是复用总应用列表的缓存：文件夹界面可能在
 * "还没打开过总应用列表"时直接被打开（桌面点文件夹），不能依赖那份缓存已加载。
 */
private fun loadFolderEntries(context: Context, packages: List<String>): List<AppEntry?> {
    if (packages.isEmpty()) return emptyList()
    val pm = context.packageManager
    return packages.map { pkg ->
        runCatching {
            val info = pm.getApplicationInfo(pkg, 0)
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrElse { pkg }
            val icon = runCatching {
                pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
            AppEntry(label = label, packageName = pkg, icon = icon)
        }.getOrNull()
    }
}
