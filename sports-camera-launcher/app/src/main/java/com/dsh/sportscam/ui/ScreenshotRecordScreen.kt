package com.dsh.sportscam.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.sportscam.cover.CoverDisplay
import com.dsh.sportscam.cover.CoverScreenRecorder
import com.dsh.sportscam.cover.CoverSettings
import com.dsh.sportscam.cover.CoverToast
import com.dsh.sportscam.cover.CoverTriggerAccessibilityService
import com.dsh.sportscam.cover.OrientationNotification
import com.dsh.sportscam.cover.RecordSettings
import com.dsh.sportscam.cover.ShizukuShell
import com.dsh.sportscam.ui.theme.Accent
import com.dsh.sportscam.ui.theme.SportsCamBlack
import com.dsh.sportscam.ui.theme.SportsCamSurface
import com.dsh.sportscam.ui.theme.SportsCamSurfaceAlt
import com.dsh.sportscam.ui.theme.TorchAmber
import kotlinx.coroutines.launch

/**
 * ============================================================================
 *  「截屏与录屏」设置（控制台 ② 辅助与测试功能 → 截屏与录屏）
 * ============================================================================
 *
 * 这一页把两件事收在一起（第二轮 D1 / B3）：
 *
 *  **截屏**
 *   · 「通知栏截屏按钮」开关 —— 语义仍然是 [CoverSettings.isScreenshotEnabled]
 *     （依赖无障碍服务 `takeScreenshot`；① 页那一项已迁到这里，不再有第二处开关）；
 *   · 保存位置 —— 新增，落在 [RecordSettings.screenshotDir]，[com.dsh.sportscam.cover.CoverScreenshot]
 *     两条通道（无障碍 / Shizuku 兜底）都读它，改完**立刻生效**（不需要重启）。
 *
 *  **录屏**（本轮新增）
 *   · 开关：是否在通知栏显示 ▶/■ 按钮；
 *   · 保存位置；帧率档位（30/60，**只改码率** —— 见下面文案）；麦克风（本机不支持，置灰）；
 *   · 状态：Shizuku 是否就绪 + 当前是否正在录屏，可直接开始/结束。
 *
 * ⚠ 界面约定（本项目硬约束）：
 *   1. 悬浮窗（2038）上下文里**不能**弹系统 Dialog/AlertDialog/Toast → 本页只用
 *      同树 Compose 组件（芯片/开关）与自绘 [CoverToast]；
 *   2. `Switch` 的 checked **不能**直接读 prefs（prefs 不是 Compose 状态，点了不刷新）
 *      → 用本地 `mutableStateOf` 驱动 + 变更后自增 `tick` 让整页重新取值；
 *   3. 不使用 `LocalConfiguration` 判断方向（那是踩过的坑），本页也用不到。
 */
