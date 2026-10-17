package com.dsh.sportscam.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dsh.sportscam.cover.CoverDebugLog
import com.dsh.sportscam.media.MediaGroup
import com.dsh.sportscam.media.MediaItem
import com.dsh.sportscam.media.MediaRepository
import com.dsh.sportscam.media.MediaTrash
import com.dsh.sportscam.media.ThumbnailLoader
import com.dsh.sportscam.ui.common.ConfirmOverlay
import com.dsh.sportscam.ui.common.hasPermissions
import com.dsh.sportscam.ui.common.showToast
import com.dsh.sportscam.ui.theme.SportsCamBlack
import com.dsh.sportscam.ui.theme.SportsCamSurfaceAlt
import kotlin.math.abs

/** 「向左搓动两次」启动桌面的判定参数 */
private const val FLICK_WINDOW_MS = 900L

/**
 * 搓动热区尺寸。
 *
 * 原始定义：以「DCIM/Camera」首字母 D 的左上角为原点，向右 1.5cm、向下 0.4cm。
 * Compose 没有 cm 单位，用标准换算 1cm = 160/2.54 dp ≈ 62.99dp
 * （dp 本身就定义为 1/160 英寸，正确上报 dpi 的显示器上即真实物理尺寸）：
 *   宽 1.5cm → **2.0cm**（按反馈"往右再拉伸 0.5cm"，原点 D 的左上角不变）
 *   ≈ 126dp
 *
 * 高度按反馈"有点难搓、往下方扩大、但不要与图片重合"调整为 **28dp**：
 * 标题栏总高 40dp，[FLICK_ZONE_TOP_PAD] 让 D 的顶边停在原位，
 * 于是热区纵向占 10dp ~ 38dp —— 底部仍留 2dp，**不侵入下方图片区**。
 */
private val FLICK_ZONE_W = (2.0f / 2.54f * 160f).dp
private val FLICK_ZONE_H = 28.dp

/** D 顶边距标题栏顶部的距离：保证加高热区时"只向下扩"，原点不动 */
private val FLICK_ZONE_TOP_PAD = 10.dp

/**
 * 浏览界面的可提升状态。
 *
 * 由上层（SportsCamApp）用 remember 持有：这样即使本界面被移出组合，
 * 已加载的数据与网格滚动位置也不会丢，返回时仍停在上一次的位置。
 */
class MediaBrowseUiState {
    var groups: List<MediaGroup> by mutableStateOf(emptyList())
    var loading: Boolean by mutableStateOf(false)
    var loaded: Boolean by mutableStateOf(false)
    val gridState: LazyGridState = LazyGridState()

    /** 自增即触发重新查询（删除媒体后刷新列表用） */
    var reloadTick: Int by mutableStateOf(0)

    fun reload() {
        loaded = false
        reloadTick++
    }
}

/**
 * 全屏媒体浏览界面（相簿 #1 / 视频 #1 共用）。
 *
 * - 按时间倒序，按日期分组（今天 / 昨天 / yyyy-MM-dd）
 * - LazyVerticalGrid 缩略图网格，横屏 5 列
 * - 点击进入大图 / 播放
 * - READ_MEDIA_IMAGES / READ_MEDIA_VIDEO 运行时申请
 * - 数据与滚动位置由 [uiState] 持有，关闭大图返回后不会跳回顶部
 */
@Composable
fun MediaBrowseScreen(
    titlePrefix: String,
    /** 标题主体：搓动热区精确锚定它的左上角（如「DCIM/Camera」） */
    titleSuffix: String = "",
    isVideo: Boolean,
    permissions: List<String>,
    uiState: MediaBrowseUiState,
    onBack: () -> Unit,
    /** 回调带上「拍平后的完整列表 + 当前下标」，大图界面据此左右翻页 */
    onOpenItem: (List<MediaItem>, Int) -> Unit,
    /**
     * 隐藏入口：连续点击右上角「xx 项」三次触发（进入 SquareHome 式桌面）。
     * 只在相簿（照片）界面传入，视频界面不传。
     */
    onHiddenEntry: (() -> Unit)? = null
) {
    val context = LocalContext.current
    // ★ 竖屏适配：订阅配置变化（保证旋转后重组），方向判定用显示器真实尺寸
    androidx.compose.ui.platform.LocalConfiguration.current
    val isPortrait = com.dsh.sportscam.cover.CoverDisplay.isPortraitNow(context)

    // ★ 竖屏下整体逆时针 90°（宽高互换）→ 依然是"横屏看照片"的排布，
    //   用户把手机转过来看即可；网格列数因此固定按横屏算（见下面的 5 列）。
    com.dsh.sportscam.ui.common.LandscapeInPortrait(enabled = isPortrait) {
        MediaBrowseContent(
            titlePrefix = titlePrefix,
            titleSuffix = titleSuffix,
            isVideo = isVideo,
            permissions = permissions,
            uiState = uiState,
            onBack = onBack,
            onOpenItem = onOpenItem,
            onHiddenEntry = onHiddenEntry,
            // 内层已被旋转成"横屏尺寸"，所以列数按横屏的 5 列
            columns = 5
        )
    }
}

