package com.dsh.sportscam.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.sportscam.device.IntentLauncher
import com.dsh.sportscam.media.MediaItem
import com.dsh.sportscam.media.MediaRepository
import com.dsh.sportscam.media.MediaTrash
import com.dsh.sportscam.media.ThumbnailLoader
import com.dsh.sportscam.ui.common.ConfirmOverlay
import com.dsh.sportscam.ui.common.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 缩放下限 / 上限 */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

/** 双击放大到的倍数 */
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * 图片手势：
 * - 双指捏合 → 缩放；
 * - 已放大时单指拖动 → 平移；
 * - **未放大时的单指横向拖动不消费事件**，留给外层 HorizontalPager 翻页；
 * - 单击退出 / 双击放大还原由另一个 pointerInput 处理。
 *
 * 关键点：只有当「有两根手指」或「当前已放大」时才 consume()，
 * 否则 Pager 收到的事件会被判定为已消费而不翻页。
 */
private suspend fun PointerInputScope.detectZoomPan(
    isZoomed: () -> Boolean,
    onTransform: (pan: Offset, zoom: Float) -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val pressedCount = event.changes.count { it.pressed }
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            val handle = pressedCount >= 2 || isZoomed()
            if (handle && (zoom != 1f || pan != Offset.Zero)) {
                onTransform(pan, zoom)
                // 只有接管手势时才消费，避免影响 Pager 翻页
                event.changes.forEach { change ->
                    if (change.positionChanged()) change.consume()
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * 全屏大图 / 视频查看界面。
 *
 * - **左右滑动切换上一张 / 下一张**（HorizontalPager）
 * - 图片手势：双指捏合缩放、放大后单指拖动平移、双击放大/还原、单击退出
 * - 视频：显示封面 + 大播放键，可左右滑动切换下一条
 * - **图片页右上角「旋转」**：把当前图片逆时针旋转 90° 并**写回原文件**（见 [rotateImageCounterClockwise]）
 * - 此界面不屏蔽返回手势，返回键同样可退出
 */
@Composable
fun MediaViewerScreen(
    items: List<MediaItem>,
    startIndex: Int,
    isVideo: Boolean,
    displayId: Int,
    onBack: () -> Unit,
    /** 删除（移入最近删除）成功后回调：上层据此关闭本界面并刷新列表 */
    onDeleted: () -> Unit = onBack,
    /**
     * 删除确认文案（默认「是否删除这张照片？」）。放在「最近删除」里时改成
     * 「将永久删除…」，因为那里的删除是**真实删除文件**。
     */
    deleteConfirmMessage: String = "是否删除这${if (isVideo) "个视频" else "张照片"}？",
    /**
     * 替换删除动作：返回 null 表示成功，否则是给用户看的原因。
     *
     * 默认（null）= 启动器内隐藏（[com.dsh.sportscam.media.HiddenMedia]）；
     * 「最近删除」里传入 = 真实删除文件 + 取消隐藏。
     */
    onDeleteAction: ((MediaItem) -> String?)? = null,
    /**
     * **旋转保存成功**后回调（带默认值，老调用点不用改）。
     *
     * 为什么必须通知上层：旋转改的是**文件内容**，本界面靠自己重新解码就能看到新方向，
     * 但**相册网格（父级）的缩略图不会自己重算** —— 上一轮的实测现象正是
     * "旋转后返回相册方向不变，必须退出主界面再重进预览才变 ✗"。
     * 上层应把它接到 `galleryUiState.reload()` / `videosUiState.reload()`（等价的重扫）上。
     */
    onMediaChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    // ★ 竖屏适配：订阅配置变化（保证旋转后重组），方向判定用显示器真实尺寸
    androidx.compose.ui.platform.LocalConfiguration.current
    val isPortrait = com.dsh.sportscam.cover.CoverDisplay.isPortraitNow(context)

    // ★ 竖屏下整体逆时针 90°（宽高互换）→ 依然是横屏看图的版式，
    //   用户把手机转过来看即可；左右滑动翻页随之变成上下滑动。
    com.dsh.sportscam.ui.common.LandscapeInPortrait(enabled = isPortrait) {
        MediaViewerContent(
            items = items,
            startIndex = startIndex,
            isVideo = isVideo,
            displayId = displayId,
            onBack = onBack,
            onDeleted = onDeleted,
            deleteConfirmMessage = deleteConfirmMessage,
            onDeleteAction = onDeleteAction,
            onMediaChanged = onMediaChanged
        )
    }
}

@Composable
private fun MediaViewerContent(
    items: List<MediaItem>,
    startIndex: Int,
    isVideo: Boolean,
    displayId: Int,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    deleteConfirmMessage: String,
    onDeleteAction: ((MediaItem) -> String?)?,
    onMediaChanged: () -> Unit
) {
    if (items.isEmpty()) {
        // 理论上不会发生（从非空网格进入）；兜底显示返回键
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            BackButton(onBack, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        return
    }

    val context = LocalContext.current
    val density = LocalDensity.current
    val previewPx = with(density) { 1080.dp.roundToPx() }
    val scope = rememberCoroutineScope()

    val safeStart = startIndex.coerceIn(0, items.size - 1)
    val pagerState = rememberPagerState(initialPage = safeStart) { items.size }

    // ---- 缩放 / 平移（只作用于当前页；放大时手势被接管，Pager 不会翻页）----
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    /**
     * 「旋转」保存成功后自增 → 让下面 `produceState` 的 key 变化，重新解码原图。
     *
     * 必须这样做的原因：全屏大图**不在** [ThumbnailLoader] 的 LruCache 里（每次进页都重新解码），
     * 但 `produceState` 只认 key —— 不改 key，文件虽已换掉，Compose 也不会重新跑一遍解码。
     */
    var rotateTick by remember { mutableIntStateOf(0) }

    /**
     * 旋转保存后，媒体库那一条的 **uri 可能被扫描器重建**（删旧行 + 插新行 → `_ID` 变化）。
     *
     * 那种情况下原来的 `content://media/external/images/media/<旧id>` 立刻读不出图，
     * 预览就会一直转圈 —— 所以把"扫描后当前 uri"记下来，**只对这张图**生效（按 id 配对，
     * 避免翻页时把别的照片也换成这张的 uri）。
     */
    var rotatedUri by remember { mutableStateOf<Pair<Long, Uri>?>(null) }

    // 翻页完成后复位缩放
    LaunchedEffect(pagerState.settledPage) {
        scale = 1f
        offset = Offset.Zero
    }

    // 手势在 graphicsLayer 之前：命中区域始终是整屏，不随图片放大而偏移
    val gestureModifier = Modifier
        .onSizeChanged { containerSize = it }
        .pointerInput(pagerState.settledPage) {
            detectZoomPan(
                isZoomed = { scale > 1.01f },
                onTransform = { pan, zoom ->
                    val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    scale = newScale
                    // 平移范围限制在放大后的图片边界内；未放大时 max = 0，自动回到中心
                    val maxX = (containerSize.width * (newScale - 1f)) / 2f
                    val maxY = (containerSize.height * (newScale - 1f)) / 2f
                    offset = Offset(
                        x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                        y = (offset.y + pan.y).coerceIn(-maxY, maxY)
                    )
                }
            )
        }
        .pointerInput(pagerState.settledPage) {
            detectTapGestures(
                onTap = { onBack() },
                onDoubleTap = {
                    if (scale > 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = DOUBLE_TAP_SCALE
                    }
                }
            )
        }

    val currentItem = items[pagerState.currentPage.coerceIn(0, items.size - 1)]
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ---------------- 左右滑动切换 ----------------
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            // ★ 放大状态下**禁止翻页**：否则双指捏合时页器仍会跟着两指中心的位移滑一点，
            //   把相邻那页露出来（而且它和当前页共用同一个 scale，会一起被放大挤进屏幕）。
            userScrollEnabled = isVideo || scale <= 1.01f
        ) { page ->
            val item = items[page]
            // 缩放只作用于当前页：共享 scale 加到每一页上，正是"下一张从右侧侵入"的另一半原因
            val isCurrentPage = page == pagerState.currentPage

            if (isVideo) {
                // ★ 视频用**内建播放器**：
                //   常驻悬浮窗没有 Activity，拉起系统播放器在副屏上会失败；
                //   VideoView 的 SurfaceView 在 overlay 窗口里图层顺序也不可靠。
                //   只有当前页才创建播放器（页器会预组合相邻页，不能同时起多个）。
                if (isCurrentPage) {
                    SimpleVideoPlayer(
                        uri = item.uri,
                        title = item.displayName,
                        onClose = onBack,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }
            } else {
                val bitmap by produceState<ImageBitmap?>(
                    initialValue = null,
                    key1 = item.id,
                    // 旋转保存后 rotateTick 变化 → 重新解码，界面立刻变成旋转后的样子
                    key2 = rotateTick
                ) {
                    // 旋转过的那张：优先用"扫描后当前 uri"（媒体库行被重建时旧 uri 已失效），
                    // 它读不出来再退回原 uri，保证不会卡在转圈上
                    val fresh = rotatedUri?.takeIf { it.first == item.id }?.second
                    value = ThumbnailLoader.loadFullImage(context, fresh ?: item.uri)
                        ?: if (fresh != null) ThumbnailLoader.loadFullImage(context, item.uri) else null
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val image = bitmap
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = item.displayName,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(gestureModifier)
                                .graphicsLayer {
                                    if (isCurrentPage) {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offset.x
                                        translationY = offset.y
                                    }
                                },
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }

        // ---------------- 缩放倍数提示 ----------------
        if (!isVideo && scale > 1.05f) {
            Text(
                text = "%.1f×".format(scale),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )
        }

        // ---------------- 底部信息条 + 序号 ----------------
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = currentItem.displayName,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp
                )
                if (isVideo && currentItem.durationMs > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "时长 ${MediaRepository.formatDuration(currentItem.durationMs)}",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${pagerState.currentPage + 1} / ${items.size}",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp
            )
        }

        // ---------------- 左上角返回 ----------------
        BackButton(onBack, Modifier.align(Alignment.TopStart).padding(8.dp))

        // ---------------- 右上角：旋转（仅图片） + 删除（移入"最近删除"） ----------------
        var showDeleteConfirm by remember { mutableStateOf(false) }
        var rotating by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ★ 只有**图片**页才有「旋转」：视频没有"把文件转 90°"这件事
            if (!isVideo) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        // 保存期间禁点，避免连点两次叠着转 180°
                        .clickable(enabled = !rotating) {
                            rotating = true
                            scope.launch {
                                val outcome = rotateImageCounterClockwise(context, currentItem)
                                if (outcome.error == null) {
                                    // 媒体库行被重建时，改用扫描后的新 uri 读图
                                    outcome.freshUri?.let { rotatedUri = currentItem.id to it }
                                    // 缩略图缓存里的那一张也过时了，清掉；再让大图重新解码
                                    ThumbnailLoader.clear()
                                    scale = 1f
                                    offset = Offset.Zero
                                    rotateTick++
                                    // ★ 关键：通知上层重扫相册/视频列表 —— 否则返回相册时
                                    //   网格缩略图还是旧方向（上一轮的实测 bug）。
                                    onMediaChanged()
                                } else {
                                    // 失败才有文字提示；成功不弹文字（转圈结束 + 图已转向 = 反馈本身）
                                    showToast(context, outcome.error)
                                }
                                rotating = false
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (rotating) {
                        CircularProgressIndicator(
                            color = Color.White.copy(alpha = 0.85f),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            // ★ 用 AutoMirrored 版（Filled.RotateLeft 已废弃）；
                            //   这套图标在 RTL 下会镜像，中文环境（LTR）显示不变
                            imageVector = Icons.AutoMirrored.Filled.RotateLeft,
                            contentDescription = "逆时针旋转90度并保存",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
            }

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { showDeleteConfirm = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = com.dsh.sportscam.ui.theme.Accent.current,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (showDeleteConfirm) {
            ConfirmOverlay(
                message = deleteConfirmMessage,
                onConfirm = {
                    showDeleteConfirm = false
                    val action = onDeleteAction
                    if (action != null) {
                        // 由调用方决定"删除"的含义（最近删除里 = 真实删除文件）
                        val err = action(currentItem)
                        showToast(context, err ?: "已删除")
                    } else {
                        // ★「删除」= 仅在启动器内隐藏（文件不动）→ vivo 不会拦截、
                        //   不会生成那份 30 天自动清除的"第三方删除的相册"副本。
                        com.dsh.sportscam.media.HiddenMedia.hide(context, currentItem, isVideo)
                        showToast(context, "已删除")
                    }
                    onDeleted()
                },
                onDismiss = { showDeleteConfirm = false }
            )
        }
    }
}

/** 左上角返回按钮 */
@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.45f))
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
}

// ---------------------------------------------------------------------------
// 「旋转」：逆时针 90° 并写回原文件
// ---------------------------------------------------------------------------

/**
 * 旋转时的最长边上限。
 *
 * 副屏只有 682×422，而手机原图动辄 4000×3000 —— ARGB_8888 全尺寸解码约 48MB，
 * 再加上旋转后新位图和编码缓冲，副屏上必 OOM。2048 与原图查看（[ThumbnailLoader.loadFullImage]）
 * 保持一致：既够清楚，又只占 ~16MB。
 */
private const val ROTATE_MAX_DECODE_SIZE = 2048

/** JPEG 重编码质量（只对 JPEG 生效；PNG 无损、WebP 走 lossy） */
private const val ROTATE_JPEG_QUALITY = 95

/**
 * 把 [item] 指向的图片**逆时针旋转 90°**并**写回原文件**。
 *
 * 为什么要绕这么多步：
 *  1. 先用 `inJustDecodeBounds` **只读尺寸**、算出 `inSampleSize` 再正式解码
 *     （最长边 ≤ [ROTATE_MAX_DECODE_SIZE]）—— 直接解原图会在副屏上 OOM；
 *  2. `BitmapFactory` **不会**应用 EXIF 的 `TAG_ORIENTATION`，而相机照片十有八九带它。
 *     不先按 EXIF 摆正就 postRotate，方向会转错（甚至看起来"没转"），所以先归一化；
 *  3. 直接往原文件流里编码，一旦中途失败原图就毁了 —— 先编码到 cacheDir 的临时文件，
 *     全部成功后再整体覆盖；
 *  4. 写回优先走 MediaStore 的 `DATA` 列拿到的**真实路径**：这样媒体库那条记录
 *     大概率还是同一张照片（`_ID` 不变），不会在相册里多出一张"新照片"；
 *     拿不到真实路径（比如 uri 不是 MediaStore 的）才退回 `openOutputStream(uri)`；
 *  5. 最后 `MediaScannerConnection.scanFile` 通知媒体库重读（尺寸 / 缩略图缓存），
 *     **并等扫描回调把"当前 uri"带回来** —— 万一某个 ROM 把行重建了（`_ID` 变化），
 *     界面还能用新 uri 读图，不会卡在转圈（C1③ 的兜底）。
 *
 * 全程在 IO 线程；主线程只负责调用与刷新界面。
 *
 * @return [RotateOutcome]；`error == null` 表示成功，`freshUri` 是扫描后媒体库里的当前 uri。
 */
private suspend fun rotateImageCounterClockwise(
    context: Context,
    item: MediaItem
): RotateOutcome =
    withContext(Dispatchers.IO) {
        val realPath = resolveRealPath(context, item.uri)
        // 真实路径（可读）才走"按路径直写"这条路：这样媒体库记录 _ID 大概率不变
        val filePath = realPath?.let { File(it) }?.takeIf { it.isFile && it.canRead() }?.absolutePath

        // ---- ① 只读尺寸（inJustDecodeBounds） ----
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsOk = runCatching {
            val p = filePath
            if (p != null) {
                BitmapFactory.decodeFile(p, bounds)
            } else {
                context.contentResolver.openInputStream(item.uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            }
            bounds.outWidth > 0 && bounds.outHeight > 0
        }.getOrDefault(false)
        if (!boundsOk) return@withContext RotateOutcome("读取原图尺寸失败", null)

        // ---- ② 采样解码（最长边 ≤ 2048） ----
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, ROTATE_MAX_DECODE_SIZE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            val p = filePath
            if (p != null) {
                BitmapFactory.decodeFile(p, options)
            } else {
                context.contentResolver.openInputStream(item.uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }
        }.getOrNull() ?: return@withContext RotateOutcome("解码原图失败", null)

        // ---- ③ EXIF 摆正 → 逆时针 90° ----
        val upright = applyExifOrientation(decoded, filePath)
        val rotated = runCatching {
            Bitmap.createBitmap(
                upright,
                0,
                0,
                upright.width,
                upright.height,
                Matrix().apply { postRotate(-90f) },
                true
            )
        }.getOrNull()
        // 中间产物（EXIF 摆正出来的那张）可以立刻回收，别等到 GC
        if (upright !== decoded) upright.recycle()
        if (rotated == null) {
            decoded.recycle()
            return@withContext RotateOutcome("旋转失败（内存不足？）", null)
        }
        if (rotated !== decoded) decoded.recycle()

        // ---- ④ 先编码到临时文件（失败也不会毁掉原图） ----
        val temp = File(context.cacheDir, "rotate_${System.currentTimeMillis()}.tmp")
        val encoded = runCatching {
            FileOutputStream(temp).use { out ->
                if (!rotated.compress(compressFormatFor(realPath ?: item.displayName), ROTATE_JPEG_QUALITY, out)) {
                    error("Bitmap.compress 返回 false")
                }
            }
            true
        }.getOrDefault(false)
        rotated.recycle()
        if (!encoded) {
            temp.delete()
            return@withContext RotateOutcome("重新编码失败（内存或存储空间不足）", null)
        }

        // ---- ⑤ 覆盖写回原文件 ----
        //   首选真实路径直写；直写失败（比如没有「所有文件访问」）再退回 contentResolver 写 uri
        val wroteByPath = filePath?.let { p ->
            runCatching {
                temp.inputStream().use { input ->
                    FileOutputStream(p, false).use { input.copyTo(it) }
                }
                true
            }.getOrDefault(false)
        } ?: false

        val written = wroteByPath || runCatching {
            val sink = context.contentResolver.openOutputStream(item.uri, "w")
                ?: return@runCatching false
            sink.use { out -> temp.inputStream().use { it.copyTo(out) } }
            true
        }.getOrDefault(false)
        temp.delete()
        if (!written) return@withContext RotateOutcome("写回原文件失败（缺「所有文件访问」权限？）", null)

        // ---- ⑥ 通知媒体库重读，并取回"扫描后当前 uri" ----
        val freshUri = filePath?.let { scanAndAwaitFreshUri(context, it) }
        RotateOutcome(null, freshUri)
    }

/**
 * 旋转结果。
 *
 * @param error null = 成功；否则是给用户看的失败原因
 * @param freshUri 扫描完成后该文件在媒体库里的**当前** uri（行被重建时与原来的不同）；拿不到为 null
 */
private class RotateOutcome(val error: String?, val freshUri: Uri?)

/**
 * 通知媒体扫描器重读 [path]，**等扫描结束**并返回扫描后该文件在媒体库里的当前 uri。
 *
 * 为什么要等回调：`MediaScannerConnection.scanFile` 是异步的，扫描器可能
 * "删掉旧行 + 插入新行"（`_ID`/uri 变化）—— 那种 ROM 上如果继续用旧 uri 读图，
 * 预览会一直转圈（C1③ 要排查的正是这个）。回调里的 uri 就是权威答案；
 * 回调没给（不是媒体文件 / 超时 3s）就自己按 DATA 路径查一次。
 */
private fun scanAndAwaitFreshUri(context: Context, path: String): Uri? {
    val latch = CountDownLatch(1)
    var scanned: Uri? = null
    return try {
        MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, uri ->
            scanned = uri
            latch.countDown()
        }
        // 扫描是异步的：等一小会儿（3s 足够；超时就不阻塞界面，直接查库兜底）
        latch.await(3, TimeUnit.SECONDS)
        scanned ?: queryImageUriByPath(context, path)
    } catch (t: Throwable) {
        queryImageUriByPath(context, path)
    }
}

/** 按真实路径反查当前的照片 uri（Images 集合，和相册查询用的是同一张表） */
private fun queryImageUriByPath(context: Context, path: String): Uri? = runCatching {
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns._ID),
        MediaStore.MediaColumns.DATA + "=?",
        arrayOf(path),
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                cursor.getLong(0)
            )
        } else {
            null
        }
    }
}.getOrNull()

