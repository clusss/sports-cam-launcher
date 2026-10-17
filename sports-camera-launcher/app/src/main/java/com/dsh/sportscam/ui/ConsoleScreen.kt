package com.dsh.sportscam.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dsh.sportscam.cover.CoverAppLauncher
import com.dsh.sportscam.cover.CameraModeSchedule
import com.dsh.sportscam.cover.CoverDebugLog
import com.dsh.sportscam.cover.CoverDisplay
import com.dsh.sportscam.cover.CoverKeeperService
import com.dsh.sportscam.cover.CoverSettings
import com.dsh.sportscam.cover.CoverTriggerAccessibilityService
import com.dsh.sportscam.cover.DisplayPresetStore
import com.dsh.sportscam.cover.OrientationActionReceiver
import com.dsh.sportscam.cover.OrientationController
import com.dsh.sportscam.cover.OrientationNotification
import com.dsh.sportscam.cover.ScreenOrientationOverlay
import com.dsh.sportscam.cover.ShizukuShell
import com.dsh.sportscam.cover.rememberCoverDisplayId
import com.dsh.sportscam.syncNavStackHomeToCameraMode
import com.dsh.sportscam.ui.common.NumberPadOverlay
import com.dsh.sportscam.ui.common.PasswordPadOverlay
import com.dsh.sportscam.ui.common.PrivateAccessGuardHost
import com.dsh.sportscam.ui.common.rememberPrivateAccessGuard
import com.dsh.sportscam.ui.common.showToast
import com.dsh.sportscam.ui.theme.SportsCamBlack
import com.dsh.sportscam.ui.theme.SportsCamSurface
import com.dsh.sportscam.ui.theme.SportsCamSurfaceAlt
import com.dsh.sportscam.ui.theme.TorchAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 控制台的页面。
 *
 * 「母界面 + 3 子界面」结构：
 *  - [Home]     母界面：只放 3 个子界面入口 + 「最近删除」；
 *  - [Personal] ① 界面与个性化
 *  - [Tools]    ② 辅助与测试功能
 *  - [Guard]    ③ 运动相机防查
 *
 * 页面状态由 [ConsoleScreen] 内部持有（`remember`）：母界面的 `refreshTick` / `message`
 * 等状态在四个页面之间天然共享，切换页面不会丢。
 */
enum class ConsolePage {
    Home,
    Personal,
    Tools,
    Guard,

    /** ④ 隐私与安全（第七轮新增）：启动器密码 / 私密应用 / 最近删除 */
    Privacy
}

/**
 * 「启动器密码」的设置流程（控制台 ② 页）。
 *
 * 一次只进行一步；每步都是一个同树浮层（见 [PasswordPadOverlay]）。
 */
private enum class PwdFlow {
    /** 没有正在进行的流程 */
    None,

    /**
     * 修改密码第 0 步：**先验证旧密码**（第六轮第 4 项）。
     * 没设过密码时跳过这一步直接到 [SetFirst]。
     */
    VerifyOld,

    /** 设置密码第 1 步：输入新密码 */
    SetFirst,

    /** 设置密码第 2 步：再次输入（二次确认，两次必须一致） */
    SetConfirm,

    /** 关闭密码：先验证一次当前密码 */
    DisableVerify
}

/**
 * 副屏控制台（母界面 + 3 子界面）。
 *
 * 集中《超级小副屏》那套能力，按用途分成三组：
 *  - ① 界面与个性化：分辨率 / DPI、风格（强调色）、拍摄用哪个相机、离开是否关灯
 *  - ② 辅助与测试：Shizuku、副屏信息、投放 / 文件权限 / 无障碍、屏幕方向、保活
 *  - ③ 运动相机防查：退出回主界面、悬浮窗防退出模式、自动拉起、主界面静音
 *
 * 母界面不再直接列出全部开关，只做入口。
 */
