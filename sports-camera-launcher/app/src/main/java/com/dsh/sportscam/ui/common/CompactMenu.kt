package com.dsh.sportscam.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 副屏上的紧凑菜单。
 *
 * 屏幕只有 682×422，Material 默认的 AlertDialog 尺寸偏大、行距也偏松，
 * 所以统一用这个：小字号、紧行距、深色底。
 *
 * ⚠ **刻意不使用 `AlertDialog` / `Dialog`**：它们会新建一个 `TYPE_APPLICATION`(2) 窗口，
 * 而常驻悬浮窗所在的窗口上下文类型是 `TYPE_APPLICATION_OVERLAY`(2038)，
 * 系统会抛：
 *   `IllegalArgumentException: Window type mismatch. Window Context's window type is 2038,
 *    while LayoutParams' type is set to 2`
 * 直接**崩进程** —— 表现就是"点一下界面整个消失、回到 vivo 桌面"（实测踩过）。
 *
 * 现在改成**同一棵组合树里的全屏浮层**：Activity 下和悬浮窗下行为完全一致，
 * 也不存在窗口类型问题。调用方需把它放在一个 `Box` 作用域内（本函数会 fillMaxSize）。
 */
@Composable
fun CompactMenuDialog(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
    /** 可选副标题（比如应用包名/来源） */
    subtitle: String? = null,
    scrollable: Boolean = false
) {
    val scrimInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // 点遮罩关闭；不显示涟漪
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 200.dp, max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1C1C1E))
                // 菜单本体吃掉点击，避免穿透到遮罩导致误关
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
            }
            Spacer(Modifier.height(6.dp))

            val content: @Composable () -> Unit = {
                Column {
                    actions.forEach { (label, action) -> MenuRow(label, action) }
                }
            }
            if (scrollable) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) { content() }
            } else {
                content()
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 12.sp) }
        }
    }
}

/** 菜单里的一行 */
@Composable
fun MenuRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 6.dp)
    )
}