/** 取媒体对应的真实文件路径（`file://` 直接返回，否则查 MediaStore 的 DATA 列） */
private fun resolveRealPath(context: Context, uri: Uri): String? = runCatching {
    if (uri.scheme == "file") return@runCatching uri.path
    context.contentResolver
        .query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

/**
 * 采样率：让解码后的最长边尽量 ≤ [maxSize]（BitmapFactory 的常规做法，取 2 的幂）。
 * 例如 4000×3000 / maxSize=2048 → 2（解出来 2000×1500）。
 */
private fun sampleSizeFor(width: Int, height: Int, maxSize: Int): Int {
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / sample > maxSize) sample *= 2
    return sample
}

/**
 * 按 EXIF 的 `TAG_ORIENTATION` 把位图摆正（[BitmapFactory] 不认这个标签）。
 *
 * 相机拍的照片大多是 `ORIENTATION_ROTATE_90/270`：像素其实是横着的，
 * 只有靠 EXIF 才能"竖着显示"。不摆正就旋转，用户会看到方向不对。
 * 罕见的方向（转置/镜像+旋转组合）不处理，直接原样返回。
 */
private fun applyExifOrientation(bitmap: Bitmap, path: String?): Bitmap {
    if (path == null) return bitmap
    val orientation = runCatching {
        android.media.ExifInterface(path).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        else -> return bitmap
    }
    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)
}

/**
 * 按原文件扩展名挑编码格式。
 *
 * 不要一律转成 JPEG：扩展名与内容不符会被部分相册/分享目标拒读。
 * ⚠ `Bitmap.compress` **不支持 HEIF**（新机型/iOS 传过来的 .heic），这种只能按 JPEG 编码，
 * 文件名保持原样 —— 解码端（含本应用）都是按文件头嗅探的，实测正常。
 */
private fun compressFormatFor(fileName: String): Bitmap.CompressFormat {
    val lower = fileName.lowercase(Locale.ROOT)
    return when {
        lower.endsWith(".png") -> Bitmap.CompressFormat.PNG
        lower.endsWith(".webp") ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
        else -> Bitmap.CompressFormat.JPEG
    }
}
