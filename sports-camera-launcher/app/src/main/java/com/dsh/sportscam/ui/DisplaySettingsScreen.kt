package com.dsh.sportscam.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.sportscam.cover.CoverDisplay
import com.dsh.sportscam.cover.DisplayPresetStore
import com.dsh.sportscam.cover.ShizukuShell
import com.dsh.sportscam.cover.rememberCoverDisplayId
import com.dsh.sportscam.ui.theme.Accent
import com.dsh.sportscam.ui.theme.SportsCamBlack
import com.dsh.sportscam.ui.theme.SportsCamSurface
import com.dsh.sportscam.ui.theme.SportsCamSurfaceAlt
import com.dsh.sportscam.ui.theme.TorchAmber
import kotlinx.coroutines.launch

/**
 * 副屏「分辨率 / DPI」设置。
 *
 * 变化（这一版）：
 *  1. **恢复文本输入** —— 输入法抢焦点的问题修好之后（悬浮窗补上 NOT_FOCUSABLE），
 *     不再需要用 ± 步进按钮绕开输入法；
 *  2. **分辨率与 DPI 成组保存、成组套用** —— 两者互相影响，
 *     `宽 × 高 @ dpi` 作为一个预设整体记忆，点一下全部套用；
 *  3. 命令仍与原版逐字一致：
 *     `cmd window size <宽>x<高> -d <id>` / `cmd window density <dpi> -d <id>`。
 */
