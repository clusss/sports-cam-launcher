package com.dsh.sportscam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 运动相机风格主色板 */
val SportsCamBlack = Color(0xFF0A0A0A)      // 深色背景
val SportsCamSurface = Color(0xFF15171B)    // 卡片底色
val SportsCamSurfaceAlt = Color(0xFF1B1D21) // 次级面板（缩略图占位等）
val SportsCamAccent = Color(0xFFFF3B30)     // 运动相机红
val TorchAmber = Color(0xFFFFC107)          // 手电筒开启黄

private val SportsCamColors = darkColorScheme(
    primary = SportsCamAccent,
    onPrimary = Color.White,
    background = SportsCamBlack,
    onBackground = Color.White,
    surface = SportsCamSurface,
    onSurface = Color.White,
    surfaceVariant = SportsCamSurfaceAlt,
    onSurfaceVariant = Color.White
)

/**
 * 主题。
 *
 * ★ `primary` 接的是 [Accent.current]（风格强调色），不是固定的 [SportsCamAccent]。
 *
 * 为什么必须这样做：Material3 的 Slider / Switch / RadioButton / TextButton / Checkbox
 * 默认都用 `colorScheme.primary` 上色。之前只把强调色接到少数几处显式颜色上，
 * 于是出现两个症状（用户实测反馈）：
 *   1. 「取消」「恢复」这类没显式指定颜色的按钮始终是主题里的固定红；
 *   2. 控制台里的滑条/开关切换风格后不刷新，要退出重进才变。
 * 接进主题后，[Accent.current] 是 Compose 状态，风格一变整棵树自动重组 ✓。
 */
@Composable
fun SportsCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SportsCamColors.copy(primary = Accent.current),
        content = content
    )
}
