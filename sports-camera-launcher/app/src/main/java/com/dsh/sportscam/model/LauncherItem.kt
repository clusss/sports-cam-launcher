package com.dsh.sportscam.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 轮播中的一个选项。
 *
 * @param id      稳定标识（Compose key / 测试用）
 * @param label   卡片下方文字
 * @param icon    大图标
 * @param accent  该选项的强调色（选中描边 / 发光 / 图标着色）
 * @param kind    行为分类
 */
data class LauncherItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    val kind: Kind
) {
    enum class Kind { CAMERA, ALBUM, ALBUM3, VIDEO, TORCH }
}

/**
 * 8 个选项，顺序即轮播顺序：
 * 拍摄 → 相簿1 → 相簿2 → 相簿3 → 视频1 → 视频2 → 视频3 → 手电筒
 */
object LauncherItems {

    /** Pager 初始页：停在「拍摄」，方便快速启动相机 */
    const val DEFAULT_INDEX = 0
    const val CAMERA_INDEX = 0
    const val ALBUM1_INDEX = 1
    const val ALBUM2_INDEX = 2
    const val ALBUM3_INDEX = 3
    const val VIDEO1_INDEX = 4
    const val VIDEO2_INDEX = 5
    const val VIDEO3_INDEX = 6
    const val TORCH_INDEX = 7

    val all: List<LauncherItem> = listOf(
        LauncherItem("camera", "拍摄", Icons.Filled.PhotoCamera, Color(0xFFFF3B30), LauncherItem.Kind.CAMERA),
        LauncherItem("album1", "相簿 1", Icons.Filled.PhotoLibrary, Color(0xFF3B9BFF), LauncherItem.Kind.ALBUM),
        LauncherItem("album2", "相簿 2", Icons.Filled.PhotoLibrary, Color(0xFF5AC8FA), LauncherItem.Kind.ALBUM),
        LauncherItem("album3", "相簿 3", Icons.Filled.PhotoLibrary, Color(0xFFB06BFF), LauncherItem.Kind.ALBUM3),
        LauncherItem("video1", "视频 1", Icons.Filled.Videocam, Color(0xFF32D74B), LauncherItem.Kind.VIDEO),
        LauncherItem("video2", "视频 2", Icons.Filled.Videocam, Color(0xFF30D158), LauncherItem.Kind.VIDEO),
        LauncherItem("video3", "视频 3", Icons.Filled.Videocam, Color(0xFF66D4CF), LauncherItem.Kind.VIDEO),
        LauncherItem("torch", "手电筒", Icons.Filled.FlashlightOn, Color(0xFFFFC107), LauncherItem.Kind.TORCH)
    )
}
