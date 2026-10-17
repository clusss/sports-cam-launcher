package com.dsh.sportscam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.sportscam.cover.CoverDebugLog
import com.dsh.sportscam.desktop.DesktopStore
import com.dsh.sportscam.media.HiddenMedia
import com.dsh.sportscam.media.MediaItem
import com.dsh.sportscam.media.MediaRepository
import com.dsh.sportscam.media.MediaTrash
import com.dsh.sportscam.media.ThumbnailLoader
import com.dsh.sportscam.ui.common.ConfirmOverlay
import com.dsh.sportscam.ui.common.showToast
import com.dsh.sportscam.ui.theme.Accent
import com.dsh.sportscam.ui.theme.SportsCamBlack
import com.dsh.sportscam.ui.theme.SportsCamSurfaceAlt

/**
 * 「最近删除」。
 *
 * 只列**被隐藏的媒体**（"删除"= 启动器内隐藏，文件不动，这样系统看不到删除事件、
 * vivo 不会把它收进"第三方删除的相册"）。
 *
 * 「删除」在这里 = **真实删除文件**（按需求：不再套一层回收站）。
 * 「恢复」= 取消隐藏（不动文件）。
 *
 * 入口只在控制台。
 */
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coverDisplayId by com.dsh.sportscam.cover.rememberCoverDisplayId()
    var tick by remember { mutableIntStateOf(0) }

    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    // 打开的内建查看器（图片/视频分开成两个列表：列表里点击只在自己的类型内翻页）
    var viewerItem by remember { mutableStateOf<MediaItem?>(null) }
    val imageItems = remember(items) { items.filter { !isVideoItem(it) } }
    val videoItems = remember(items) { items.filter { isVideoItem(it) } }

    // 批量选择
    var selecting by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var confirmSingleDelete by remember { mutableStateOf<MediaItem?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var confirmBatchRestore by remember { mutableStateOf(false) }

    // ★ 竖屏下整体逆时针 90°（宽高互换）→ 与相簿/视频列表的旋转保持一致
    androidx.compose.ui.platform.LocalConfiguration.current
    val isPortrait = com.dsh.sportscam.cover.CoverDisplay.isPortraitNow(context)

    // ★ 桌面是否已经有「最近删除」的 1×1 垃圾桶入口（第十二轮第 2 项）：
    //   用来把顶栏那颗按钮显示成"已在桌面"，用户一眼能看到状态、不会反复点。
    var trashEntryOnDesktop by remember {
        mutableStateOf(DesktopStore.containsTrashEntry(context))
    }

    LaunchedEffect(tick) {
        val keys = HiddenMedia.all(context)
        val imageIds = keys.filter { it.startsWith("image:") }
            .mapNotNull { it.removePrefix("image:").toLongOrNull() }.toSet()
        val videoIds = keys.filter { it.startsWith("video:") }
            .mapNotNull { it.removePrefix("video:").toLongOrNull() }.toSet()
        items = MediaRepository.loadByIds(context, imageIds, isVideo = false) +
            MediaRepository.loadByIds(context, videoIds, isVideo = true)
    }

    Box(modifier = Modifier.fillMaxSize().background(SportsCamBlack)) {
      com.dsh.sportscam.ui.common.LandscapeInPortrait(enabled = isPortrait) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---------------- 顶栏 ----------------
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 6.dp),
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
                        Icons.AutoMirrored.Filled.ArrowBack, "返回",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "最近删除",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                // ★ 第十二轮第 2 项：把「最近删除」加到桌面（1×1 垃圾桶入口）。
                //   · 幂等：已在桌面时 `addTrashEntry` 返回 false → 提示「桌面上已经有这个入口了」
                //     （**不是**"桌面满了"✗）；
                //   · ⚠ **不在这里弹密码**：进入最近删除时的密码门（Purpose.Trash）已经拦过一次了，
                //     这里再弹会让用户连输两遍 ✗；
                //   · 加完后按钮文案切成「已在桌面」，避免用户以为没成功而反复点。
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable {
                            val added = DesktopStore.addTrashEntry(
                                context,
                                com.dsh.sportscam.cover.CoverDisplay.isPortraitNow(context)
                            )
                            trashEntryOnDesktop = DesktopStore.containsTrashEntry(context)
                            CoverDebugLog.log(
                                "Trash",
                                "在桌面添加『最近删除』入口：added=$added 现在已在桌面=$trashEntryOnDesktop"
                            )
                            showToast(
                                context,
                                if (added) "已添加到桌面" else "桌面上已经有这个入口了"
                            )
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (trashEntryOnDesktop) Icons.Filled.Check else Icons.Filled.Add,
                        contentDescription = null,
                        tint = if (trashEntryOnDesktop) Accent.current else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (trashEntryOnDesktop) "已在桌面" else "桌面入口",
                        color = if (trashEntryOnDesktop) Accent.current else Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "${items.size} 项",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "最近删除是空的",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(items = items, key = { "h${it.id}" }) { item ->
                        HiddenCell(
                            item = item,
                            selecting = selecting,
                            selected = selectedIds.contains(item.id),
                            // ★ 重扫版本号：见 HiddenCell 里 produceState 的 key2 注释
                            thumbRevision = tick,
                            onClick = {
                                if (selecting) {
                                    if (selectedIds.contains(item.id)) selectedIds.remove(item.id)
                                    else selectedIds.add(item.id)
                                } else {
                                    viewerItem = item
                                }
                            },
                            onLongClick = {
                                if (!selecting) selecting = true
                                if (!selectedIds.contains(item.id)) selectedIds.add(item.id)
                            }
                        )
                    }
                }
            }
        }
      }   // LandscapeInPortrait（竖屏旋转；下方的浮层不再旋转）

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
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    if (selectedIds.size == items.size) selectedIds.clear()
                    else {
                        selectedIds.clear()
                        items.forEach { selectedIds.add(it.id) }
                    }
                }) { Text("全选/取消", fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    selecting = false
                    selectedIds.clear()
                }) { Text("取消", fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f)) }
                TextButton(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = { confirmBatchRestore = true }
                ) { Text("恢复", fontSize = 12.sp, color = Accent.current) }
                TextButton(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = { confirmBatchDelete = true }
                ) { Text("删除", fontSize = 12.sp, color = Accent.current) }
            }
        }

        // ---------------- 批量恢复（只取消隐藏） ----------------
        if (confirmBatchRestore) {
            val list = items.filter { selectedIds.contains(it.id) }
            ConfirmOverlay(
                message = "是否恢复 ${list.size} 项媒体？",
                confirmText = "恢复",
                onConfirm = {
                    confirmBatchRestore = false
                    list.forEach { HiddenMedia.unhide(context, it.id, isVideoItem(it)) }
                    selectedIds.clear()
                    selecting = false
                    showToast(context, "已恢复")
                    tick++
                },
                onDismiss = { confirmBatchRestore = false }
            )
        }

        // ---------------- 批量删除（真实删除文件） ----------------
        if (confirmBatchDelete) {
            val list = items.filter { selectedIds.contains(it.id) }
            ConfirmOverlay(
                message = "是否删除 ${list.size} 项媒体？将永久删除，不可恢复",
                onConfirm = {
                    confirmBatchDelete = false
                    var ok = 0
                    list.forEach {
                        if (MediaTrash.deleteReally(context, it) == null) ok++
                        HiddenMedia.unhide(context, it.id, isVideoItem(it))
                    }
                    selectedIds.clear()
                    selecting = false
                    showToast(context, "已永久删除 $ok 项")
                    tick++
                },
                onDismiss = { confirmBatchDelete = false }
            )
        }

        // ---------------- 查看器（滑块切换 / 捏合缩放 / 内建视频播放） ----------------
        //
        // 复用 MediaViewerScreen（它自带 HorizontalPager + 捏合缩放 + SimpleVideoPlayer），
        // 只把"删除"换成**真实删除文件**：这里的删除与相簿里的"移入最近删除"语义不同。
        viewerItem?.let { current ->
            val isVideo = isVideoItem(current)
            val list = if (isVideo) videoItems else imageItems
            val index = list.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
            MediaViewerScreen(
                items = list,
                startIndex = index,
                isVideo = isVideo,
                displayId = coverDisplayId,
                onBack = { viewerItem = null },
                onDeleted = { viewerItem = null; tick++ },
                // ★ 旋转保存成功后：自增 tick → 上面的 LaunchedEffect 重查 + HiddenCell 的
                //   thumbRevision 变化 → 网格缩略图立刻变成旋转后的方向
                onMediaChanged = { tick++ },
                deleteConfirmMessage = "是否永久删除这${if (isVideo) "个视频" else "张照片"}？不可恢复",
                onDeleteAction = { item -> permanentlyDelete(context, item) }
            )
        }

        confirmSingleDelete?.let { item ->
            ConfirmOverlay(
                message = "是否删除「${item.displayName}」？将永久删除，不可恢复",
                onConfirm = {
                    confirmSingleDelete = null
                    showToast(context, permanentlyDelete(context, item) ?: "已永久删除")
                    tick++
                },
                onDismiss = { confirmSingleDelete = null }
            )
        }
    }
}

