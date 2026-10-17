package com.dsh.sportscam.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.dsh.sportscam.cover.CoverSettings

/**
 * 强调色（"风格"）。
 *
 * 作用范围：**删除类文本与按钮、多选框、控制台的滑条/进度类小控件、警告文本**
 * 等一切"有颜色"的元素。
 *
 * ⚠ 刻意**不影响**主界面（轮播）的白色图标与文字 —— 那里的视觉是固定需求：
 * 白图标 + 手电筒开启时的黄色。所以手电筒用的 [TorchAmber] 不参与本套色板。
 *
 * 用 Compose 可观察的全局状态持有：控制台改完立即生效，不需要重启界面。
 */
object Accent {

    data class Style(val key: String, val label: String, val color: Color)

    val styles = listOf(
        Style("red", "红", Color(0xFFFF453A)),
        Style("yellow", "黄", Color(0xFFFFB020)),
        Style("green", "绿", Color(0xFF32D74B)),
        Style("blue", "蓝", Color(0xFF3B82F6))
    )

    private const val DEFAULT_KEY = "red"

    /** 当前强调色（Compose 可观察） */
    var current: Color by mutableStateOf(styles.first().color)
        private set

    var currentKey: String by mutableStateOf(DEFAULT_KEY)
        private set

    /** 从设置里同步一次；由 SportsCamApp 每次组合时调用（很便宜） */
    fun sync(context: Context) {
        val key = CoverSettings.accentKey(context)
        if (key != currentKey) {
            currentKey = key
            current = styles.firstOrNull { it.key == key }?.color ?: styles.first().color
        }
    }

    /** 在非 Compose 侧（例如创建窗口/画笔）取当前色 */
    fun colorOf(context: Context): Color =
        styles.firstOrNull { it.key == CoverSettings.accentKey(context) }?.color
            ?: styles.first().color
}
