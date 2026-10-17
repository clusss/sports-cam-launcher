package com.dsh.sportscam.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.view.Display
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.sportscam.cover.CoverDebugLog
import com.dsh.sportscam.cover.CoverDisplay
import com.dsh.sportscam.cover.CoverToast
import com.dsh.sportscam.cover.WallpaperStore
import com.dsh.sportscam.ui.theme.Accent
import com.dsh.sportscam.ui.theme.SportsCamBlack
import com.dsh.sportscam.ui.theme.SportsCamSurface
import com.dsh.sportscam.ui.theme.SportsCamSurfaceAlt
import com.dsh.sportscam.ui.theme.TorchAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * ============================================================================
 *  桌面壁纸 · 简易编辑预览界面（第十轮第 4 项）
 * ============================================================================
 *
 * 入口：控制台（settings-console 已按 `WallpaperEditorScreen(onBack)` 接线 ✓）。
 *
 * ## 两个阶段（同一个 Composable 内切换，不额外开 Activity ✓）
 *  1. **选图**：应用内自建的相册网格 ✓ —— ⚠ **不用 SAF 系统选图器** ✗：
 *     这台机器是"vivo X Flip 副屏改造机"，**主屏没有面板**；`ACTION_OPEN_DOCUMENT`
 *     会把选图 UI 投到主屏 ⇒ 用户看不见、以为卡死 ✗（项目里多次踩过"系统弹窗落主屏"）。
 *     所以：查 MediaStore → 自绘网格 → 点一张进编辑 ✓；没权限就提示去控制台授予
 *     「所有文件访问」✓（也不弹系统权限框 ✗，同理）。
 *  2. **编辑**：预览画布（按副屏比例 ✓）+ 两行工具栏 ✓
 *     · 双指缩放 + 拖动平移（[detectTransformGestures] ✓）
 *     · 「＋ / −」按钮微调缩放、「旋转 90°」、「复位」
 *     · 「裁剪」（把当前构图烘成新图，可继续调）、「保存」、「恢复默认壁纸」、「返回」
 *
 * ## 保存/预览"所见即所得"的关键
 * 预览（[drawWallpaper]）和烘焙（[bake]）用**同一套变换公式** ✓：
 *   基准缩放 = "让图片刚好铺满画布"的 cover 适配（**并且按旋转后的包围盒算** ✓，
 *   这样旋转 90° 后仍然铺满、不会露底色 ✗）；
 *   最终变换 = 基准 × 用户缩放 × 旋转 × 平移 ✓。
 * 烘焙按"预览画布尺寸 → 目标尺寸"等比放大绘制（`k = targetW / previewW` ✓），
 * 所以屏幕上看到什么，存下来就是什么 ✓。
 *
 * 背景声明：本页是**普通 Compose 页**（同树浮层里也安全 ✓），
 * 全程**不用任何系统 Dialog / 系统 Toast** ✗ —— 提示一律走 [CoverToast] ✓
 * （2038 悬浮窗上下文里弹系统窗口会崩或被静默丢弃 ✓，项目硬约束）。
 */
@Composable
fun WallpaperEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // null = 还在选图阶段；非 null = 正在编辑这张图
    // （「裁剪」会把烘焙结果**替换**成新的编辑对象 ✓，所以这里必须是可变的 state ✓）
    var source by remember { mutableStateOf<Bitmap?>(null) }

    val current = source
    if (current == null) {
        WallpaperPicker(
            context = context,
            onBack = onBack,
            onPicked = { source = it }
        )
    } else {
        // ★ key(current)：换图（含"裁剪"替换）时**重置编辑器内部状态** ✓
        //   —— 否则 tf（缩放/旋转/平移）会沿用上一张图的变换，
        //   用户会看到"裁剪完世界都歪了"的诡异效果 ✗
        androidx.compose.runtime.key(current) {
            WallpaperEditor(
                context = context,
                source = current,
                onReplace = { source = it },
                onBack = onBack
            )
        }
    }
}

// ======================================================================
// 阶段一：应用内选图（不用 SAF ✗）
// ======================================================================