/**
 * 「最近删除」里的删除 = **真实删除文件** + 取消隐藏。
 *
 * @return null 表示成功，否则是给用户看的原因
 */
private fun permanentlyDelete(context: android.content.Context, item: MediaItem): String? {
    val err = MediaTrash.deleteReally(context, item)
    HiddenMedia.unhide(context, item.id, isVideoItem(item))
    return err
}

/** 隐藏条目里是不是视频 */
private fun isVideoItem(item: MediaItem): Boolean {
    val n = item.displayName.lowercase()
    return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") ||
        n.endsWith(".mov") || n.endsWith(".3gp") || n.endsWith(".webm")
}

/** 媒体格子（图片缩略图 / 视频封面都用现成加载器） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HiddenCell(
    item: MediaItem,
    selecting: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    /** 重扫版本号（= 本界面的 `tick`）；作用同 [MediaCell] 里的同名参数 */
    thumbRevision: Int = 0
) {
    val context = LocalContext.current
    // 先取内存缓存避免闪烁，再异步加载；
    // ⚠ key2（重扫版本号）不能省：本列表 `items(key = { "h${it.id}" })` + produceState 只按 id 做 key，
    //   旋转是"原地覆盖 + scanFile"（_ID 通常不变）→ 只 reload 列表不会让已组合格子重跑 → 缩略图还是旧方向。
    //   详见 MediaBrowseScreen.kt 里 MediaCell 的同类注释。
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = ThumbnailLoader.cached(item.id),
        key1 = item.id,
        key2 = thumbRevision
    ) {
        value = ThumbnailLoader.load(context, item.id, item.uri, 320, 320)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(SportsCamSurfaceAlt)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        val image = thumb
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            if (isVideoItem(item)) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "视频",
                    tint = Color.White.copy(alpha = 0.95f),
                    modifier = Modifier.size(22.dp)
                )
            }
        } else {
            Text("▦", color = Color.White.copy(alpha = 0.35f), fontSize = 18.sp)
        }

        // 批量选择方框（颜色跟随风格）
        if (selecting) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        if (selected) Accent.current else Color.Black.copy(alpha = 0.5f)
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "已选",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}