@Composable
fun ConsoleScreen(
    onBack: () -> Unit,
    onOpenDisplaySettings: () -> Unit,
    /** 「最近删除」管理（按需求：这个入口**只在控制台**） */
    onOpenTrash: () -> Unit = {},
    /** 「截屏与录屏」设置界面（第二轮 B3：① 页的截屏设置已迁进那个新界面） */
    onOpenScreenshotRecord: () -> Unit = {},
    /**
     * 「私密应用入口」（第八轮）：直接进 list-viewer 的私密应用界面
     * （`Screen.PrivateApps`）。该界面内部自带密码门 ⇒ 这里**不要再拦一次** ✗。
     */
    onOpenPrivateApps: () -> Unit = {},
    /**
     * 「桌面壁纸」入口（第十轮第 4 项）：进 `Screen.Wallpaper`
     * （`ui/WallpaperEditorScreen.kt`，recorder-statusbar 做）。
     */
    onOpenWallpaper: () -> Unit = {},
    /**
     * 「制作者与开源地址」（第十四轮）：进 `Screen.About`
     * （界面本体就是本文件的 [AboutScreen]）。
     */
    onOpenAbout: () -> Unit = {},
    /**
     * 是否**主屏大屏模式**（第十五轮）：在主屏打开本应用时直接进控制台，
     * 并在标题旁标一行灰小字「主屏模式 · 控制台」（副屏模式恒 false ⇒ 界面与以前完全一致 ✓）。
     */
    mainScreenMode: Boolean = false,
    /** 初始页面：默认母界面；外部不需要传 */
    page: ConsolePage = ConsolePage.Home
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val shizuku by ShizukuShell.state.collectAsState()
    val coverId by rememberCoverDisplayId()

    // 当前所在页：母界面为 Home，点子界面入口后切到对应页，返回键回到 Home
    var currentPage by remember { mutableStateOf(page) }

    var message by remember { mutableStateOf("") }
    var refreshTick by remember { mutableIntStateOf(0) }

    // 「拍摄长按启动第二层应用」的单选应用浮层开关
    var showAppPicker by remember { mutableStateOf(false) }

    // 「定时计划」编辑浮层（③ 运动相机防查；第六轮第 3 项）
    var showScheduleEditor by remember { mutableStateOf(false) }

    // ---------------- 「私密应用」（第七轮；第八轮改为跳转界面） ----------------
    /**
     * 私密应用访问门：**每次"启用私密应用"都要重新验证密码**。
     * 没有密码时它显示引导（只给"返回"和"去设置密码"），**绝不放行**。
     *
     * ⚠ 第八轮：「私密应用入口」不再走这里 —— 它直接进 `ui/PrivateAppsScreen.kt`，
     *   由那个界面内部的 `PrivateAccessGate` 把关（避免连着输两遍密码 ✗）。
     */
    val privateGuard = rememberPrivateAccessGuard()
    /** 用户是"点开启 → 被引导去设密码"进来的：设完密码后自动把私密应用打开 */
    var pendingEnablePrivateAfterSet by remember { mutableStateOf(false) }

    // 「启动器密码」设置流程（② 辅助与测试功能）
    var pwdFlow by remember { mutableStateOf(PwdFlow.None) }
    var pwdFirst by remember { mutableStateOf("") }
    var pwdNotice by remember { mutableStateOf<String?>(null) }

    // ★★ 密码相关状态一律用**本地 Compose 状态**持有，不在组合里直接读 prefs
    //   （公共约定 2）。上一轮的 bug 正是"设置完密码要退出设置页重进才看到状态更新" ——
    //   因为 `checked` 直接读了 SharedPreferences，它**不是** Compose 状态。
    //   现在每次写入都立刻更新这里的本地状态（写入点都会调 syncPasswordState()）。
    var hasPw by remember { mutableStateOf(CoverSettings.hasPassword(context)) }
    var pwMasterOn by remember { mutableStateOf(CoverSettings.isPasswordEnabled(context)) }
    var guardConsole by remember { mutableStateOf(CoverSettings.isPasswordGuardConsole(context)) }
    var guardAppList by remember { mutableStateOf(CoverSettings.isPasswordGuardAppList(context)) }
    var guardDesktop by remember { mutableStateOf(CoverSettings.isPasswordGuardDesktopEdit(context)) }
    // 第十轮：第 4 个保护范围「保护最近删除」
    var guardTrash by remember { mutableStateOf(CoverSettings.isPasswordGuardTrash(context)) }

    /** 从 prefs 重新读一遍密码相关的全部状态（任何写入之后都要调一次） */
    val syncPasswordState: () -> Unit = {
        hasPw = CoverSettings.hasPassword(context)
        pwMasterOn = CoverSettings.isPasswordEnabled(context)
        guardConsole = CoverSettings.isPasswordGuardConsole(context)
        guardAppList = CoverSettings.isPasswordGuardAppList(context)
        guardDesktop = CoverSettings.isPasswordGuardDesktopEdit(context)
        guardTrash = CoverSettings.isPasswordGuardTrash(context)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        message = if (granted) "已允许通知" else "未允许通知（前台服务通知不会显示）"
    }

    var currentSize by remember { mutableStateOf<String?>(null) }
    var currentDensity by remember { mutableStateOf<String?>(null) }
    var displayRotationText by remember { mutableStateOf<String?>(null) }
    var overlayOrientation by remember { mutableIntStateOf(ScreenOrientationOverlay.current) }

    val coverInfo = remember(coverId, refreshTick) { CoverDisplay.info(context, coverId) }
    val canOverlay = remember(refreshTick) { ScreenOrientationOverlay.canDrawOverlays(context) }
    val notifEnabled = remember(refreshTick) { CoverSettings.isOrientationNotifEnabled(context) }
    // ★ 运动相机模式总开关（默认开）。关闭时 ③ 页的防查子项全部置灰锁定；
    //   这里直接读 prefs —— refreshTick 变化会重组本界面，读到的就是最新值。
    val cameraMode = CoverSettings.isCameraModeEnabled(context)
    // vivo 对新生应用默认关闭通知（实测 POST_NOTIFICATION=ignore），这里要显式提示
    val appNotificationsEnabled = remember(refreshTick) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    LaunchedEffect(coverId, refreshTick) {
        if (coverId != 0 && shizuku.usable) {
            currentSize = DisplayPresetStore.querySize(coverId)
            currentDensity = DisplayPresetStore.queryDensity(coverId)
            displayRotationText = OrientationController.queryDisplayRotation(coverId)
        }
    }

    LaunchedEffect(Unit) { ShizukuShell.refresh(context) }

    /** 返回按钮：子界面 → 母界面；母界面 → 退出控制台 */
    val goBack: () -> Unit = {
        if (currentPage == ConsolePage.Home) onBack() else currentPage = ConsolePage.Home
    }

    /** 子界面标题（母界面用默认标题） */
    val pageTitle = when (currentPage) {
        ConsolePage.Home -> "副屏控制台"
        ConsolePage.Personal -> "界面与个性化"
        ConsolePage.Tools -> "辅助与测试功能"
        ConsolePage.Guard -> "运动相机防查"
        ConsolePage.Privacy -> "隐私与安全"
    }

    Box(Modifier.fillMaxSize().background(SportsCamBlack)) {
      Column(Modifier.fillMaxSize()) {
        // ---------------- 顶栏 ----------------
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = goBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            // 注：这里原来有一个「← 回控制台」按钮，已删除 —— 左上角的 ← 箭头
            // 本身就会返回（Home 时退出控制台，子页时回母界面），按钮是冗余的。
            Text(pageTitle, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            // 第十五轮：主屏大屏模式的明显标识（副屏不显示 ⇒ 界面零变化 ✓）
            if (mainScreenMode) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "主屏模式 · 控制台",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }
            Spacer(Modifier.weight(1f))
            if (message.isNotEmpty()) {
                Text(message, color = com.dsh.sportscam.ui.theme.Accent.current, fontSize = 10.sp, maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
            ActionChip("刷新") { refreshTick++; ShizukuShell.refresh(context) }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // ==============================================================
            // 母界面：4 个入口（最近删除已迁进 ④ 隐私与安全）
            // ==============================================================
            if (currentPage == ConsolePage.Home) {
                item {
                    Section("启动器控制台") {
                        Text(
                            clampIntro("四个分组，点进去就能调。"),
                            color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        EntryRow(
                            title = "界面与个性化",
                            sub = "分辨率、强调色、相机、长按、离开行为"
                        ) { currentPage = ConsolePage.Personal }
                        EntryRow(
                            title = "辅助与测试功能",
                            sub = "Shizuku、副屏、权限、无障碍、方向、保活、录屏"
                        ) { currentPage = ConsolePage.Tools }
                        EntryRow(
                            title = "运动相机防查",
                            sub = "回主界面、悬浮窗、自动拉起、静音、定时"
                        ) { currentPage = ConsolePage.Guard }
                        // 第七轮：启动器密码 / 私密应用 / 最近删除 都迁到这一页
                        EntryRow(
                            title = "隐私与安全",
                            sub = "密码、私密应用、最近删除"
                        ) { currentPage = ConsolePage.Privacy }
                    }
                }
                // 第十四轮：母界面**最下方**「制作者与开源地址」入口
                item {
                    Section("关于") {
                        EntryRow(
                            title = "制作者与开源地址",
                            sub = "作者、鸣谢、GitHub、更新"
                        ) { onOpenAbout() }
                    }
                }
                // ⚠ 「最近删除」原来在这里，第七轮已**迁进 ④ 隐私与安全**，此处刻意不留重复入口。
            }

            // ==============================================================
            // ① 界面与个性化
            // ==============================================================
            if (currentPage == ConsolePage.Personal) {
                // 分辨率 / DPI 设置（入口）
                item {
                    Section("分辨率 / DPI") {
                        Text(
                            clampIntro("调副屏分辨率和清晰度，改完重新投屏生效"),
                            color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(5.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ActionChip("分辨率 / DPI 设置") { onOpenDisplaySettings() }
                        }
                    }
                }

                // 风格（强调色）
                item {
                    Section("风格（强调色）") {
                        // 用户要求：说明要"面向用户、简洁"（原来那段 60+ 字开发者文已删）
                        Text(
                            clampIntro("选中后按钮、勾选框等界面会变色"),
                            color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            com.dsh.sportscam.ui.theme.Accent.styles.forEach { style ->
                                ActionChip(
                                    text = style.label,
                                    selected = CoverSettings.accentKey(context) == style.key
                                ) {
                                    CoverSettings.setAccentKey(context, style.key)
                                    com.dsh.sportscam.ui.theme.Accent.sync(context)
                                    message = "风格已切换为${style.label}色"
                                    refreshTick++
                                }
                            }
                        }
                    }
                }

                // 拍摄使用的相机应用
                item {
                    Section("拍摄使用的相机应用") {
                        Text(
                            "点击[拍摄]启动的相机软件",
                            color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        val cameras = remember(refreshTick) {
                            listOf(
                                android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
                                android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA,
                                android.provider.MediaStore.ACTION_IMAGE_CAPTURE
                            ).flatMap { action ->
                                runCatching {
                                    context.packageManager
                                        .queryIntentActivities(android.content.Intent(action), 0)
                                        .mapNotNull { res ->
                                            val p = res.activityInfo?.packageName
                                            if (p == null) null
                                            else p to res.loadLabel(context.packageManager).toString()
                                        }
                                }.getOrDefault(emptyList())
                            }.distinctBy { (pkg, _) -> pkg }
                                .sortedBy { (_, label) -> label }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ActionChip(
                                text = "自动",
                                selected = CoverSettings.cameraPackage(context).isEmpty()
                            ) {
                                CoverSettings.setCameraPackage(context, "")
                                message = "拍摄：自动"
                                refreshTick++
                            }
                            cameras.forEach { (pkg, label) ->
                                ActionChip(
                                    text = label,
                                    selected = CoverSettings.cameraPackage(context) == pkg
                                ) {
                                    CoverSettings.setCameraPackage(context, pkg)
                                    message = "拍摄：$label"
                                    refreshTick++
                                }
                            }
                        }
                    }
                }

                // ⚠ 原有「通知栏截屏按钮」开关已**迁走**（第二轮 B3）：它现在在
                //   「② 辅助与测试功能 → 截屏与录屏 → 截屏与录屏设置」那个新界面里
                //   （ui/ScreenshotRecordScreen.kt）。这里刻意**不留**重复项，
                //   否则两个地方都能开关同一个设置，容易打架。

                // 长按「拍摄」启动第二层应用（与上面「短按打开哪个相机」是两件事）
                item {
                    Section("长按[拍摄]启动应用") {
                        val lpPkg = CoverSettings.cameraLongPressPackage(context)
                        val lpLabel = remember(refreshTick, lpPkg) {
                            if (lpPkg.isEmpty()) ""
                            else runCatching {
                                context.packageManager
                                    .getApplicationLabel(context.packageManager.getApplicationInfo(lpPkg, 0))
                                    .toString()
                            }.getOrDefault(lpPkg)
                        }
                        ToggleRow(
                            label = "启用长按「拍摄」",
                            sub = "长按[拍摄]直接打开另一个应用",
                            checked = CoverSettings.isCameraLongPressEnabled(context)
                        ) { v ->
                            CoverSettings.setCameraLongPressEnabled(context, v)
                            message = if (v) "" else ""
                            refreshTick++
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ActionChip(if (lpPkg.isEmpty()) "选择应用" else "更换应用") {
                                showAppPicker = true
                            }
                            if (lpPkg.isEmpty()) {
                                Text(
                                    "尚未选择：长按无动作",
                                    color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp
                                )
                            } else {
                                Text(
                                    "当前：$lpLabel",
                                    color = com.dsh.sportscam.ui.theme.Accent.current, fontSize = 9.sp
                                )
                                ActionChip("清除") {
                                    CoverSettings.setCameraLongPressPackage(context, "")
                                    message = "已清除长按应用"
                                    refreshTick++
                                }
                            }
                        }
                    }
                }

                // ⚠ 第九轮：按用户要求**删除了**「非运动相机界面不使用悬浮窗」这个设置项
                //   （`Section("悬浮窗显示范围")` 整块已移除，不是置灰）。
                //   原因（真机实证）：悬浮窗本身就是应用列表/控制台这些页面的**宿主**，
                //   在我们的页面里收起它必然先闪出 vivo 桌面、随后又被"桌面在前台"判定拉回来
                //   （日志实证 10 次 hide→onResume→挂出→show 循环）⇒ 现有架构下做不到，
                //   留着只会误导用户。**也不再提供任何出口提示**（见 ③ 页注释）。

                // 主界面显示项（第十轮）：主界面时钟开关 + 桌面壁纸入口
                item {
                    Section("主界面显示") {
                        // ⚠ 渲染不在这里 —— 时钟由 `ui/CarouselScreen.kt`（desktop-ui）读
                        //   `CoverSettings.isShowClockOnHome(context)` 自己画；本页只放开关。
                        ToggleRow(
                            label = "主界面显示时钟（24 小时制）",
                            sub = if (CoverSettings.isShowClockOnHome(context))
                                "开：左上角显示 24 小时制时钟"
                            else "关：左上角不显示时钟",
                            checked = CoverSettings.isShowClockOnHome(context)
                        ) { v ->
                            CoverSettings.setShowClockOnHome(context, v)
                            message = if (v) "主界面时钟：开" else "主界面时钟：关"
                            refreshTick++
                        }
                        Spacer(Modifier.height(5.dp))
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 第十轮第 4 项：壁纸编辑器（界面本体由 recorder-statusbar 新建）
                            ActionChip("桌面壁纸") { onOpenWallpaper() }
                            Text(
                                "换主界面的背景图",
                                color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp
                            )
                        }
                    }
                }

                // 离开启动器时关闭手电筒
                item {
                    Section("手电筒") {
                        ToggleRow(
                            label = "离开启动器时关闭手电筒",
                            sub = null,
                            checked = CoverSettings.isTorchOffOnLeave(context)
                        ) { v ->
                            CoverSettings.setTorchOffOnLeave(context, v)
                            message = if (v) "离开关灯：开" else "离开关灯：关"
                        }
                    }
                }
            }

            // ==============================================================
            // ② 辅助与测试功能
            // ==============================================================
            if (currentPage == ConsolePage.Tools) {
                // Shizuku 状态检测（B 修正：原来写成「shizuku状态检测」，用户要求规范写法）
                item {
                    Section("Shizuku 状态检测") {
                        val status = when {
                            !shizuku.installed -> "未安装 Shizuku"
                            !shizuku.binderAlive -> "已安装，但服务未运行"
                            !shizuku.granted -> "服务已运行，未授权本应用"
                            else -> "已就绪（v${shizuku.version}）"
                        }
                        Text(
                            status,
                            color = if (shizuku.usable) Color(0xFF32D74B) else com.dsh.sportscam.ui.theme.Accent.current,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(5.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ActionChip("申请授权", enabled = shizuku.binderAlive && !shizuku.granted) {
                                ShizukuShell.requestPermission()
                                message = "已请求授权"
                            }
                            ActionChip("重新检测") {
                                ShizukuShell.refresh(context)
                                message = "已重新检测"
                            }
                        }
                        if (!shizuku.usable) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                clampIntro("先打开 Shizuku 启动服务，再回来授权"),
                                color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 自动息屏（悬浮窗模式下系统不判空闲 → 屏幕不会自动灭，很耗电）
                item {
                    Section("自动息屏") {
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        val curMs = CoverSettings.screenTimeoutMs(context)
                        Text(
                            clampIntro("设置副屏多久没操作就熄屏（省电）"),
                            color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            com.dsh.sportscam.cover.CoverScreenTimeout.PRESETS.forEach { (label, ms) ->
                                ActionChip(text = label, selected = curMs == ms) {
                                    CoverSettings.setScreenTimeoutMs(context, ms)
                                    scope.launch {
                                        message = com.dsh.sportscam.cover.CoverScreenTimeout
                                            .apply(context, ms)
                                        refreshTick++
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        // B/C 修正：用户给的新文（并规范 Shizuku 写法）
                        Text(
                            clampIntro("该设置项需要 Shizuku 授权，无授权则无法生效"),
                            color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )

                        // ★ 没有 Shizuku 时，必须能授予「修改系统设置」—— 否则上面点了档位也写不进系统。
                        val canWrite = android.provider.Settings.System.canWrite(context)
                        val shizukuOk = com.dsh.sportscam.cover.ShizukuShell.isUsable
                        if (!canWrite && !shizukuOk) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ActionChip("授予「修改系统设置」") {
                                    openSettingsOnCover(
                                        context,
                                        Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                            .setData(Uri.parse("package:${context.packageName}"))
                                    )
                                }
                                Text(
                                    "授权后回来点档位即可写入系统超时",
                                    color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp
                                )
                            }
                        }
                    }
                }

                // 副屏信息 + 投放 / 文件权限 / 无障碍
                item {
                    Section("副屏") {
                        if (coverInfo == null) {
                            Text("未检测到副屏（只有一块屏）", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        } else {
                            Text(
                                "副屏 ${coverInfo.resolution}px · ${coverInfo.densityDpi}dpi · 旋转${coverInfo.rotation}",
                                color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp
                            )
                            if (currentSize != null || currentDensity != null) {
                                Text(
                                    "当前：${currentSize?.replace("\n", " ") ?: "-"} / ${currentDensity?.replace("\n", " ") ?: "-"}",
                                    color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp
                                )
                            }
                            Spacer(Modifier.height(5.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ActionChip("把自己投到副屏") {
                                    scope.launch {
                                        val r = CoverAppLauncher.launchSelfToCover(context, coverId)
                                        message = r.toString()
                                    }
                                }
                                // 删除媒体需要"所有文件访问"权限（见 MediaTrash 注释）
                                // B 修正：去掉「」后的多余空格
                                ActionChip("授予「所有文件访问」权限") {
                                    openSettingsOnCover(
                                        context,
                                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                            .setData(Uri.parse("package:${context.packageName}"))
                                    )
                                }
                                ActionChip("无障碍设置") {
                                    openSettingsOnCover(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            }
                        }
                    }
                }

                // 屏幕方向（仅通知栏按钮调节）
                item {
                    Section("屏幕方向调节") {
                        // 用户要求：简洁、面向用户（原来那段「只保留一个入口…两套状态打架」已删）
                        Text(
                            clampIntro("方向在通知栏用按钮调，点一下转一格"),
                            color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        ToggleRow(
                            label = "通知栏显示方向开关",
                            sub = null,
                            checked = notifEnabled
                        ) { v ->
                            CoverSettings.setOrientationNotifEnabled(context, v)
                            if (v) OrientationNotification.show(context) else OrientationNotification.cancel(context)
                            message = if (v) "方向通知已开启" else "方向通知已关闭"
                            refreshTick++
                        }
                        if (!appNotificationsEnabled) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "⚠ 本应用通知被关闭，方向开关/保活通知都不会显示",
                                    color = TorchAmber, fontSize = 9.sp
                                )
                                ActionChip("去开启应用通知") {
                                    openSettingsOnCover(
                                        context,
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    )
                                }
                            }
                        }
                        if (displayRotationText != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "显示器旋转锁定状态：$displayRotationText",
                                color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp
                            )
                        }
                    }
                }

                // 桌面设置 / 自动投屏 / 保活
                item {
                    Section("启动器保活设置") {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ActionChip("打开系统「主屏幕」设置") {
                                openSettingsOnCover(context, Intent(Settings.ACTION_HOME_SETTINGS))
                            }
                            Text(
                                "把「运动相机」设为副屏启动器（是否可选取决于系统）",
                                color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        }
                        ToggleRow(
                            label = "主屏拉起时自动投到副屏",
                            sub = null,
                            checked = CoverSettings.isAutoPlaceOnStart(context)
                        ) { v ->
                            CoverSettings.setAutoPlaceOnStart(context, v)
                            message = if (v) "已开启自动投屏" else "已关闭自动投屏"
                        }
                        ToggleRow(
                            label = "常驻前台服务（保活）",
                            sub = "点保活通知可回到启动器",
                            checked = CoverSettings.isKeeperEnabled(context)
                        ) { v ->
                            CoverSettings.setKeeperEnabled(context, v)
                            if (v) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                CoverKeeperService.start(context)
                            } else {
                                CoverKeeperService.stop(context)
                            }
                            message = if (v) "保活已开启" else "保活已关闭"
                        }
                    }
                }
            }

            // ==============================================================
            // ④ 隐私与安全（第七轮新增）
            //   本块：启动器密码（**从 ② 辅助与测试功能迁来**，原位置不留重复项）
            //   下面的另一块：私密应用 / 最近删除
            // ==============================================================
            if (currentPage == ConsolePage.Privacy) {
                // 启动器密码（四位数字；默认不启用）
                // ⚠ 设置项刻意放在 ② 辅助与测试功能；拦截点：
                //   MainActivity（控制台 / 总应用列表）与 SquareHomeScreen（桌面编辑模式）。
                // ★ 第二轮 B1：保护范围拆成**三个互不干扰的开关**（默认都关），
                //   判定统一走 PasswordGate.needsPassword(context, Purpose.X)。
                item {
                    Section("启动器密码") {
                        // 用户要求：面向用户、简洁（原来「sha256(密码+固定盐)」等开发者措辞已删）
                        Text(
                            clampIntro("开启后进下面勾选的入口都要输 4 位密码；忘记只能重装"),
                            color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(5.dp))
                        ToggleRow(
                            label = "启用启动器密码",
                            sub = if (!hasPw) "先点下面「设置密码」"
                            else if (pwMasterOn) "已启用：勾选保护的入口进入前要输密码"
                            else "已设置但未启用（入口都不会被拦）",
                            checked = pwMasterOn && hasPw,
                            // 没设置密码时无法启用（开了也没哈希可校验，等于把入口锁死）
                            enabled = hasPw
                        ) { v ->
                            CoverSettings.setPasswordEnabled(context, v)
                            syncPasswordState()   // ★ 立即刷新本地状态（不必退出重进）
                            message = if (v) "密码已启用" else "密码已停用（已设置的密码保留）"
                            refreshTick++
                        }

                        Spacer(Modifier.height(6.dp))
                        Text(
                            clampIntro("选择要保护哪几个入口（互不影响，默认都关）"),
                            color = Color.White.copy(alpha = 0.65f), fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        ToggleRow(
                            label = "保护「控制台」",
                            sub = "进控制台前要输密码",
                            checked = guardConsole,
                            // 没设密码时置灰（勾了也没有密码可校验）
                            enabled = hasPw
                        ) { v ->
                            CoverSettings.setPasswordGuardConsole(context, v)
                            guardConsole = v
                            message = if (v) "已保护：控制台" else "已取消保护：控制台"
                        }
                        ToggleRow(
                            label = "保护「总应用列表」",
                            sub = "进应用列表前要输密码",
                            checked = guardAppList,
                            enabled = hasPw
                        ) { v ->
                            CoverSettings.setPasswordGuardAppList(context, v)
                            guardAppList = v
                            message = if (v) "已保护：总应用列表" else "已取消保护：总应用列表"
                        }
                        ToggleRow(
                            label = "保护「桌面编辑模式」",
                            sub = "拖图标、编辑桌面之前要输密码",
                            checked = guardDesktop,
                            enabled = hasPw
                        ) { v ->
                            CoverSettings.setPasswordGuardDesktopEdit(context, v)
                            guardDesktop = v
                            message = if (v) "已保护：桌面编辑模式" else "已取消保护：桌面编辑模式"
                        }
                        // 第十轮：第 4 个保护范围 —— 与上面三个完全同款（默认关、未设密码置灰）
                        ToggleRow(
                            label = "保护「最近删除」",
                            sub = "进最近删除前要输密码",
                            checked = guardTrash,
                            enabled = hasPw
                        ) { v ->
                            CoverSettings.setPasswordGuardTrash(context, v)
                            guardTrash = v
                            message = if (v) "已保护：最近删除" else "已取消保护：最近删除"
                        }

                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ActionChip(if (hasPw) "修改密码" else "设置密码") {
                                pwdFirst = ""
                                pwdNotice = null
                                // ★ 第六轮第 4 项：改密码前**先验证旧密码**（没设过就跳过）
                                pwdFlow = if (hasPw) PwdFlow.VerifyOld else PwdFlow.SetFirst
                            }
                            ActionChip("关闭密码", enabled = hasPw) {
                                pwdFirst = ""
                                pwdNotice = null
                                // 关闭前必须验证一次当前密码（防"顺手点一下就关了"）
                                pwdFlow = PwdFlow.DisableVerify
                            }
                        }
                    }
                }
            }

            // ==============================================================
            // ② 辅助与测试功能（续）—— 只留「截屏与录屏」入口
            //   ⚠ 启动器密码已迁到 ④ 隐私与安全（见上面的块），这里不再有它 ✓
            // ==============================================================
            if (currentPage == ConsolePage.Tools) {
                // 截屏与录屏（第二轮 B3）：真正的设置界面由 recorder-statusbar 新建
                // （ui/ScreenshotRecordScreen.kt）。① 页原来的「通知栏截屏按钮」已迁进去，
                // 这里只留一个跳转入口（不要再放重复的开关）。
                item {
                    Section("截屏与录屏") {
                        Text(
                            "截屏（通知栏按钮、保存位置）与录屏（Shizuku screenrecord " +
                                "--display-id 副屏、帧率/码率、麦克风）的设置都在这里。",
                            color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp
                        )
                        Spacer(Modifier.height(5.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ActionChip("截屏与录屏设置") { onOpenScreenshotRecord() }
                        }
                    }
                }
            }

            // ==============================================================
            // ③ 运动相机防查
            // ==============================================================
            if (currentPage == ConsolePage.Guard) {
                // ---------- 总开关：运动相机模式 ----------
                // 关掉它 = 本应用退回"普通启动器"：进启动器直接进桌面，
                // 且下面所有防查能力**不生效**（但**保留用户的意愿**，再次打开模式即生效，见 CoverSettings）。
                // ⚠ 改这里的逻辑务必同步 CoverKeeperService.syncOverlayWindow 与 MainActivity 的启动页判断。
                item {
                    Section("运动相机模式总开关") {
                        ToggleRow(
                            label = "运动相机模式",
                            sub = if (cameraMode)
                                "开：进入启动器直接进运动相机主界面"
                            else "关（默认）：直接进桌面；防查功能暂不生效",
                            checked = cameraMode
                        ) { v ->
                            CoverSettings.setCameraModeEnabled(context, v)
                            // ★ 第六轮第 2 项：关闭时**不再改写**用户的防查选项，
                            //   只做"把已经挂出来的悬浮窗当场收掉"（意愿留在 prefs，再次打开模式即生效）。
                            if (!v) collapseGuardOverlays(context)
                            // 共享导航栈的底页跟着模式走（关 → 桌面；开 → 运动相机主界面），
                            // 否则用户会话中途关掉后一路返回还会落回运动相机主界面
                            syncNavStackHomeToCameraMode(v)
                            message = if (v) "运动相机模式已开启" else "运动相机模式已关闭"
                            refreshTick++
                        }
                        if (!cameraMode) {
                            Spacer(Modifier.height(4.dp))
                            // 用户要求：删掉所有「重开即恢复」类说法，改成一句面向用户的提示
                            Text(
                                clampIntro("关闭时下面这些防查项暂不生效（原来的选择都留着）"),
                                color = TorchAmber, fontSize = 9.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                item {
                    Section("其它运动相机防查模式自定义设置") {
                        ToggleRow(
                            label = "退出应用后回到主界面",
                            sub = if (!cameraMode)
                                "再次进入总是回到桌面"
                            else if (CoverSettings.isReturnToHomeOnReentry(context))
                                "开：再次进入总是回到运动相机主页" else "关：保留上一次停留的界面",
                            checked = CoverSettings.isReturnToHomeOnReentry(context)
                        ) { v ->
                            CoverSettings.setReturnToHomeOnReentry(context, v)
                            message = if (v) "退出后回到主界面" else "退出后保留上次界面"
                            refreshTick++
                        }
                        ToggleRow(
                            label = "强制悬浮窗防退出模式（bug多但防查强）",
                            // ⚠️ 该模式（overlay mode）已知问题较多：在副屏**直接**启动应用会被 vivo
                            //    拦截、窗口"存活过久"后启动也被拦、启动前重建悬浮窗又会闪屏并重置
                            //    导航状态。默认关闭。详细踩坑记录见 CoverAppLauncher.launchPackageToCover
                            //    开头的那段注释。改动涉及它的逻辑时务必在"关闭该模式"下也回归一遍。
                            sub = if (!cameraMode)
                                "运动相机模式关闭 → 暂不生效"
                            else if (CoverSettings.isOverlayModeEnabled(context))
                                "开：盖住 vivo 桌面防退出，问题较多慎用"
                            else "关：普通界面；开启后「自动拉起」才可用",
                            checked = CoverSettings.isOverlayModeEnabled(context),
                            // ★ 运动相机模式关闭 → 防查能力整体下线，本项置灰锁定
                            enabled = cameraMode
                        ) { v ->
                            CoverSettings.setOverlayModeEnabled(context, v)
                            if (!v) {
                                // 父项关闭 → 子项一并关闭，避免留下"父关子开"的悬空状态
                                CoverSettings.setAutoPullEnabled(context, false)
                            }
                            message = if (v) "悬浮窗防退出模式已开启" else "已关闭（同时关闭自动拉起）"
                            refreshTick++
                        }
                        ToggleRow(
                            label = "回到桌面自动拉起悬浮窗",
                            sub = if (!cameraMode)
                                "运动相机模式关闭 → 暂不生效"
                            else if (!CoverSettings.isOverlayModeEnabled(context))
                                "需先开启「悬浮窗防退出模式」"
                            else if (CoverTriggerAccessibilityService.running) "无障碍服务：已开启"
                            else "无障碍服务：未开启（去系统设置里打开）",
                            checked = CoverSettings.isOverlayModeEnabled(context) &&
                                // ⚠ 这里必须用**不带屏幕闸**的 isAutoPullArmed：
                                //   用户此刻正站在控制台里（不在 Carousel），
                                //   若用 isAutoPullEnabled 会把开关显示成"关"✗
                                //   （那个带屏幕闸的值是给保活/拉起路径用的）。
                                CoverSettings.isAutoPullArmed(context),
                            // ★ 以「悬浮窗防退出模式」为前置：父项关闭时本项置灰锁定，无法开启；
                            //   运动相机模式关闭时同样整体锁定
                            enabled = cameraMode && CoverSettings.isOverlayModeEnabled(context)
                        ) { v ->
                            CoverSettings.setAutoPullEnabled(context, v)
                            message = if (v) "已开启自动拉起" else "已关闭自动拉起"
                        }
                        // 「不主动弹出」：不判断副屏前台，只在启动器界面显示悬浮窗；
                        // 仍只在父项「悬浮窗防退出模式」开启时可用。
                        // ⚠ 运动相机模式关闭时**也要把这一行显示出来**（置灰），
                        //   否则用户看不到"它已被强制关闭"，会以为是设置丢了。
                        if (CoverSettings.isOverlayModeEnabled(context) || !cameraMode) {
                            ToggleRow(
                                label = "不主动弹出（只用悬浮窗防退出）",
                                sub = if (!cameraMode)
                                    "运动相机模式关闭 → 暂不生效"
                                else if (CoverSettings.isOverlayPassive(context))
                                    "开：悬浮窗只在启动器里出现，不影响别的应用"
                                else "关：回到桌面时自动挂出悬浮窗",
                                checked = CoverSettings.isOverlayPassive(context),
                                enabled = cameraMode && CoverSettings.isOverlayModeEnabled(context)
                            ) { v ->
                                CoverSettings.setOverlayPassive(context, v)
                                message = if (v) "已改为「不主动弹出」" else "已恢复主动覆盖"
                                refreshTick++
                            }
                        }
                        // 缺了它就会退回事件流判定（会被自家窗口/系统界面顶替而抖动）。
                        // 悬浮窗"该不该弹"完全依赖前台判定，而权威源是「使用记录」权限。
                        // 「不主动弹出」模式下不做前台判断，这条警告就不适用了。
                        if (cameraMode &&
                            CoverSettings.isOverlayModeEnabled(context) &&
                            !CoverSettings.isOverlayPassive(context) &&
                            !com.dsh.sportscam.cover.CoverForeground.hasUsageAccess(context)
                        ) {
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    clampIntro("⚠ 缺「使用记录」权限，悬浮窗可能在别的应用上弹出来"),
                                    color = TorchAmber, fontSize = 9.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                ActionChip("去开启") {
                                    openSettingsOnCover(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                }
                            }
                        }
                        // ⚠ 第九轮第 3 项（用户最终决定）：**不提供任何"逃生通道"** ——
                        //   通知栏两处出口（方向通知的退出药丸 / 保活通知的退出 action）已删除，
                        //   视频页右滑方案也已撤回 ⇒ 这里**不再有任何出口提示** ✗。
                        //   关闭「悬浮窗防退出模式」的正常路径就是上面这个开关（页面内开关，不算逃生口 ✓）。
                    }
                }

                item {
                    Section("防查：声音") {
                        ToggleRow(
                            label = "主界面静音（隐藏音量条）",
                            sub = if (!cameraMode)
                                "运动相机模式关闭 → 暂不生效"
                            else if (CoverSettings.isMuteOnCarousel(context))
                                "开：主界面音量锁 0 且不弹音量条，切出去恢复"
                            else "关：主界面也保持正常音量",
                            checked = CoverSettings.isMuteOnCarousel(context),
                            enabled = cameraMode
                        ) { v ->
                            CoverSettings.setMuteOnCarousel(context, v)
                            message = if (v) "主界面静音已开启" else "主界面静音已关闭"
                            refreshTick++
                        }
                    }
                }

                // 定时开关运动相机模式（第六轮第 3 项）：多条计划，同树浮层里增删改
                item {
                    Section("定时计划") {
                        val plans = CoverSettings.cameraModeSchedules(context)
                        val active = plans.count { it.enabled }
                        Text(
                            clampIntro("到点自动开/关运动相机模式，可建多条"),
                            color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        // ★ 第七轮：执行器挂在保活服务的 2s 通知守卫里（onCreate 无条件启动），
                        //   所以**运动相机模式关着也能到点执行** ✓，唯一前提是「保活服务」开着。
                        //   这里动态提醒，免得用户关了保活后以为定时坏了 ✗。
                        Spacer(Modifier.height(3.dp))
                        val keeperOn = CoverSettings.isKeeperEnabled(context)
                        Text(
                            text = if (keeperOn)
                                "需要「保活服务」开着（当前已开启）"
                            else "⚠ 保活已关闭 → 定时不会执行，请到 ② 页重新开启",
                            color = if (keeperOn) Color.White.copy(alpha = 0.4f) else TorchAmber,
                            fontSize = 9.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (plans.isEmpty()) "当前没有计划"
                            else plans.joinToString("　") {
                                "${if (it.enabled) "" else "（暂停）"}${it.timeText} " +
                                    "${if (it.turnOn) "开" else "关"} ${it.weekdayText}"
                            },
                            color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp,
                            maxLines = 2
                        )
                        Spacer(Modifier.height(5.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ActionChip(if (plans.isEmpty()) "新建计划" else "管理计划（${active}/${plans.size} 启用）") {
                                showScheduleEditor = true
                            }
                        }
                    }
                }
            }

            // ==============================================================
            // ④ 隐私与安全（第七轮）· 第二块
            //   本块：私密应用 / 最近删除（最近删除**从母界面迁来**，原位置不留重复项）
            // ==============================================================
            if (currentPage == ConsolePage.Privacy) {
                // 私密应用：把选中的应用从桌面/应用列表隐藏，只能凭密码进入
                item {
                    Section("私密应用") {
                        val privateOn = CoverSettings.isPrivateAppsEnabled(context)
                        val chosen = CoverSettings.privateAppsPreferred(context)
                        Text(
                            clampIntro("设为私密的应用在桌面和应用列表里都看不到，只能输密码进"),
                            color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        ToggleRow(
                            label = "启用「私密应用」",
                            sub = when {
                                privateOn -> "已启用：已选 ${chosen.size} 个应用"
                                hasPw -> "关闭（默认）：已选应用会正常显示"
                                else -> "开启前需要「启动器密码」，会引导你创建"
                            },
                            checked = privateOn
                        ) { v ->
                            if (v) {
                                // ★ 首次开启必须验证启动器密码；没设过密码 → 引导去创建
                                privateGuard.guard(
                                    context = context,
                                    subtitle = "开启「私密应用」前请验证启动器密码",
                                    onNeedSetup = {
                                        pendingEnablePrivateAfterSet = true
                                        pwdFirst = ""
                                        pwdNotice = null
                                        pwdFlow = PwdFlow.SetFirst   // 复用已有设置流程（两次输入）
                                    }
                                ) {
                                    CoverSettings.setPrivateAppsEnabled(context, true)
                                    com.dsh.sportscam.cover.CoverDebugLog.log("Privacy", "私密应用：已启用")
                                    message = "私密应用已启用"
                                    refreshTick++
                                }
                            } else {
                                CoverSettings.setPrivateAppsEnabled(context, false)
                                com.dsh.sportscam.cover.CoverDebugLog.log(
                                    "Privacy", "私密应用：已关闭（已选包名保留）"
                                )
                                message = "私密应用已关闭（已选应用会重新显示）"
                                refreshTick++
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // ★ 第八轮（用户要求）：这里不再自己弹"选择"浮层，而是**直接进私密应用界面**
                            //   （`ui/PrivateAppsScreen.kt`：右上「＋」添加、长按「移除私密应用」）。
                            //   ⚠ **不要**在这一层再拦一次密码 —— 该界面内部已套 `PrivateAccessGate`，
                            //     两边都拦会让用户**连着输两遍** ✗（没设密码时界面自己会给"去设置密码"引导 ✓）。
                            ActionChip("私密应用入口") { onOpenPrivateApps() }
                            Text(
                                text = when {
                                    chosen.isEmpty() -> "还没选任何应用（进去用右上「＋」添加）"
                                    privateOn -> "已隐藏 ${chosen.size} 个"
                                    else -> "已选 ${chosen.size} 个 · ⚠ 未启用，仍可见"
                                },
                                color = if (chosen.isNotEmpty() && !privateOn) TorchAmber
                                else Color.White.copy(alpha = 0.45f),
                                fontSize = 9.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!hasPw) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                clampIntro("⚠ 还没有启动器密码：先到本页上面「启动器密码」里设置"),
                                color = TorchAmber, fontSize = 9.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 最近删除（第七轮：从母界面迁到本页）
                item {
                    Section("最近删除") {
                        Text(
                            clampIntro("删除的媒体先放这里，确认不要了再彻底清理"),
                            color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(5.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ActionChip("最近删除") { onOpenTrash() }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(6.dp)) }
        }
      }

        // ---------------- 「拍摄长按启动第二层应用」的单选应用浮层 ----------------
        if (showAppPicker) {
            AppPickerOverlay(
                context = context,
                onDismiss = { showAppPicker = false },
                onPick = { pkg, label ->
                    CoverSettings.setCameraLongPressPackage(context, pkg)
                    message = "长按拍摄：$label"
                    showAppPicker = false
                }
            )
        }

        // ---------------- 「定时计划」编辑浮层（第六轮第 3 项；同树浮层） ----------------
        if (showScheduleEditor) {
            CameraModeScheduleOverlay(
                context = context,
                onDismiss = { showScheduleEditor = false },
                onChanged = { plans ->
                    message = "定时计划已保存（${plans.count { it.enabled }} / ${plans.size} 条启用）"
                    refreshTick++
                }
            )
        }

        // ---------------- 私密应用访问门（同树浮层；第七轮） ----------------
        // 第八轮：只用于"启用私密应用"这道门；「私密应用入口」已改为跳转界面
        // （那边的 PrivateAccessGate 负责拦，避免连输两遍）。
        PrivateAccessGuardHost(privateGuard)

        // ---------------- 「启动器密码」设置流程（同树浮层） ----------------
        // ⚠ 一律用 PasswordPadOverlay（同树浮层）：悬浮窗（2038）上下文里弹系统 Dialog 会崩进程。
        when (pwdFlow) {
            PwdFlow.VerifyOld -> PasswordPadOverlay(
                title = "先验证旧密码",
                subtitle = "改密码前需要先验证当前密码",
                onCancel = {
                    pwdFlow = PwdFlow.None
                    pwdFirst = ""
                    pwdNotice = null
                },
                onComplete = { code ->
                    if (CoverSettings.verifyPassword(context, code)) {
                        // 验证通过要落盘（第六轮第 4 项）；失败由浮层自己提示 + 抖动
                        com.dsh.sportscam.cover.CoverDebugLog.log(
                            "Console", "改密码：旧密码验证通过 → 进入新密码输入"
                        )
                        pwdNotice = null
                        pwdFlow = PwdFlow.SetFirst
                        true
                    } else {
                        com.dsh.sportscam.cover.CoverDebugLog.log(
                            "Console", "改密码：旧密码验证失败（已提示用户重试）"
                        )
                        false
                    }
                }
            )

            PwdFlow.SetFirst -> PasswordPadOverlay(
                title = "设置启动器密码",
                subtitle = pwdNotice
                    ?: "输入要使用的 4 位数字（忘记只能重装应用，请记牢）",
                onCancel = {
                    pwdFlow = PwdFlow.None
                    pwdFirst = ""
                    pwdNotice = null
                },
                onComplete = { code ->
                    // 第 1 步先记下，第 2 步再输一次做二次确认
                    pwdFirst = code
                    pwdNotice = null
                    pwdFlow = PwdFlow.SetConfirm
                    true
                }
            )

            PwdFlow.SetConfirm -> PasswordPadOverlay(
                title = "请再次输入",
                subtitle = "两次输入必须一致（二次确认）",
                onCancel = {
                    pwdFlow = PwdFlow.None
                    pwdFirst = ""
                    pwdNotice = null
                },
                onComplete = { code ->
                    if (code == pwdFirst) {
                        CoverSettings.setPassword(context, code)
                        pwdFlow = PwdFlow.None
                        pwdFirst = ""
                        pwdNotice = null
                        // ★★ 第二轮 B2：设完密码**立刻**刷新本地状态 ——
                        //   `hasPw` / `pwMasterOn` 一变，总开关与三个保护开关当场可用，
                        //   不用再"退出设置页重进"。
                        syncPasswordState()
                        // ★ 第七轮：如果这次设密码是"为了开启私密应用"（用户走引导进来的），
                        //   设完自动把私密应用打开，省一次点击 ✓
                        if (pendingEnablePrivateAfterSet) {
                            pendingEnablePrivateAfterSet = false
                            CoverSettings.setPrivateAppsEnabled(context, true)
                            com.dsh.sportscam.cover.CoverDebugLog.log(
                                "Privacy", "私密应用：引导创建密码完成 → 自动启用"
                            )
                        }
                        message = "密码已设置并启用"
                        refreshTick++
                        true
                    } else {
                        // 不一致 → 回到第 1 步重来（用 subtitle 提示，不能让用户卡在第 2 步）
                        pwdFirst = ""
                        pwdNotice = "两次输入不一致，请重新设置"
                        pwdFlow = PwdFlow.SetFirst
                        true
                    }
                }
            )

            PwdFlow.DisableVerify -> PasswordPadOverlay(
                title = "关闭启动器密码",
                subtitle = "先验证一次当前密码",
                onCancel = {
                    pwdFlow = PwdFlow.None
                    pwdNotice = null
                },
                onComplete = { code ->
                    if (CoverSettings.verifyPassword(context, code)) {
                        CoverSettings.clearPassword(context)
                        pwdFlow = PwdFlow.None
                        pwdNotice = null
                        // 关闭后 hasPw=false → 三个保护开关当场置灰（本地状态同步）
                        syncPasswordState()
                        message = "密码已关闭"
                        refreshTick++
                        true
                    } else {
                        // false → 浮层自己提示「密码错误，请重试」并抖动、清空重输
                        false
                    }
                }
            )

            PwdFlow.None -> Unit
        }
    }
}

/**
 * 关闭「运动相机模式」时**只做一件事**：把已经挂出来的防查窗口当场收掉。
 *
 * ★★ 第六轮第 2 项（用户实测吐槽）：这里**绝不再把用户的防查选项写 false**。
 *   旧实现（`disableAllGuardFeatures`）在关模式时把 overlay/autoPull/passive/mute 全写 false ✗，
 *   于是用户下次打开运动相机模式时要**一个个手动重开** ✗。
 *
 *   现在"生效 / 意愿"分离在 [CoverSettings] 里统一收敛
 *   （`isXxx = 意愿 && 运动相机模式开着`），所以关模式后这些能力**自动不生效**，
 *   而用户原先勾的选择**原样保留**；再次打开模式后自动生效。③ 页的置灰只是界面表现
 *   （各 `ToggleRow(enabled = cameraMode)`），不再需要写盘。
 */
private fun collapseGuardOverlays(context: Context) {
    // 解除"粘住"：否则「不主动弹出」模式下悬浮窗会一直被粘着
    // （虽然已不生效，但状态要清干净，免得再次打开模式后立刻又粘回来）
    com.dsh.sportscam.cover.CoverOverlayState.clearSticky()

    // 保活服务在跑时让它立刻同步一次 → 已挂的悬浮窗当场收起（不必等 350ms / 2s 周期）
    if (CoverSettings.isKeeperEnabled(context)) CoverKeeperService.syncOverlaySoon(context)

    // 落盘记录"用户意愿原样保留"，真机排查时能一眼看出没被改写
    val kept = "overlay=${CoverSettings.overlayModePreferred(context)}" +
        " autoPull=${CoverSettings.autoPullPreferred(context)}" +
        " passive=${CoverSettings.overlayPassivePreferred(context)}" +
        " mute=${CoverSettings.muteOnCarouselPreferred(context)}"
    com.dsh.sportscam.cover.CoverDebugLog.log(
        "Console",
        "运动相机模式关闭：只收悬浮窗，不改写用户意愿（$kept）"
    )
}

/**
 * 单选一个应用（用于「拍摄长按启动第二层应用」）。
 *
 * ⚠ 与 [com.dsh.sportscam.ui.common.CompactMenuDialog] 同样的理由：**不能**用系统
 * `Dialog`/`AlertDialog` —— 常驻悬浮窗的窗口上下文类型是 2038，与对话框要的 type 2
 * 冲突会直接崩进程。这里画成同一棵组合树里的全屏浮层。
 */
@Composable
private fun AppPickerOverlay(
    context: Context,
    onDismiss: () -> Unit,
    onPick: (packageName: String, label: String) -> Unit
) {
    // 遍历 PackageManager + 解码图标很慢，只算一次（打开浮层时才付这个代价）
    val apps = remember { queryLaunchableApps(context) }
    val current = remember { CoverSettings.cameraLongPressPackage(context) }
    var query by remember { mutableStateOf("") }
    val shown = remember(query, apps) {
        val q = query.trim()
        if (q.isEmpty()) apps
        else apps.filter {
            it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SportsCamSurface)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "选择长按「拍摄」要启动的应用",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                ActionChip("关闭") { onDismiss() }
            }
            Spacer(Modifier.height(6.dp))

            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(SportsCamSurfaceAlt)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text("搜索应用名或包名", color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp)
                    }
                    inner()
                }
            )
            Spacer(Modifier.height(6.dp))

            if (shown.isEmpty()) {
                Text("没有匹配的应用", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    shown.forEach { app ->
                        val selected = app.packageName == current
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (selected) com.dsh.sportscam.ui.theme.Accent.current.copy(alpha = 0.22f)
                                    else SportsCamSurfaceAlt
                                )
                                .clickable { onPick(app.packageName, app.label) }
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    app.label,
                                    color = if (selected) com.dsh.sportscam.ui.theme.Accent.current
                                    else Color.White.copy(alpha = 0.9f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    app.packageName,
                                    color = Color.White.copy(alpha = 0.35f),
                                    fontSize = 9.sp,
                                    maxLines = 1
                                )
                            }
                            if (selected) {
                                Text(
                                    "已选",
                                    color = com.dsh.sportscam.ui.theme.Accent.current,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------

/**
 * 控制台简介统一限长：超出部分截断并加省略号（用户要求"超过字数后不显示"）。
 *
 * 两道保险一起用：
 *  1. 组件层 `Text(maxLines=…, overflow=TextOverflow.Ellipsis)` 防换行撑爆布局；
 *  2. 这里做**硬截断**（[maxChars] 默认 26，控制台 sub 建议 20~24 字），
 *     防止超长字符串把同一行挤满、省略号都不出现。
 */
internal fun clampIntro(text: String, maxChars: Int = 26): String {
    val t = text.trim()
    return if (t.length <= maxChars) t else t.take(maxChars - 1).trimEnd() + "…"
}

// ======================================================================
// 第十四轮：「制作者与开源地址」/「免责声明与用户守则」两个界面
// ======================================================================

/**
 * 开源仓库地址。
 *
 * ⚠ **占位**：用户稍后给真实地址。仍含 `PLACEHOLDER` 时点击只提示「开源地址待补充」，
 *   不会打开无效页、也不会崩 ✓。
 */
internal const val REPO_URL = "https://github.com/clusss/sports-cam-launcher"

/** 作者 B 站 UID（第十五轮补充：About 页「作者B站主页」按钮显示用） */
internal const val BILI_UID = "3546803062442725"

/** 作者 B 站主页地址（点击跳转；失败提示「没有可用浏览器」✓） */
internal const val BILI_URL = "https://space.bilibili.com/$BILI_UID?spm_id_from=333.337.0.0"

/**
 * 免责声明正文（**Lead 定稿，一字不改** ✓）。
 *
 * 约定格式：`## ` 开头的行 = 小节标题（强调色粗体），其余非空行 = 正文段落。
 * ⚠ 法律文本**不允许截断** ✗ —— 副屏上过长的段落靠整页滚动，不要加 maxLines ✗。
 */
internal const val DISCLAIMER_TEXT: String = """
## 概述
本软件为个人开发的免费开源项目，按「现状」提供，不提供任何明示或默示的担保，包括适销性、特定用途适用性及不侵权担保。

## 权限与数据
本软件需要无障碍服务、修改系统设置、Shizuku 授权、所有文件访问、麦克风、悬浮窗等较敏感权限，仅用于实现软件内明示的功能。所有数据均在本机处理，作者不收集、不上传，也不运营任何服务器端数据。

## 使用须知
请仅在你自己的设备上使用本软件。修改系统设置、应用白名单等操作存在风险，建议在操作前备份原始状态；本软件会尽量提供「恢复备份」入口，但由此产生的一切后果仍由使用者自行承担。

## 你可以
1. 在本机安装并使用本软件。
2. 在遵守开源许可证的前提下阅读、修改、编译源码。
3. 在注明出处的前提下分享本仓库的原始链接。
4. 反馈问题、提出建议。

## 你不得
1. 将本软件用于偷拍、偷录、侵犯他人隐私等违法用途。录音录像前须取得被记录者的知情同意；在禁止录音录像的场所使用本软件，风险与责任由使用者自负。
2. 将本软件用于规避单位或学校的合法管理规定。
3. 修改源码后冒充官方版本发布，或植入广告、后门、数据采集代码后再分发。
4. 去除或隐藏本免责声明后二次分发。
5. 将本软件用于任何违反当地法律法规的活动；由此产生的责任由使用者自行承担。

## 免责与责任限制
因使用或无法使用本软件导致的任何直接或间接损失（含数据丢失、设备异常、应用被系统拦截、第三方账号被限制、录音录像内容泄露等），作者不承担任何责任。本软件可能随时更新或停止维护，因系统升级、厂商策略调整导致功能失效的，作者不承担责任。

## 开源地址与分发
请仅从作者本人的 GitHub 仓库获取本软件。凡从其他渠道（第三方应用市场、网盘、论坛、他人转发等）获取的安装包，均可能被篡改、植入恶意代码，由此导致的隐私泄露、财产损失等，作者不承担任何责任。本软件为开源项目，允许在遵守许可证的前提下学习、修改与分发；任何第三方基于本软件修改、二次打包或分发所产生的行为与后果，与原作者无关。

## 第三方与商标
本软件不是任何手机厂商、运营商或单位的官方产品，与 vivo、iQOO 等品牌及其关联方无任何隶属或授权关系。本软件基于 Android Shizuku 开放接口实现部分功能，Shizuku 为第三方开源项目，与本软件作者无关。文中出现的商标与产品名称仅用于描述与兼容性说明，权利归各自所有者。

## 未成年人
未满 18 周岁的使用者应在监护人指导下使用本软件；监护人应就使用行为承担相应责任。

## 同意与变更
使用本软件即表示你已阅读、理解并同意本声明与守则。如不同意，请立即停止使用并卸载本软件。本声明可能随版本更新而调整，更新后继续使用即视为接受。
"""

/**
 * GitHub 官方 mark 的 path 数据（16×16 viewBox，公开 SVG）。
 *
 * 为什么内嵌成矢量：副屏字体可能缺 emoji 字形、PNG 又要多套一套资源 ⇒
 * `ImageVector` 最稳（任意大小都不糊、不依赖网络/字体 ✓）。
 */
private const val GITHUB_MARK_PATH = "M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 " +
    "5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94" +
    "-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82" +
    ".72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95" +
    " 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 " +
    "1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 " +
    "2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 " +
    "0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8z"

/** GitHub 图标（懒加载一次，全局复用） */
private val githubMarkVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "GitHubMark",
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f
    ).addPath(
        pathData = PathParser().parsePathString(GITHUB_MARK_PATH).toNodes(),
        fill = SolidColor(Color.White)
    ).build()
}

/** About / Disclaimer 共用的顶部返回行 */
@Composable
private fun SimpleScreenTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = Color.White,
            modifier = Modifier.size(24.dp).clickable { onBack() }
        )
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 「制作者与开源地址」（第十四轮）。
 *
 * 副屏只有 546×338dp ⇒ 整页 [verticalScroll] ✓（内容必然超一屏）。
 */
@Composable
internal fun AboutScreen(onOpenDisclaimer: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SportsCamBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        SimpleScreenTopBar("制作者与开源地址", onBack)
        Spacer(Modifier.height(10.dp))

        // 1) 制作者
        Section("制作者") {
            Text(
                "@Clus",
                color = com.dsh.sportscam.ui.theme.Accent.current,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text("制作者", color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp)
            Spacer(Modifier.height(6.dp))
            // 第十五轮（补充）：作者 B 站主页（与 GitHub 那行同款：图标 + 标题 + 小字 + 打开 ›）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .clickable {
                        CoverDebugLog.log("About", "打开 B 站主页 uid=$BILI_UID ✓")
                        val ok = runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(BILI_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.isSuccess
                        if (!ok) showToast(context, "没有可用浏览器")
                    }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 图标：用系统自带的通用「语言/网页」图标（矢量、不依赖字形，稳定 ✓）
                Icon(
                    Icons.Filled.Language,
                    contentDescription = "B 站主页",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("作者B站主页", color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
                    Text(
                        "UID: $BILI_UID",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text("打开 ›", color = com.dsh.sportscam.ui.theme.Accent.current, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        // 2) 特别鸣谢 + 灵感来源（方括号原样显示 ✓）
        //    ⚠ 第十五轮：按用户要求鸣谢只保留 @元喵（原来的第二位鸣谢已删）
        Section("特别鸣谢") {
            Text("@元喵", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                "[部分功能灵感来源]",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 9.sp
            )
        }
        Spacer(Modifier.height(8.dp))

        // 3) 开源地址及软件更新（整行可点：GitHub 图标 + 文案）
        Section("开源地址及软件更新") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .clickable {
                        if (REPO_URL.contains("PLACEHOLDER")) {
                            showToast(context, "开源地址待补充")
                        } else {
                            val ok = runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.isSuccess
                            if (!ok) showToast(context, "没有可用浏览器")
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    githubMarkVector,
                    contentDescription = "GitHub",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "GitHub 开源地址",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )
                    Text(
                        if (REPO_URL.contains("PLACEHOLDER")) "地址待补充（点击有提示）" else REPO_URL,
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text("打开 ›", color = com.dsh.sportscam.ui.theme.Accent.current, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        // 4) 免责提示（用户给的原文，一字不改）
        Section("免责提示") {
            Text(
                "由于本项目开源且涉及隐私的权限较多，请不要在 github 以外的地方下载该软件，" +
                    "防止应用被篡改导致个人隐私泄露，对于在非本作者 github 主页下载的软件导致的" +
                    "个人信息泄露等作者不负相关法律责任",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 9.sp
            )
        }
        Spacer(Modifier.height(8.dp))

        // 5) 最底部入口
        EntryRow("免责声明与用户守则") { onOpenDisclaimer() }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * 「免责声明与用户守则」（第十四轮）。
 *
 * 必须可滚 ✓；正文来自 [DISCLAIMER_TEXT]（`## 小节` = 标题，其余 = 正文）✓。
 */
@Composable
internal fun DisclaimerScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SportsCamBlack)
    ) {
        SimpleScreenTopBar("免责声明与用户守则", onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // 顶端一行灰色小字（Lead 要求）：最后更新日期
            Text(
                "最后更新：2026-10",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 9.sp
            )
            Spacer(Modifier.height(2.dp))
            DISCLAIMER_TEXT.lines().forEach { rawLine ->
                val line = rawLine.trimEnd()
                when {
                    line.startsWith("## ") -> {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            line.removePrefix("## "),
                            color = com.dsh.sportscam.ui.theme.Accent.current,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(5.dp))
                    }
                    line.isBlank() -> Spacer(Modifier.height(6.dp))
                    else -> {
                        Text(
                            line,
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 母界面上的子界面入口行：点击进入对应子界面。
 *
 * [sub] 可空：`null`/空白 ⇒ 不渲染副标题（用户删掉的说明不会留下空行 ✓）。
 */
@Composable
private fun EntryRow(title: String, sub: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SportsCamSurfaceAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White.copy(alpha = 0.92f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (!sub.isNullOrBlank()) {
                Text(
                    clampIntro(sub, 26),
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("进入 ›", color = com.dsh.sportscam.ui.theme.Accent.current, fontSize = 11.sp)
    }
}

/**
 * 在**副屏**上打开系统设置页。
 *
 * 为什么不能直接 `startActivity`：这台机器主屏没有面板，
 * 普通启动会落到默认显示器（主屏）上 —— 用户什么都看不见，
 * 而副屏上 vivo 桌面仍是前台，于是悬浮窗按规则"正确地"挂出来覆盖桌面，
 * 表现就是"一进设置悬浮窗就不停弹"。
 */
private fun openSettingsOnCover(context: Context, intent: Intent) {
    runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val coverId = com.dsh.sportscam.cover.CoverDisplay.coverDisplayId(context)
        if (coverId != android.view.Display.DEFAULT_DISPLAY) {
            val options = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(coverId)
                .toBundle()
            context.startActivity(intent, options)
        } else {
            context.startActivity(intent)
        }
    }.onFailure { showToast(context, "打不开该设置页") }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {    Column(
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
private fun ActionChip(
    text: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        selected -> com.dsh.sportscam.ui.theme.Accent.current.copy(alpha = 0.22f)
        !enabled -> SportsCamSurfaceAlt.copy(alpha = 0.5f)
        else -> SportsCamSurfaceAlt
    }
    val fg = when {
        !enabled -> Color.White.copy(alpha = 0.3f)
        selected -> com.dsh.sportscam.ui.theme.Accent.current
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
    /** 可空：`null`/空白 ⇒ 不渲染副标题（不会留空行） */
    sub: String? = null,
    checked: Boolean,
    /** false = 置灰锁定（例如子项的前置父项未开启） */
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    // ★ 用**本地状态**驱动 Switch，点击立即翻转；同时立刻回调写设置。
    //
    //   为什么不直接把 checked 交给调用方（读 SharedPreferences）：
    //   普通 prefs 读取不是 Compose 状态，点击后界面不会重组 —— 表现就是
    //   "点了开关不变色，要退出控制台重进才变"（用户实测反馈）。
    //   这里 remember(checked) 会在**外部值变化时**重新同步（例如别的开关联动），
    //   而用户点击时先本地翻转、视觉立刻生效，不依赖外部重组时机。
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
            if (!sub.isNullOrBlank()) {
                Text(
                    clampIntro(sub, 24),
                    color = Color.White.copy(alpha = if (enabled) 0.4f else 0.22f),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Switch(
            checked = local && enabled,
            enabled = enabled,
            onCheckedChange = { value ->
                local = value          // 立即反馈
                onCheckedChange(value) // 立即生效并落盘
            }
        )
    }
}

// ======================================================================
// 「定时开关运动相机模式」的计划编辑浮层（第六轮第 3 项）
// ======================================================================

/**
 * 定时计划编辑浮层（**同树浮层**，绝不用系统 Dialog —— 悬浮窗 2038 上下文会崩进程）。
 *
 * 布局按副屏最窄的横屏尺寸排（682×422@200dpi ≈ **546×338dp**）：
 *  面板 = 96% 宽 × 94% 高，上下结构（列表可滚动 + 底部编辑区），
 *  这样即使计划很多也不会把编辑区挤出屏幕（密码浮层那次踩过尺寸坑，这里同样谨慎）。
 *
 * 状态：
 *  · [plans] 是**本地 Compose 状态**（约定 2：Switch/勾选不能直接读 prefs），
 *    每次改动立刻 `setCameraModeSchedules` 落盘 + 通知上层刷新 ③ 页；
 *  · [draft] 是"正在编辑的那一条"，改字段只动草稿，点「保存」才进 [plans]（改多条字段时才不会半途写坏）。
 */
@Composable
private fun CameraModeScheduleOverlay(
    context: Context,
    onDismiss: () -> Unit,
    onChanged: (List<CameraModeSchedule>) -> Unit
) {
    var plans by remember { mutableStateOf(CoverSettings.cameraModeSchedules(context)) }
    /** 正在编辑的那一条（非空 → 打开**独立编辑窗口**；第七轮按用户示意图重做） */
    var editing by remember { mutableStateOf<CameraModeSchedule?>(null) }

    /** 落盘 + 同步本地状态 + 通知上层（每次都立即写，界面与服务立刻一致） */
    val commit: (List<CameraModeSchedule>) -> Unit = { next ->
        val sorted = next.sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))
        plans = sorted
        CoverSettings.setCameraModeSchedules(context, sorted)
        onChanged(sorted)
    }
    /** 新计划的 id：取现有最大值 +1（比时间戳更稳，不会撞号） */
    val newId: () -> Long = { (plans.maxOfOrNull { it.id } ?: 0L) + 1L }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(14.dp))
                .background(SportsCamSurface)
                // 面板本体吃掉点击，避免穿透到遮罩被误关
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "定时计划（运动相机模式）",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                ActionChip("关闭") { onDismiss() }
            }
            Text(
                "到点自动开/关「运动相机模式」；「重复」留空 = 每天。" +
                    "列表行上可 启停 / 编辑 / 删除。",
                color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp, maxLines = 2
            )
            Spacer(Modifier.height(5.dp))

            // ---------- 计划列表（可滚动，占满剩余高度） ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                if (plans.isEmpty()) {
                    Text(
                        "还没有计划 —— 点下面的「新建计划」添加一条。",
                        color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp
                    )
                }
                plans.forEach { plan ->
                    SchedulePlanRow(
                        plan = plan,
                        onToggleEnabled = {
                            commit(plans.map { if (it.id == plan.id) it.copy(enabled = !it.enabled) else it })
                        },
                        // 编辑 → 开一个**独立的小窗口**（只放 动作/时间/重复 + 保存/取消）
                        onEdit = { editing = plan },
                        onDelete = {
                            commit(plans.filterNot { it.id == plan.id })
                            if (editing?.id == plan.id) editing = null
                        }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ---------- 底部只有「新建计划」（编辑走独立窗口，不在这里挤） ----------
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionChip("新建计划") {
                    // 默认：启用、开启运动相机模式、每天 08:00（进窗口改两下就行）
                    editing = CameraModeSchedule(
                        id = newId(),
                        enabled = true,
                        turnOn = true,
                        hour = 8,
                        minute = 0,
                        weekdays = emptySet()
                    )
                }
                Text(
                    "点列表行里的「编辑」改已有计划",
                    color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
    }

    // ---------- 独立编辑窗口（专注：动作 / 时间 / 重复 + 保存 / 取消） ----------
    val current = editing
    if (current != null) {
        ScheduleEditOverlay(
            initial = current,
            onDismiss = { editing = null },
            onSave = { saved ->
                val next = if (plans.any { it.id == saved.id }) {
                    plans.map { if (it.id == saved.id) saved else it }
                } else {
                    plans + saved
                }
                commit(next)
                editing = null
            }
        )
    }
}

/**
 * 「定时计划」的**独立编辑窗口**（第七轮按用户示意图重做）。
 *
 * 结构（严格照用户示意图，三块纵向分行、标签左对齐、行间留白均匀）：
 * ```
 *  动作                 ← 标签
 *   [开启运动相机模式] [关闭运动相机模式]     ← 单选，选中态用强调色
 *
 *  时间
 *        07:30         ← 大字号居中
 *      ─────────      ← 下划线
 *   （点击这整块 → 弹数字键盘输入）
 *
 *  重复：
 *   [每天][工作日][周末]
 *   [一][二][三][四][五][六][日]            ← 7 个单日方块，多选
 *
 *  [ 保存 ]  [ 取消 ]
 * ```
 * ⚠ 仍是**同树浮层**（绝不用系统 Dialog：悬浮窗 2038 上下文弹 Dialog 会崩进程）。
 * ⚠ `已启用/已暂停 / 删除` 刻意**不放在这个窗口里**（按用户要求），它们在列表行上。
 * 尺寸：宽度 94%（≥340dp），内容超屏时可纵向滚动（横屏最窄 338dp 高）。
 */
@Composable
private fun ScheduleEditOverlay(
    initial: CameraModeSchedule,
    onDismiss: () -> Unit,
    onSave: (CameraModeSchedule) -> Unit
) {
    var draft by remember { mutableStateOf(initial) }
    var showTimePad by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SportsCamSurface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // ---------- 动作 ----------
            ScheduleFieldLabel("动作")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("开启运动相机模式", selected = draft.turnOn) {
                    draft = draft.copy(turnOn = true)
                }
                ActionChip("关闭运动相机模式", selected = !draft.turnOn) {
                    draft = draft.copy(turnOn = false)
                }
            }
            Spacer(Modifier.height(14.dp))

            // ---------- 时间（点击输入数字） ----------
            ScheduleFieldLabel("时间")
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showTimePad = true }      // ★ 点它 → 弹数字键盘
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    draft.timeText,
                    color = com.dsh.sportscam.ui.theme.Accent.current,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                // 输入框下划线（与用户示意图一致）
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .height(1.5.dp)
                        .background(Color.White.copy(alpha = 0.35f))
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "点击输入数字（4 位 = 时:分，例如 0730 → 07:30）",
                    color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp
                )
            }
            Spacer(Modifier.height(14.dp))

            // ---------- 重复 ----------
            ScheduleFieldLabel("重复：" + draft.weekdayText)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CameraModeSchedule.WEEKDAY_PRESETS.forEach { (label, set) ->
                    ActionChip(label, selected = draft.weekdays == set) {
                        // 快捷项：点一下 = 全选/工作日/周末，同时同步下面 7 个方块 ✓
                        draft = draft.copy(weekdays = set)
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { iso ->
                    WeekdayToggle(
                        label = CameraModeSchedule.WEEKDAY_LABELS[iso - 1],
                        selected = draft.weekdays.contains(iso)
                    ) {
                        val next = draft.weekdays.toMutableSet()
                        if (!next.add(iso)) next.remove(iso)
                        draft = draft.copy(weekdays = next)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ---------- 保存 / 取消 ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionChip("保存") { onSave(draft) }
                ActionChip("取消") { onDismiss() }
                Text(
                    "到时由保活服务套用（服务没开就不会执行）",
                    color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, maxLines = 1
                )
            }
        }

        // ---------- 时间输入的数字键盘（同树浮层，叠在上面） ----------
        if (showTimePad) {
            NumberPadOverlay(
                title = "输入时间",
                subtitle = "4 位数字 = 时:分（0730 → 07:30）",
                errorText = "时间非法：时应 00-23、分应 00-59",
                length = 4,
                // 边输边显示："" → "--:--"，"07" → "07"，"0730" → "07:30"
                format = { d ->
                    when {
                        d.isEmpty() -> "--:--"
                        d.length <= 2 -> d
                        else -> d.substring(0, 2) + ":" + d.substring(2)
                    }
                },
                onCancel = { showTimePad = false },
                onComplete = { code ->
                    val h = code.substring(0, 2).toIntOrNull() ?: -1
                    val m = code.substring(2, 4).toIntOrNull() ?: -1
                    if (h in 0..23 && m in 0..59) {
                        draft = draft.copy(hour = h, minute = m)
                        showTimePad = false
                        true
                    } else {
                        // false → 键盘自己显示"时间非法…" + 抖动 + 清空重输 ✓
                        false
                    }
                }
            )
        }
    }
}

/** 编辑窗口里的字段标签（左对齐，与用户示意图一致） */
@Composable
private fun ScheduleFieldLabel(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.8f),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 计划列表里的一行：启用状态 + 时间 + 动作 + 重复 + 编辑/删除 */
@Composable
private fun SchedulePlanRow(
    plan: CameraModeSchedule,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(SportsCamSurfaceAlt)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${plan.timeText}　${if (plan.turnOn) "开启" else "关闭"}运动相机模式",
                color = if (plan.enabled) Color.White.copy(alpha = 0.92f)
                else Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
            Text(
                plan.weekdayText + if (plan.enabled) "" else "　（已暂停）",
                color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionChip(
                text = if (plan.enabled) "已启用" else "已暂停",
                selected = plan.enabled
            ) { onToggleEnabled() }
            ActionChip("编辑") { onEdit() }
            ActionChip("删除") { onDelete() }
        }
    }
}

/** 星期小方块（7 个一行放得下 546dp 宽的副屏；选中态用强调色） */
@Composable
private fun WeekdayToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) com.dsh.sportscam.ui.theme.Accent.current.copy(alpha = 0.28f)
                else SportsCamSurfaceAlt
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) com.dsh.sportscam.ui.theme.Accent.current
            else Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp
        )
    }
}

// ======================================================================
// 「私密应用」选择浮层（第七轮）
//
// ⚠ 第八轮起**不再挂载**（用户要求 ④ 页那个入口改成直接进 `Screen.PrivateApps` 界面，
//   在那里用右上「＋」添加、长按「移除私密应用」）。按 Lead 的说明"留着但不再挂"——
//   保留它是为了万一要回退到这个"控制台内低调板选择器"，不必重写。
//   若下一轮确认不需要，可整块删除（含上面的 `queryLaunchableApps` 依赖不变，别处仍在用）。
// ======================================================================

/**
 * 选择哪些应用是"私密应用"（同树浮层）。
 *
 * 点一行 = 加入 / 移出私密，**立即落盘**（`CoverSettings.setPrivateApps` —— 单一真相，
 * 桌面隐藏与列表过滤都读同一份，不会出现两套状态）。
 *
 * ⚠ 本浮层由 `privateGuard` 把关：**每次进入都要先输密码**（见调用点）；
 *   这里只负责选，不再重复门禁。
 * ⚠ 用 [CoverSettings.privateAppsPreferred]（原始集合）而不是生效值 —— 否则功能没启用时
 *   进来会看到"空列表"，用户没法预先配置。
 */
@Composable
private fun PrivateAppsPickerOverlay(
    context: Context,
    onDismiss: () -> Unit,
    onChanged: (Int) -> Unit
) {
    // 遍历 PackageManager + 解码图标很慢，只算一次（打开浮层时才付这个代价）
    val apps = remember { queryLaunchableApps(context) }
    // ★ 本地 Compose 状态（约定 2：勾选状态不能直接读 prefs）
    var selected by remember { mutableStateOf(CoverSettings.privateAppsPreferred(context)) }
    var query by remember { mutableStateOf("") }
    val enabledNow = remember { CoverSettings.isPrivateAppsEnabled(context) }

    val shown = remember(query, apps) {
        val q = query.trim()
        if (q.isEmpty()) apps
        else apps.filter {
            it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }

    /** 点一行：加入/移出私密 + 立即落盘 + 通知上层刷新 */
    val toggle: (String) -> Unit = { pkg ->
        val next = if (selected.contains(pkg)) selected - pkg else selected + pkg
        selected = next
        CoverSettings.setPrivateApps(context, next)
        // ★ 通知 list-viewer 的私密应用界面/列表：已组合的列表立刻刷新
        //   （他自己增删时也会自动调；我们这里直接写 prefs，所以要显式叫一声 ✓）
        runCatching { notifyPrivateAppsChanged() }
        onChanged(next.size)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .padding(10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SportsCamSurface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "选择私密应用",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "已选 ${selected.size}",
                    color = com.dsh.sportscam.ui.theme.Accent.current, fontSize = 11.sp
                )
                Spacer(Modifier.width(8.dp))
                ActionChip("关闭") { onDismiss() }
            }
            Text(
                if (enabledNow)
                    "点一行即可加入/移出。私密应用不会出现在桌面与总应用列表里，进入私密界面每次都要密码。"
                else "⚠ 私密应用功能当前未启用：这些应用仍然可见（回上一页开启后立即隐藏）。",
                color = if (enabledNow) Color.White.copy(alpha = 0.45f) else TorchAmber,
                fontSize = 9.sp, maxLines = 2
            )
            Spacer(Modifier.height(6.dp))

            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(SportsCamSurfaceAlt)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text("搜索应用名或包名", color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp)
                    }
                    inner()
                }
            )
            Spacer(Modifier.height(6.dp))

            if (shown.isEmpty()) {
                Text("没有匹配的应用", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    shown.forEach { app ->
                        val isPrivate = selected.contains(app.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (isPrivate) {
                                        com.dsh.sportscam.ui.theme.Accent.current.copy(alpha = 0.22f)
                                    } else SportsCamSurfaceAlt
                                )
                                .clickable { toggle(app.packageName) }
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    app.label,
                                    color = if (isPrivate) com.dsh.sportscam.ui.theme.Accent.current
                                    else Color.White.copy(alpha = 0.9f),
                                    fontSize = 11.sp, fontWeight = FontWeight.Medium
                                )
                                Text(
                                    app.packageName,
                                    color = Color.White.copy(alpha = 0.35f),
                                    fontSize = 9.sp, maxLines = 1
                                )
                            }
                            Text(
                                if (isPrivate) "已私密 · 点击移出" else "设为私密",
                                color = if (isPrivate) com.dsh.sportscam.ui.theme.Accent.current
                                else Color.White.copy(alpha = 0.6f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