@Composable
fun ScreenshotRecordScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var message by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    val shizuku by ShizukuShell.state.collectAsState()
    val a11yRunning = CoverTriggerAccessibilityService.running

    // 进页面时刷新一次 Shizuku 状态（adb 断开/重连后 binder 可能已经变了）
    LaunchedEffect(Unit) { runCatching { ShizukuShell.refresh(context) } }

    // 每次 tick 变化都重新取一次设置值（prefs 不是 Compose 状态，必须靠 tick 触发）
    val shotEnabled = remember(tick) { CoverSettings.isScreenshotEnabled(context) }
    val shotDir = remember(tick) { RecordSettings.screenshotDir(context) }
    val recEnabled = remember(tick) { RecordSettings.isRecordEnabled(context) }
    val recDir = remember(tick) { RecordSettings.recordDir(context) }
    // 第四轮第 2 项：档位从"帧率(30/60)"改成**码率(Mbps)**（默认 8）
    val bitrateMbps = remember(tick) { RecordSettings.bitRateMbps(context) }
    // 第四轮追加：麦克风（真录，MediaRecorder + 机内合并）；未授权时整项置灰
    val micEnabled = remember(tick) { RecordSettings.isMicEnabled(context) }
    val micGranted = remember(tick) { CoverScreenRecorder.isMicAvailable(context) }
    val muxing = remember(tick) { CoverScreenRecorder.isMuxing }
    val recording = remember(tick) { CoverScreenRecorder.isRecording(context) }
    val activePath = remember(tick) { RecordSettings.activePath(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SportsCamBlack)
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
            Text("截屏与录屏", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (shizuku.usable) "Shizuku 已就绪" else "Shizuku 未就绪",
                color = if (shizuku.usable) Color(0xFF32D74B) else TorchAmber,
                fontSize = 10.sp
            )
            Spacer(Modifier.weight(1f))
            if (message.isNotEmpty()) {
                Text(message, color = TorchAmber, fontSize = 10.sp, maxLines = 1)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
                .padding(bottom = 10.dp)
        ) {
            // ==============================================================
            // 一、截屏
            // ==============================================================
            Section("截屏") {
                ToggleRow(
                    label = "显示通知栏「截屏」按钮",
                    sub = if (a11yRunning)
                        "截屏走无障碍服务（不需要 Shizuku）；截到的图存到下面的目录"
                    else "无障碍服务未开启：截屏能力来自它，开启后才能打开本开关",
                    checked = shotEnabled && a11yRunning,
                    enabled = a11yRunning
                ) { v ->
                    CoverSettings.setScreenshotEnabled(context, v)
                    // 立刻刷新通知栏那一行的显隐（不要绕过"方向通知关掉了"的判断）
                    OrientationNotification.showIfEnabled(context, "截屏开关切换")
                    message = if (v) "截屏按钮已显示在通知栏" else "截屏按钮已隐藏"
                    tick++
                }
                if (!a11yRunning) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "无障碍服务（CoverTriggerAccessibilityService）没在跑 → 截屏开关置灰。",
                        color = TorchAmber, fontSize = 9.sp
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("去开启无障碍") { openSettingsOnCover(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) }
                        Chip("重新检测") {
                            ShizukuShell.refresh(context)
                            tick++
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "保存位置（改完立刻生效，不需要重启）",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RecordSettings.SCREENSHOT_DIR_OPTIONS.forEach { dir ->
                        Chip(
                            text = shortDir(dir),
                            selected = shotDir == dir
                        ) {
                            RecordSettings.setScreenshotDir(context, dir)
                            message = "截屏目录：$dir"
                            tick++
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "当前：$shotDir" + if (shotDir == RecordSettings.DEFAULT_SCREENSHOT_DIR)
                        "（与系统截屏同相簿）" else "",
                    color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            // ==============================================================
            // 二、录屏
            // ==============================================================
            Section("录屏") {
                ToggleRow(
                    label = "显示通知栏录屏按钮（▶ / ■）",
                    sub = if (shizuku.usable)
                        "通知栏只有一个按钮：未录屏 ▶、录屏中 ■，点它就是开始/结束"
                    else "录屏必须用 Shizuku（screenrecord 只有 shell 能跑）：未就绪时按钮不显示",
                    checked = recEnabled,
                    // 没有 Shizuku 就不让开：开了也不会有按钮（隐藏而不是点了没反应）
                    enabled = shizuku.usable
                ) { v ->
                    RecordSettings.setRecordEnabled(context, v)
                    OrientationNotification.showIfEnabled(context, "录屏开关切换")
                    message = if (v) "通知栏录屏按钮已显示" else "通知栏录屏按钮已隐藏"
                    tick++
                }

                if (!shizuku.usable) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = when {
                            !shizuku.installed -> "未安装 Shizuku → 录屏不可用（点击播放/录制需要 shell 权限）。"
                            !shizuku.binderAlive -> "Shizuku 服务未运行 → 录屏不可用。先去 Shizuku 里启动服务。"
                            !shizuku.granted -> "Shizuku 已运行但未授权本应用 → 点下面「申请授权」。"
                            else -> "Shizuku 未就绪 → 录屏不可用。"
                        },
                        color = TorchAmber, fontSize = 9.sp
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("申请授权", enabled = shizuku.binderAlive && !shizuku.granted) {
                            ShizukuShell.requestPermission()
                            message = "已请求 Shizuku 授权"
                        }
                        Chip("重新检测") {
                            ShizukuShell.refresh(context)
                            message = "已重新检测 Shizuku"
                            tick++
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "保存位置",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RecordSettings.RECORD_DIR_OPTIONS.forEach { dir ->
                        Chip(text = shortDir(dir), selected = recDir == dir) {
                            RecordSettings.setRecordDir(context, dir)
                            message = "录屏目录：$dir"
                            tick++
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "当前：$recDir",
                    color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    "码率档位（录制清晰度）",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp
                )
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RecordSettings.BITRATE_TIERS_MBPS.forEach { mbps ->
                        Chip(
                            text = if (mbps == RecordSettings.DEFAULT_BITRATE_MBPS)
                                "${mbps} Mbps（推荐）" else "$mbps Mbps",
                            selected = bitrateMbps == mbps
                        ) {
                            RecordSettings.setBitRateMbps(context, mbps)
                            message = "码率：${mbps} Mbps"
                            tick++
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                // ★ 真机实测结论（第四轮第 2 项，2026-10-11）——必须如实写清楚：
                //   · `screenrecord` **没有**帧率/profile 参数 → 帧率由副屏刷新率与
                //     "画面有没有变化"决定（静止画面实测"3 秒只有 1 帧"）；
                //   · 录制**不做任何缩放**（--verbose：Content area is 682x422 at offset x=0 y=0），
                //     所以"糊"不是缩放造成的；成片就是副屏原生 682x422，
                //     在 1080p 以上屏幕全屏播放本来就会偏软；
                //   · `--bit-rate` 是上限：同样一段滑动，8M→16M 产物几乎翻倍（画质更好 ✓），
                //     16M→32M 对这种内容已饱和。所以嫌糊优先上 16M。
                Text(
                    "⚠ 说明：本机 screenrecord 没有帧率/画质参数，档位就是真实码率。" +
                        "静止画面几乎不产帧（实测 3 秒只有 1 帧），动起来才吃码率；" +
                        "录屏不做缩放，成片就是副屏原生 682×422，在大屏全屏播放会偏软。" +
                        "实测同样动作：8M→产物约 0.7MB/7s，16M→1.3MB（更清晰），32M 提升已很小。",
                    color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp
                )

                Spacer(Modifier.height(8.dp))
                // ★ 第四轮追加：**麦克风是真的能录**（MediaRecorder，与显示器无关）：
                //   顺序是"先启麦克风 → 再启录屏"，两个时刻都用 elapsedRealtime 记下来，
                //   结束录屏后按这个偏移在**机内**合并成一条 MP4（MediaExtractor + MediaMuxer）。
                //   ⚠ 未授权时**不弹系统权限框**（主屏没有面板，弹了用户看不见 ✗）——
                //     权限由装机时 `adb shell pm grant cn.pashiguoke.subscreen RECORD_AUDIO` 授予，
                //     未授予就只录画面，并把原因写在这里与日志里（绝不静默）。
                ToggleRow(
                    label = "录制麦克风（环境声/人声）",
                    sub = if (micGranted)
                        "先启麦克风再启录屏，结束后自动合并成一条 MP4（对齐误差几十毫秒）"
                    else
                        "未授予录音权限（RECORD_AUDIO）→ 本次仅录画面；" +
                            "权限由装机时 pm grant 授予（本机不弹权限框，弹窗会落在没面板的主屏）",
                    checked = micEnabled,
                    enabled = micGranted
                ) { v ->
                    RecordSettings.setMicEnabled(context, v)
                    message = if (v) "已开启麦克风录制（下次开始录屏生效）" else "已关闭麦克风录制"
                    tick++
                }

                Spacer(Modifier.height(6.dp))
                // ★ 第四轮第 9 项结论：**应用内声音做不到**（与麦克风是两件事，别混淆）
                Text(
                    "应用内声音：本机系统限制，录不到。" +
                        "（screenrecord 无任何音频参数：--audio / --audio-source / --mic " +
                        "实测全部 unrecognized option；MediaProjection 的 AudioPlaybackCapture " +
                        "只能跟默认显示器绑定，与副屏录制不是一回事）",
                    color = TorchAmber, fontSize = 9.sp
                )

                Spacer(Modifier.height(8.dp))
                // ---------- 开始 / 结束 ----------
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = when {
                            muxing -> "合并声音中…"
                            recording -> "录制中"
                            else -> "未录屏"
                        },
                        color = when {
                            muxing -> TorchAmber
                            recording -> Accent.current
                            else -> Color.White.copy(alpha = 0.7f)
                        },
                        fontSize = 11.sp
                    )
                    Chip(
                        text = if (recording) "结束录屏 ■" else "开始录屏 ▶",
                        enabled = shizuku.usable && !muxing,
                        selected = recording
                    ) {
                        scope.launch {
                            if (recording) {
                                when (val r = CoverScreenRecorder.stop(context)) {
                                    is CoverScreenRecorder.StopResult.Ok -> {
                                        message = "已保存录屏${if (r.note.isNotEmpty()) "·${r.note}" else ""}"
                                        CoverToast.show(
                                            context,
                                            "录屏已保存（${r.sizeBytes / 1024}KB）" +
                                                if (r.note.isNotEmpty()) "·${r.note}" else ""
                                        )
                                    }
                                    is CoverScreenRecorder.StopResult.Failure -> {
                                        message = "结束失败：${r.reason}"
                                        CoverToast.show(context, "结束录屏失败：${r.reason}")
                                    }
                                }
                            } else {
                                when (val r = CoverScreenRecorder.start(context)) {
                                    // ★ 第四轮第 6 项：开始成功**不弹提示**（CoverToast 是副屏覆盖窗，
                                    //   会被 screenrecord 录进视频开头 ✗）；失败才提示。
                                    is CoverScreenRecorder.StartResult.Ok -> {
                                        message = "录制中…（提示已关闭，避免被录进开头）"
                                    }
                                    is CoverScreenRecorder.StartResult.Failure -> {
                                        message = "开始失败：${r.reason}"
                                        CoverToast.show(context, "开始录屏失败：${r.reason}")
                                    }
                                }
                            }
                            tick++
                        }
                    }
                    Chip("刷新状态") { tick++ }
                }
                if (activePath.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "当前文件：$activePath",
                        color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "⚠ 录屏期间不要锁屏、也不要切走太久：screenrecord 受系统限制，" +
                        "息屏/切屏后录到的可能是黑屏。" +
                        "结束后会先发 SIGINT、等文件写完 moov 再扫描（实测进程 0.85~1.2s 退出），" +
                        "所以点完 ■ 后稍等一下相册才会出现视频；开了麦克风的话还会多一步" +
                        "「机内合并」（几秒级，期间按钮显示 ⋯）。",
                    color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            // ==============================================================
            // 三、原理说明（排查用）
            // ==============================================================
            Section("说明（排查用）") {
                Text(
                    "· 录屏走 Shizuku 的 `screenrecord --display-id <物理 id>`：" +
                        "副屏不是默认显示器，MediaProjection 只录主屏，所以只能走 shell。",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "· 物理 id 是运行时算出来的（dumpsys display 的 physicalPort ↔ " +
                        "dumpsys SurfaceFlinger 的 port 对照）；逻辑 displayId 传进去会报 " +
                        "Invalid physical display ID。",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "· 取消暂停功能：screenrecord 不支持暂停/继续，所以要停就停、要录就新开一段。",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp
                )
            }
        }
    }
}

// ----------------------------------------------------------------------
// 本页私有小组件（与 ConsoleScreen 的视觉保持一致；那些组件是私有的，不能跨文件用）
// ----------------------------------------------------------------------

@Composable
private fun Section(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SportsCamSurface)
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        Text(title, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Chip(
    text: String,
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
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    sub: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    // 本地状态驱动 Switch：点击立即翻转；同时回调写设置。
    // （直接读 prefs 会导致"点了不变色，退出重进才变"—— 见公共约定 2）
    var local by remember(checked) { mutableStateOf(checked) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = Color.White.copy(alpha = if (enabled) 0.9f else 0.35f),
                fontSize = 11.sp
            )
            Text(
                sub,
                color = Color.White.copy(alpha = if (enabled) 0.4f else 0.22f),
                fontSize = 9.sp
            )
        }
        Switch(
            checked = local && enabled,
            enabled = enabled,
            onCheckedChange = { value ->
                local = value
                onCheckedChange(value)
            }
        )
    }
}

/** 长路径只显示最后两段，避免把副屏那一行撑爆 */
private fun shortDir(dir: String): String {
    val parts = dir.trim('/').split('/')
    return if (parts.size >= 2) "${parts[parts.size - 2]}/${parts.last()}" else dir
}

/**
 * 在**副屏**上打开一个系统设置页。
 *
 * 为什么必须投到副屏：主屏没有面板（这是改造过的 vivo X Flip 副屏设备），
 * 在主屏启动的设置页用户根本看不到。
 */
private fun openSettingsOnCover(context: Context, action: String) {
    runCatching {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val coverId = CoverDisplay.coverDisplayId(context)
        if (coverId == Display.DEFAULT_DISPLAY) {
            context.startActivity(intent)
        } else {
            val options = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(coverId).toBundle()
            context.startActivity(intent, options)
        }
    }.onFailure {
        CoverToast.show(context, "打不开该设置页")
    }
}