@Composable
private fun MediaBrowseContent(
    titlePrefix: String,
    titleSuffix: String,
    isVideo: Boolean,
    permissions: List<String>,
    uiState: MediaBrowseUiState,
    onBack: () -> Unit,
    onOpenItem: (List<MediaItem>, Int) -> Unit,
    onHiddenEntry: (() -> Unit)?,
    columns: Int
) {
    val context = LocalContext.current

    var granted by remember { mutableStateOf(hasPermissions(context, permissions)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 以实际授予结果为准（请求结果里可能混着本系统不存在的权限，如 maxSdk 限制的写权限）
        granted = hasPermissions(context, permissions)
    }

    // 首次进入即申请
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(permissions.toTypedArray())
    }
    // 从系统设置页返回时重新检查
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasPermissions(context, permissions)
    }

    // ★ 每次**进入**本界面都重新查询一次（并拉回顶端）。
    //   原来进入时直接沿用缓存（uiState.loaded=true 就跳过查询），导致相机往
    //   DCIM/Camera 新拍的照片在本界面看不到，必须退出应用重进（用户反馈）。
    //   从大图/播放界面返回时本界面不会被重新组合，所以那种情况下滚动位置仍然保留。
    LaunchedEffect(Unit) {
        runCatching { uiState.gridState.scrollToItem(0) }
        if (uiState.loaded) uiState.reload()
    }

    LaunchedEffect(granted, isVideo, uiState.reloadTick) {
        if (!granted) return@LaunchedEffect
        if (uiState.loaded) return@LaunchedEffect
        // 列表已有内容时不要显示 loading，避免每次进入都闪一下转圈
        uiState.loading = uiState.groups.isEmpty()
        val items = if (isVideo) {
            MediaRepository.loadVideos(context)
        } else {
            MediaRepository.loadImages(context)
        }
        uiState.groups = MediaRepository.groupByDate(items)
        uiState.loaded = true
        uiState.loading = false
    }

    val groups = uiState.groups
    val loading = uiState.loading
    // 拍平后的顺序与网格里的展示顺序一致，供大图界面左右滑动翻页使用
    val flatItems = remember(groups) { groups.flatMap { it.items } }
    val flatIndex = remember(flatItems) {
        flatItems.withIndex().associate { it.value.id to it.index }
    }

    // ---------------- 批量选择 ----------------
    var selecting by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var showBatchConfirm by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SportsCamBlack)
        ) {
            BrowseTopBar(
                titlePrefix = titlePrefix,
                titleSuffix = titleSuffix,
                subtitle = "${groups.sumOf { it.items.size }} 项",
                onBack = {
                    if (selecting) {
                        selecting = false
                        selectedIds.clear()
                    } else {
                        onBack()
                    }
                },
                onHiddenEntry = onHiddenEntry
            )

            when {
                !granted -> PermissionHint { permissionLauncher.launch(permissions.toTypedArray()) }
                loading -> CenterHint { CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f)) }
                groups.isEmpty() -> CenterHint {
                    Text(
                        text = if (isVideo) "暂无视频" else "暂无照片",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 13.sp
                    )
                }
                else -> LazyVerticalGrid(
                    // 列数由外层决定：内容已被旋转成横屏尺寸时用 5 列（单格物理尺寸与横屏一致）
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    // 滚动位置由上层持有：关掉大图返回后仍停在原处
                    state = uiState.gridState,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    groups.forEach { group ->
                        // 日期分组标题：横跨整行
                        item(
                            key = "header_${group.title}",
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            DateHeader(title = group.title, count = group.items.size)
                        }
                        items(items = group.items, key = { it.id }) { item ->
                            MediaCell(
                                item = item,
                                isVideo = isVideo,
                                selecting = selecting,
                                selected = selectedIds.contains(item.id),
                                // ★ 重扫版本号：见 MediaCell 里 produceState 的 key2 注释
                                thumbRevision = uiState.reloadTick,
                                onClick = {
                                    if (selecting) {
                                        // 点一下选中，再点一下取消
                                        if (selectedIds.contains(item.id)) {
                                            selectedIds.remove(item.id)
                                        } else {
                                            selectedIds.add(item.id)
                                        }
                                    } else {
                                        onOpenItem(flatItems, flatIndex[item.id] ?: 0)
                                    }
                                },
                                onLongClick = {
                                    // 长按进入批量选择，并把当前这项选上
                                    if (!selecting) selecting = true
                                    if (!selectedIds.contains(item.id)) selectedIds.add(item.id)
                                }
                            )
                        }
                    }
                }
            }
        }

        // ---------------- 批量操作条 ----------------
        if (selecting) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.88f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已选 ${selectedIds.size} 项", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    selecting = false
                    selectedIds.clear()
                }) { Text("取消", fontSize = 12.sp) }
                TextButton(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = { showBatchConfirm = true }
                ) { Text("删除", fontSize = 12.sp, color = com.dsh.sportscam.ui.theme.Accent.current) }
            }
        }

        // ---------------- 二次确认 ----------------
        if (showBatchConfirm) {
            val targets2 = flatItems.filter { selectedIds.contains(it.id) }
            ConfirmOverlay(
                message = "是否删除 ${selectedIds.size} 项媒体？",
                onConfirm = {
                    showBatchConfirm = false
                    // ★「删除」= 仅在启动器内隐藏（文件不动）：
                    //   系统看不到删除事件 → vivo 不会把它收进"第三方删除的相册"。
                    val n = com.dsh.sportscam.media.HiddenMedia.hideAll(context, targets2, isVideo)
                    CoverDebugLog.log("Browse", "批量隐藏：目标 ${targets2.size} 项，新增 $n")
                    selectedIds.clear()
                    selecting = false
                    uiState.reload()
                    showToast(context, "已删除")
                },
                onDismiss = { showBatchConfirm = false }
            )
        }
    }
}

