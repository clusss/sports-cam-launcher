package com.dsh.sportscam.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.sportscam.ui.theme.TorchAmber
import kotlinx.coroutines.delay

/**
 * 内建简易视频播放器（**悬浮窗里也能用**）。
 *
 * 为什么不直接用系统播放器 / `VideoView`：
 *  · 常驻悬浮窗没有 Activity，用 Intent 拉起系统播放器在副屏上很容易失败，
 *    或者把用户弹回 vivo 桌面；
 *  · `VideoView` 内部是 `SurfaceView`（独立图层），在
 *    `TYPE_APPLICATION_OVERLAY` 窗口里图层顺序不可靠 —— 常见表现是"有声音没画面"。
 *  · 所以用 **MediaPlayer + TextureView**：TextureView 直接画进所在窗口的图层，
 *    悬浮窗与普通 Activity 下都能正常显示。
 *
 * 提供的基本操作：播放/暂停、进度条拖动跳转、当前/总时长、关闭。
 */
@Composable
fun SimpleVideoPlayer(
    uri: Uri,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableIntStateOf(0) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 进度轮询：播放中 400ms 一次，暂停时 800ms 一次（够用又不费电）
    LaunchedEffect(prepared, playing) {
        while (prepared) {
            runCatching { player?.let { if (it.isPlaying || !playing) positionMs = it.currentPosition } }
            delay(if (playing) 400L else 800L)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }

    fun open(texture: SurfaceTexture) {
        runCatching {
            player?.release()
            errorText = null
            prepared = false
            val mp = MediaPlayer()
            mp.setDataSource(context, uri)
            mp.setSurface(Surface(texture))
            mp.setOnPreparedListener { p ->
                durationMs = p.duration.coerceAtLeast(0)
                if (p.videoWidth > 0 && p.videoHeight > 0) {
                    aspect = p.videoWidth.toFloat() / p.videoHeight.toFloat()
                }
                p.start()
                playing = true
                prepared = true
            }
            mp.setOnCompletionListener {
                playing = false
                positionMs = durationMs
            }
            mp.setOnErrorListener { _, what, extra ->
                errorText = "播放失败（$what/$extra）"
                prepared = false
                true
            }
            mp.prepareAsync()
            player = mp
        }.onFailure {
            errorText = "无法打开视频：${it.message}"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ---------------- 顶栏 ----------------
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close, "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // ---------------- 画面 ----------------
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (errorText != null) {
                Text(
                    text = errorText ?: "",
                    color = com.dsh.sportscam.ui.theme.Accent.current,
                    fontSize = 12.sp
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    st: SurfaceTexture, w: Int, h: Int
                                ) = open(st)

                                override fun onSurfaceTextureSizeChanged(
                                    st: SurfaceTexture, w: Int, h: Int
                                ) = Unit

                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    runCatching { player?.release() }
                                    player = null
                                    prepared = false
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                            }
                        }
                    }
                )
                if (!prepared) {
                    Text("加载中…", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        }

        // ---------------- 进度条 + 控制 ----------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.05f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Slider(
                value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
                onValueChange = { positionMs = it.toInt() },
                onValueChangeFinished = {
                    runCatching { player?.seekTo(positionMs) }
                },
                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                colors = SliderDefaults.colors(
                    // 进度条跟随"风格"强调色
                    thumbColor = com.dsh.sportscam.ui.theme.Accent.current,
                    activeTrackColor = com.dsh.sportscam.ui.theme.Accent.current,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                ),
                modifier = Modifier.fillMaxWidth().height(22.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable {
                            val p = player ?: return@clickable
                            if (p.isPlaying) {
                                p.pause()
                                playing = false
                            } else {
                                p.start()
                                playing = true
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${fmt(positionMs)} / ${fmt(durationMs)}",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun fmt(ms: Int): String {
    val total = (ms.coerceAtLeast(0)) / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
