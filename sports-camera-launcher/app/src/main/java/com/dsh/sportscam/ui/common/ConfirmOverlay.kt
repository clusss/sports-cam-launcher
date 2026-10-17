package com.dsh.sportscam.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.dsh.sportscam.ui.theme.Accent

/**
 * 二次确认浮层（**同一棵组合树里的浮层，不是 Dialog**）。
 *
 * ⚠ 为什么不用 `AlertDialog`：它会新建 `TYPE_APPLICATION`(2) 窗口，而常驻悬浮窗的
 * 窗口上下文是 `TYPE_APPLICATION_OVERLAY`(2038)，系统会抛窗口类型不匹配并**崩进程**
 * —— 表现是"点一下界面整个消失、回到桌面"。所以本项目所有弹窗都走这种同树浮层。
 *
 * 调用方需把它放在 `Box` 作用域内（本函数 fillMaxSize 铺满）。
 */
@Composable
fun ConfirmOverlay(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    dismissText: String = "取消"
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 190.dp, max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1C1C1E))
                // 面板本体吃掉点击，避免穿透到遮罩
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = message,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 「取消」给它一个中性色，不跟着强调色走（避免和"确认"抢注意力）
                TextButton(onClick = onDismiss) {
                    Text(dismissText, fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
                }
                TextButton(onClick = onConfirm) {
                    // 确认按钮（删除类提示）跟随"风格"强调色
                    Text(confirmText, fontSize = 12.sp, color = Accent.current)
                }
            }
        }
    }
}