/**
 * 顶部工具条：返回 + 标题 + 数量。
 *
 * 右上角的「xx 项」是**隐藏入口**：连续点三次进入 SquareHome 式桌面
 * （见 [SquareHomeScreen]）。时限 1.5 秒，超时重新计数。
 *
 * 竖屏适配：整屏被 [com.dsh.sportscam.ui.common.LandscapeInPortrait] 逆时针转了 90°，
 * 用户在竖屏上"向左搓"在旋转后的坐标系里是**向下**，所以这里改用通用拖动检测、
 * 只要任一轴位移够大就计一次 —— 横屏/竖屏的手感因此一致。
 */
@Composable
private fun BrowseTopBar(
    /** 标题前缀（如「相簿 1 · 」），不参与搓动热区 */
    titlePrefix: String,
    /** 标题主体（如「DCIM/Camera」）—— 搓动热区**精确锚定它左上角** */
    titleSuffix: String,
    subtitle: String,
    onBack: () -> Unit,
    onHiddenEntry: (() -> Unit)? = null
) {
    // 隐藏入口：在标题主体上**快速向左搓动两次** → 桌面。
    // 为什么不做成点击：这行文本很窄，本来就是误触高发区；方向明确的快速滑动能基本避免误触。
    var flickCount by remember { mutableIntStateOf(0) }
    var lastFlickAt by remember { mutableLongStateOf(0L) }
    val flickMinPx = with(LocalDensity.current) { 44.dp.toPx() }

    val flickModifier = if (onHiddenEntry == null) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            var dx = 0f
            var dy = 0f
            detectDragGestures(
                onDragStart = { dx = 0f; dy = 0f },
                onDragCancel = { dx = 0f; dy = 0f },
                onDragEnd = {
                    // 取"主方向"的位移；竖屏时用户看到的左右搓在旋转坐标系里是上下移动
                    val travel = if (abs(dx) >= abs(dy)) dx else dy
                    if (travel <= -flickMinPx) {
                        val now = System.currentTimeMillis()
                        if (now - lastFlickAt > FLICK_WINDOW_MS) flickCount = 0
                        lastFlickAt = now
                        flickCount++
                        if (flickCount >= 2) {
                            flickCount = 0
                            onHiddenEntry()
                        }
                    }
                }
            ) { change, dragAmount ->
                dx += dragAmount.x
                dy += dragAmount.y
                change.consume()
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 自绘返回键，比 IconButton 更紧凑（副屏高度只有 422）
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
        Text(
            text = titlePrefix,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            // 与前缀后那段主体用同一套顶部对齐 + 偏移，保证两段文字基线一致
            modifier = Modifier
                .align(Alignment.Top)
                .padding(top = FLICK_ZONE_TOP_PAD)
        )

        if (titleSuffix.isNotEmpty()) {
            // ★ 搓动热区 = 以「标题主体第一个字母的左上角」为原点，
            //   向右 1.5cm、向下 0.4cm 的矩形（用户给出的精确规格）。
            //   Compose 的 cm 单位基于 1dp = 1/160 inch 换算，与该显示器 dpi 一致。
            //   文本用 TopStart 对齐 → 它的左上角正好落在热区左上角，即坐标原点。
            Box(
                modifier = Modifier
                    // 顶部对齐 + 固定上偏移 → 调整高度时只往下长，D 的左上角始终是原点
                    .align(Alignment.Top)
                    .padding(top = FLICK_ZONE_TOP_PAD)
                    .width(FLICK_ZONE_W)
                    .height(FLICK_ZONE_H)
                    .then(flickModifier),
                contentAlignment = Alignment.TopStart
            ) {
                Text(
                    text = titleSuffix,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
        )
        Spacer(Modifier.width(6.dp))
    }
}

/** 日期分组标题 */
@Composable
private fun DateHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "($count)",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 10.sp
        )
    }
}