@Composable
private fun WallpaperPicker(
    context: Context,
    onBack: () -> Unit,
    onPicked: (Bitmap) -> Unit
) {
    val scope = rememberCoroutineScope()
    // 每次回到本页/授完权后重查（用户可能在设置页里刚给了权限 ✓）
    var reloadTick by remember { mutableIntStateOf(0) }
    val hasAccess = remember(reloadTick) { WallpaperStore.hasImageAccess(context) }
    var loading by remember(reloadTick) { mutableStateOf(hasAccess) }
    var images by remember(reloadTick) { mutableStateOf<List<WallpaperStore.AlbumImage>>(emptyList()) }
    var custom by remember(reloadTick) { mutableStateOf(WallpaperStore.hasCustomWallpaper(context)) }
    var decoding by remember { mutableStateOf(false) }

    LaunchedEffect(hasAccess, reloadTick) {
        if (!hasAccess) {
            loading = false
            images = emptyList()
            return@LaunchedEffect
        }
        loading = true
        // 查相册是 IO：放到后台线程，别卡住副屏 UI ✓
        images = withContext(Dispatchers.IO) { WallpaperStore.queryAlbum(context) }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SportsCamBlack)
            .padding(6.dp)
    ) {
        // ---- 头部：返回 / 标题 / 当前状态 + 恢复默认 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onBack() }
                    .padding(4.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "选择壁纸图片",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (custom) "当前：自定义壁纸" else "当前：默认壁纸",
                color = if (custom) Accent.current else Color.White.copy(alpha = 0.45f),
                fontSize = 9.sp
            )
            Spacer(Modifier.weight(1f))
            SmallChip("恢复默认", enabled = custom) {
                WallpaperStore.reset(context)
                custom = false
                CoverToast.show(context, "已恢复默认壁纸")
            }
        }
        Spacer(Modifier.height(6.dp))

        when {
            // 没权限：给明确提示 + 一键去控制台/设置页授予「所有文件访问」✓（不弹系统权限框 ✗）
            !hasAccess -> NoPermissionHint(
                context = context,
                onRetry = { reloadTick++ }
            )

            loading -> CenterHint("正在读取相册…")

            images.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "相册里没有图片",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                SmallChip("重新读取") { reloadTick++ }
            }

            else -> Column(Modifier.fillMaxSize()) {
                Text(
                    "共 ${images.size} 张 · 点一张进入编辑（双指缩放/拖动，工具条可旋转/裁剪）",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 9.sp
                )
                Spacer(Modifier.height(4.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(images, key = { it.uri.toString() }) { img ->
                        AlbumTile(
                            context = context,
                            image = img,
                            onClick = {
                                if (decoding) return@AlbumTile
                                decoding = true
                                scope.launch {
                                    // 采样解码（最长边 ≤2048 ✓）防 OOM ✗
                                    val bmp = withContext(Dispatchers.IO) {
                                        WallpaperStore.loadAlbumBitmap(context, img.uri)
                                    }
                                    decoding = false
                                    if (bmp == null) {
                                        CoverToast.show(context, "这张图读不出来，换一张试试")
                                    } else {
                                        onPicked(bmp)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 相册网格里的一格：缩略图异步解码 ✓（每格自己 produceState，进出屏幕才解码 ✓） */
@Composable
private fun AlbumTile(
    context: Context,
    image: WallpaperStore.AlbumImage,
    onClick: () -> Unit
) {
    val thumb by produceState<ImageBitmap?>(initialValue = null, image.uri) {
        value = withContext(Dispatchers.IO) {
            // 缩略图最长边 240 就够（五列网格 ✓）—— 解码 240 比解码原图快一个数量级 ✓
            WallpaperStore.loadAlbumBitmap(context, image.uri, maxEdge = 240)?.asImageBitmap()
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(SportsCamSurfaceAlt)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val bmp = thumb
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp,
                contentDescription = image.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text("…", color = Color.White.copy(alpha = 0.25f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun NoPermissionHint(context: Context, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("需要「所有文件访问」权限才能读取相册图片", color = TorchAmber, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "我们不弹系统权限框（这台机器主屏没有面板，弹窗会落到看不见的地方 ✗）；" +
                "请点下面按钮到设置页手动授予，或去控制台开启。",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 9.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallChip("去授予权限") {
                openAllFilesAccessOnCover(context)
            }
            SmallChip("重新检测") { onRetry() }
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
    }
}

// ======================================================================
// 阶段二：编辑（预览 + 两行工具栏）
// ======================================================================

/** 画布变换：用户缩放（1 = 刚好铺满）、旋转角度、平移（画布像素） */
private data class WallpaperTransform(
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
)

@Composable
private fun WallpaperEditor(
    context: Context,
    source: Bitmap,
    onReplace: (Bitmap) -> Unit,
    onBack: () -> Unit
) {
    val (targetW, targetH) = remember { WallpaperStore.targetSize(context) }
    val canvasRatio = targetW.toFloat() / targetH.toFloat()

    var tf by remember { mutableStateOf(WallpaperTransform()) }
    // 预览画布的**实际像素尺寸**：烘焙时用它换算比例（所见即所得 ✓）
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }

    fun clamp(): WallpaperTransform {
        if (previewSize == IntSize.Zero) return tf
        return clampTransform(tf, source.width, source.height, previewSize.width.toFloat(), previewSize.height.toFloat())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SportsCamBlack)
            .padding(6.dp)
    ) {
        // ---- 头部 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onBack() }
                    .padding(4.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text("编辑壁纸", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                "双指缩放 / 拖动平移 · 目标 ${targetW}×${targetH}",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 9.sp
            )
        }
        Spacer(Modifier.height(4.dp))

        // ---- 预览区：按副屏比例的画布（居中、尽量大 ✓） ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(canvasRatio)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SportsCamSurface)                 // 深灰底（比棋盘格省事且不刺眼 ✓）
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .onSizeChanged { previewSize = it }
                    .pointerInput(source, previewSize) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            if (previewSize == IntSize.Zero) return@detectTransformGestures
                            tf = clampTransform(
                                tf.copy(
                                    scale = (tf.scale * zoom).coerceIn(1f, 6f),
                                    rotation = tf.rotation + rotation,
                                    offsetX = tf.offsetX + pan.x,
                                    offsetY = tf.offsetY + pan.y
                                ),
                                source.width, source.height,
                                previewSize.width.toFloat(), previewSize.height.toFloat()
                            )
                        }
                    }
            ) {
                drawWallpaper(source, tf)
            }
        }

        Spacer(Modifier.height(4.dp))

        // ---- 工具栏第 1 行：缩放 / 旋转 / 复位 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            SmallChip("缩小 −", modifier = Modifier.weight(1f)) {
                tf = clampTransform(
                    tf.copy(scale = (tf.scale / 1.15f).coerceAtLeast(1f)),
                    source.width, source.height,
                    previewSize.width.toFloat(), previewSize.height.toFloat()
                )
            }
            SmallChip("放大 ＋", modifier = Modifier.weight(1f)) {
                tf = clampTransform(
                    tf.copy(scale = (tf.scale * 1.15f).coerceAtMost(6f)),
                    source.width, source.height,
                    previewSize.width.toFloat(), previewSize.height.toFloat()
                )
            }
            SmallChip("旋转 90°", modifier = Modifier.weight(1f)) {
                val next = ((tf.rotation + 90f) % 360f + 360f) % 360f
                tf = clampTransform(
                    tf.copy(rotation = next),
                    source.width, source.height,
                    previewSize.width.toFloat(), previewSize.height.toFloat()
                )
            }
            SmallChip("复位", modifier = Modifier.weight(1f)) {
                tf = WallpaperTransform()
            }
        }
        Spacer(Modifier.height(4.dp))

        // ---- 工具栏第 2 行：裁剪 / 保存 / 恢复默认 / 返回 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            SmallChip("裁剪", modifier = Modifier.weight(1f), enabled = !busy) {
                // 把当前构图烘成新图（同比例 ✓）→ **替换编辑对象**（父级 key() 会重置变换 ✓），
                // 用户可以继续在这张已裁剪的图上缩放/旋转 ✓。
                val baked = bakeCurrent(context, source, tf, previewSize, targetW, targetH)
                if (baked == null) {
                    CoverToast.show(context, "预览还没准备好，稍等一下")
                } else {
                    CoverToast.show(context, "已裁剪（可继续调整）")
                    onReplace(baked)
                }
            }
            SmallChip("保存", modifier = Modifier.weight(1f), enabled = !busy) {
                if (previewSize == IntSize.Zero) {
                    CoverToast.show(context, "预览还没准备好，稍等一下")
                } else {
                    busy = true
                    val baked = bakeCurrent(context, source, tf, previewSize, targetW, targetH)
                    busy = false
                    if (baked == null) {
                        CoverToast.show(context, "处理失败，换个图片试试")
                    } else {
                        val ok = WallpaperStore.save(context, baked)
                        baked.recycle()
                        if (ok) {
                            CoverToast.show(context, "壁纸已保存")
                            onBack()
                        } else {
                            CoverToast.show(context, "保存失败，请重试")
                        }
                    }
                }
            }
            SmallChip("恢复默认", modifier = Modifier.weight(1f), enabled = !busy) {
                WallpaperStore.reset(context)
                CoverToast.show(context, "已恢复默认壁纸")
                onBack()
            }
            SmallChip("返回", modifier = Modifier.weight(1f)) { onBack() }
        }
    }
}

// 「裁剪」不再需要文件级暂存：它通过 `onReplace(baked)` 把新图交回父级，
// 父级用 `key(source)` 重置编辑器状态 ✓（早期草稿里的 bakedToUse 全局变量已删 ✗）。

// ======================================================================
// 变换数学（**预览与烘焙共用**，保证所见即所得 ✓）
// ======================================================================

/**
 * 基准缩放：让"旋转后的图片包围盒"刚好铺满画布（cover ✓）。
 *
 * 为什么按**旋转后**的包围盒算：旋转 90° 后图片的长宽互换，若仍按原尺寸算基准，
 * 图片会盖不满画布 ⇒ 预览出现底色空隙 ✗、存下来也是黑的 ✗。
 */
private fun baseScaleOf(
    srcW: Int, srcH: Int, canvasW: Float, canvasH: Float, rotationDeg: Float
): Float {
    val rad = Math.toRadians(rotationDeg.toDouble())
    val c = abs(cos(rad)).toFloat()
    val s = abs(sin(rad)).toFloat()
    val bboxW = srcW * c + srcH * s
    val bboxH = srcW * s + srcH * c
    if (bboxW <= 0f || bboxH <= 0f) return 1f
    return maxOf(canvasW / bboxW, canvasH / bboxH)
}

/** 平移夹紧：保证图片始终铺满画布（不会拖出底色 ✗） */
private fun clampTransform(
    tf: WallpaperTransform,
    srcW: Int, srcH: Int,
    canvasW: Float, canvasH: Float
): WallpaperTransform {
    if (canvasW <= 0f || canvasH <= 0f) return tf
    val base = baseScaleOf(srcW, srcH, canvasW, canvasH, tf.rotation)
    val total = base * tf.scale
    val rad = Math.toRadians(tf.rotation.toDouble())
    val c = abs(cos(rad)).toFloat()
    val s = abs(sin(rad)).toFloat()
    val bboxW = (srcW * c + srcH * s) * total
    val bboxH = (srcW * s + srcH * c) * total
    val maxX = maxOf(0f, (bboxW - canvasW) / 2f)
    val maxY = maxOf(0f, (bboxH - canvasH) / 2f)
    return tf.copy(
        offsetX = tf.offsetX.coerceIn(-maxX, maxX),
        offsetY = tf.offsetY.coerceIn(-maxY, maxY)
    )
}

/** 预览绘制：以画布中心为锚点 平移 → 旋转 → 缩放 ✓ */
private fun DrawScope.drawWallpaper(src: Bitmap, tf: WallpaperTransform) {
    val base = baseScaleOf(src.width, src.height, size.width, size.height, tf.rotation)
    val total = base * tf.scale
    withTransform({
        translate(size.width / 2f + tf.offsetX, size.height / 2f + tf.offsetY)
        rotate(tf.rotation, pivot = Offset.Zero)
        scale(total, total, pivot = Offset.Zero)
    }) {
        drawImage(src.asImageBitmap(), topLeft = Offset(-src.width / 2f, -src.height / 2f))
    }
}

/**
 * 烘焙：按"预览尺寸 → 目标尺寸"等比放大，**用与预览完全相同的变换** ✓。
 * 返回 null = 预览还没测到尺寸/异常 ✓。
 */
private fun bakeCurrent(
    context: Context,
    src: Bitmap,
    tf: WallpaperTransform,
    previewSize: IntSize,
    targetW: Int,
    targetH: Int
): Bitmap? {
    if (previewSize == IntSize.Zero || previewSize.width <= 0 || previewSize.height <= 0) return null
    return runCatching {
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        val k = targetW.toFloat() / previewSize.width.toFloat()
        canvas.scale(k, k)
        val pw = previewSize.width.toFloat()
        val ph = previewSize.height.toFloat()
        val base = baseScaleOf(src.width, src.height, pw, ph, tf.rotation)
        val total = base * tf.scale
        canvas.translate(pw / 2f + tf.offsetX, ph / 2f + tf.offsetY)
        canvas.rotate(tf.rotation)
        canvas.scale(total, total)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, -src.width / 2f, -src.height / 2f, paint)
        out
    }.onFailure {
        CoverDebugLog.log("Wallpaper", "烘焙失败：${it::class.java.simpleName} ${it.message}")
    }.getOrNull()
}

// ======================================================================
// 小控件 / 小工具
// ======================================================================

@Composable
private fun SmallChip(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        selected -> Accent.current.copy(alpha = 0.22f)
        !enabled -> SportsCamSurfaceAlt.copy(alpha = 0.5f)
        else -> SportsCamSurfaceAlt
    }
    val fg = when {
        !enabled -> Color.White.copy(alpha = 0.3f)
        selected -> Accent.current
        else -> Color.White.copy(alpha = 0.9f)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * 在**副屏**上打开「所有文件访问」设置页。
 *
 * 为什么必须投到副屏：主屏没有面板（副屏改造机），在主屏弹出的设置页用户根本看不到 ✗。
 * 与 `ScreenshotRecordScreen.openSettingsOnCover` 同款实现（那边是 private，所以这里复制一份 ✓）。
 */
private fun openAllFilesAccessOnCover(context: Context) {
    runCatching {
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val coverId = CoverDisplay.coverDisplayId(context)
        if (coverId == Display.DEFAULT_DISPLAY) {
            context.startActivity(intent)
        } else {
            val options = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(coverId).toBundle()
            context.startActivity(intent, options)
        }
    }.onFailure {
        CoverToast.show(context, "打不开设置页，请到系统设置里授予「所有文件访问」")
    }
}