@Composable
fun DisplaySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coverId by rememberCoverDisplayId()

    var message by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    val shizukuReady = ShizukuShell.state.collectAsState().value.usable

    // 待套用/保存的一组值（文本输入，可自由键入）
    var widthText by remember { mutableStateOf("682") }
    var heightText by remember { mutableStateOf("422") }
    var dpiText by remember { mutableStateOf("200") }
    var inited by remember { mutableStateOf(false) }

    var currentSize by remember { mutableStateOf("") }
    var currentDpi by remember { mutableStateOf("") }
    var savedGroups by remember { mutableStateOf(DisplayPresetStore.customGroups(context)) }

    val coverInfo = remember(coverId, tick) { CoverDisplay.info(context, coverId) }

    LaunchedEffect(coverId, tick) {
        if (!inited && coverInfo != null) {
            widthText = coverInfo.widthPx.toString()
            heightText = coverInfo.heightPx.toString()
            if (coverInfo.densityDpi > 0) dpiText = coverInfo.densityDpi.toString()
            inited = true
        }
        if (shizukuReady) {
            currentSize = DisplayPresetStore.querySize(coverId)?.replace("\n", " / ") ?: ""
            currentDpi = DisplayPresetStore.queryDensity(coverId)?.replace("\n", " / ") ?: ""
        }
    }

    val width = widthText.toIntOrNull()
    val height = heightText.toIntOrNull()
    val dpi = dpiText.toIntOrNull()
    val valuesOk = width != null && height != null && dpi != null &&
        width in 200..8000 && height in 200..8000 && dpi in 72..1200

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SportsCamBlack)
            .imePadding()
    ) {
        // ---------------- 顶栏 ----------------
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text("分辨率 / DPI", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (shizukuReady) "Shizuku 已就绪" else "Shizuku 未就绪（改不了）",
                color = if (shizukuReady) Color(0xFF32D74B) else TorchAmber,
                fontSize = 10.sp
            )
            Spacer(Modifier.weight(1f))
            if (message.isNotEmpty()) {
                Text(message, color = TorchAmber, fontSize = 10.sp, maxLines = 1)
            }
        }

        // ---------------- 输入区（一组值一起编辑、一起套用） ----------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SportsCamSurface)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumField("宽", widthText, 62.dp) { widthText = it }
                Spacer(Modifier.width(6.dp))
                Text("×", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                NumField("高", heightText, 62.dp) { heightText = it }
                Spacer(Modifier.width(12.dp))
                NumField("dpi", dpiText, 58.dp) { dpiText = it }

                Spacer(Modifier.width(12.dp))
                Chip("应用这组", enabled = shizukuReady && valuesOk) {
                    val w = width ?: return@Chip
                    val h = height ?: return@Chip
                    val d = dpi ?: return@Chip
                    scope.launch {
                        val ok = DisplayPresetStore.applyGroup(coverId, w, h, d)
                        message = if (ok) "已套用 ${w}x$h@$d" else "套用失败"
                        // 逻辑尺寸/密度变了：请保活服务重建悬浮窗，
                        // 否则常驻悬浮窗可能保留旧尺寸，出现拉伸/错位
                        if (ok) com.dsh.sportscam.cover.CoverKeeperService.recreateOverlay(context)
                        tick++
                    }
                }
                Spacer(Modifier.width(6.dp))
                Chip("保存这组", enabled = valuesOk) {
                    val w = width ?: return@Chip
                    val h = height ?: return@Chip
                    val d = dpi ?: return@Chip
                    DisplayPresetStore.saveCustomGroup(context, w, h, d)
                    savedGroups = DisplayPresetStore.customGroups(context)
                    message = "已保存 ${w}x$h@$d"
                }
                Spacer(Modifier.width(6.dp))
                Chip("还原出厂", enabled = shizukuReady) {
                    scope.launch {
                        val ok = DisplayPresetStore.reset(coverId)
                        message = if (ok) "已还原出厂" else "还原失败"
                        tick++
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "物理 ${coverInfo?.resolution ?: "-"} · ${coverInfo?.densityDpi ?: "-"}dpi" +
                    "     当前 ${currentSize.ifBlank { "-" }} / ${currentDpi.ifBlank { "-" }}",
                color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp, maxLines = 2
            )
            if (!valuesOk) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "宽/高 需 200~8000，dpi 需 72~1200",
                    color = Accent.current, fontSize = 9.sp
                )
            }
        }

        // ---------------- 已保存的组合 ----------------
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SportsCamSurface)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                "已保存的组合（点击整组套用，长按删除）",
                color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (savedGroups.isEmpty()) {
                    Text(
                        "（还没有保存过：填好宽/高/dpi 后点「保存这组」）",
                        color = Color.White.copy(alpha = 0.3f), fontSize = 9.sp
                    )
                }
                savedGroups.forEach { preset ->
                    SavedChip(
                        text = "${preset.width}x${preset.height}@${preset.dpi}",
                        enabled = shizukuReady,
                        onClick = {
                            widthText = preset.width.toString()
                            heightText = preset.height.toString()
                            dpiText = preset.dpi.toString()
                            scope.launch {
                                val ok = DisplayPresetStore.applyGroup(
                                    coverId, preset.width, preset.height, preset.dpi
                                )
                                message = if (ok) "已套用 ${preset.name}" else "套用失败"
                                if (ok) com.dsh.sportscam.cover.CoverKeeperService.recreateOverlay(context)
                                tick++
                            }
                        },
                        onLongClick = {
                            DisplayPresetStore.removeCustomGroup(
                                context, preset.width, preset.height, preset.dpi
                            )
                            savedGroups = DisplayPresetStore.customGroups(context)
                            message = "已删除 ${preset.name}"
                        }
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------

/**
 * 紧凑数字输入框。
 *
 * 只保留数字、最多 5 位（避免输入异常值）；`imePadding()` 保证输入法弹出时
 * 这一行不会被挡住。
 */
@Composable
private fun NumField(
    label: String,
    value: String,
    width: Dp,
    onValueChange: (String) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 10.sp,
            modifier = Modifier.width(22.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = { s -> onValueChange(s.filter { it.isDigit() }.take(5)) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = SolidColor(Accent.current),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .width(width)
                .clip(RoundedCornerShape(8.dp))
                .background(SportsCamSurfaceAlt)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun Chip(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (enabled) SportsCamSurfaceAlt else SportsCamSurfaceAlt.copy(alpha = 0.5f)
    val fg = if (enabled) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.3f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedChip(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // 「当前生效」的组合高亮：跟随「风格（强调色）」
    val bg = if (enabled) Accent.current.copy(alpha = 0.16f) else SportsCamSurfaceAlt.copy(alpha = 0.5f)
    val fg = if (enabled) Accent.current else Color.White.copy(alpha = 0.3f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .combinedClickable(
                enabled = enabled,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