/** 缩略图单元格 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    item: MediaItem,
    isVideo: Boolean,
    selecting: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    /**
     * 重扫版本号（= `MediaBrowseUiState.reloadTick`）。
     *
     * ⚠ 不是为了分页/缓存，而是**大图界面旋转保存后能让本格子的缩略图立刻更新** ——
     * 详见下面 `produceState` 里 key2 处的注释。
     */
    thumbRevision: Int = 0
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val thumbPx = with(density) { 160.dp.roundToPx() }

    // 先取内存缓存避免闪烁，再异步加载
    //
    // ⚠ **key2 = thumbRevision 不能省**（上一轮实测 bug：旋转保存后返回相册，缩略图方向不变 ✗）：
    //   · 网格是 `items(items = group.items, key = { it.id })`，格子的 produceState 只按 item.id 做 key；
    //   · 旋转是"按真实路径原地覆盖 + MediaScannerConnection.scanFile"，媒体库那一行的 **_ID 通常不变**，
    //     重新查询得到的 MediaItem 只是"同 id 的另一份数据"；
    //   · 于是 `uiState.reload()` 换掉 groups 列表时，**已经组合好的格子不会重跑 produceState**，
    //     屏上那张缩略图仍是旧方向（只有把整个界面的组合丢掉再重建——比如退出相册再进来——才会变）；
    //   · `ThumbnailLoader.clear()` 只能让"重新组合"的格子取到新图，救不了已经组合着的格子。
    //   带上一个"重扫版本号"进 key，reload 后就会强制重新取缩略图（缓存已清 → 重新 loadThumbnail）。
    val bitmap by produceState<ImageBitmap?>(
        initialValue = ThumbnailLoader.cached(item.id),
        key1 = item.id,
        key2 = thumbRevision
    ) {
        value = ThumbnailLoader.load(context, item.id, item.uri, thumbPx, thumbPx)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(SportsCamSurfaceAlt)
            // 长按进入批量选择；批量模式下单击改为"选中/取消"
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.2f),
                modifier = Modifier.size(22.dp)
            )
        }

        if (isVideo) {
            // 播放角标
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.Center)
            )
            // 时长
            if (item.durationMs > 0) {
                Text(
                    text = MediaRepository.formatDuration(item.durationMs),
                    color = Color.White,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        // 批量选择模式：右上角出现可框选的方框（选中打勾）
        if (selecting) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (selected) com.dsh.sportscam.ui.theme.Accent.current else Color.Black.copy(alpha = 0.5f))
                    .border(1.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "已选",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionHint(onGrant: () -> Unit) {
    CenterHint {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "需要媒体读取权限才能浏览",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onGrant) { Text("授予权限") }
        }
    }
}

@Composable
private fun CenterHint(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) { content() }
}
