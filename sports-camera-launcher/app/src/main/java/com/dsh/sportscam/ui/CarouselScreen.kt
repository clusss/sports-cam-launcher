package com.dsh.sportscam.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.sportscam.model.LauncherItem
import com.dsh.sportscam.model.LauncherItems
import com.dsh.sportscam.ui.theme.SportsCamBlack
import com.dsh.sportscam.ui.theme.TorchAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 设计基准分辨率：vivo X Flip 副屏 682 x 422（横屏） */
private const val REF_WIDTH = 682f

/**
 * 缩放基准 = 副屏的**最短边**（两个方向都是 422）。
 *
 * 用宽度当基准会在竖屏下缩得过小；用最短边则横竖屏比例一致 —— 见 [CarouselScreen] 内注释。
 */
private const val REF_SHORT_SIDE = 422f

/** 「相簿 #2」双击判定窗口（毫秒）；同时是单击动作的延迟时间 */
private const val DOUBLE_TAP_MS = 400L

/** 选中卡片设计尺寸 */
private const val CARD_WIDTH_REF = 220f
private const val CARD_HEIGHT_REF = 300f

/** 两侧卡片的缩放 / 透明度 */
private const val SIDE_SCALE = 0.75f

/**
 * 「相簿 #3 居中时右下角热区」的边长。
 *
 * 0.7cm ≈ 0.7/2.54 英寸 × 160dpi ≈ 44dp（上一版是 0.5cm≈32dp，按反馈放大）。
 * 完全透明但可点击，只在相簿 #3 停稳居中时出现。
 */
private val ALBUM3_HOTZONE_DP = 44.dp
private const val SIDE_ALPHA = 0.45f

/** 文字相对原设计的放大倍数（在 2 倍基础上略微增大） */
private const val TEXT_SCALE = 2.3f

/**
 * 图标放大倍数（相对最初设计比例 0.24）：
 * 0.24 × 4 = 0.96×卡高 —— 在上一次「放大 2 倍」的基础上再放大一倍。
 */
private const val ICON_SCALE = 4f

/**
 * 主界面：横向直线轮播选择器（**无限循环滚动**）。
 *
 * 布局以 682x422 为基准等比缩放（不是固定 dp），因此在任何 density 下
 * 都能得到与设计稿完全一致的比例：中央完整卡片 + 左右各一张只露出一部分。
 *
 * 视觉：无卡片背景 / 无描边 / 无发光 / 无选中框、无底部指示点；
 * 图标与文字一律白色，图标放大到卡高的 0.96 倍。
 */
@Composable
fun CarouselScreen(
    flashlightOn: Boolean,
    flashlightAvailable: Boolean,
    onExecute: (Int) -> Unit,
    /** 隐藏入口：选中「相簿 #3」并停稳时，点屏幕右下角透明热区三下 → 应用列表 */
    onAlbum3TripleTap: () -> Unit,
    /** 「相簿 #2」快速双击 → 清理后台进程（悬浮窗模式下系统后台面板用不了） */
    onAlbum2DoubleTap: () -> Unit,
    /** 长按「拍摄」时执行的第二层动作（由控制台「拍摄长按启动第二层应用」决定） */
    onLongPressCamera: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // ★ 竖屏适配：订阅配置变化（保证旋转后重组），方向判定用显示器真实尺寸
    androidx.compose.ui.platform.LocalConfiguration.current
    val isPortrait = com.dsh.sportscam.cover.CoverDisplay.isPortraitNow(context)

    val items = remember { LauncherItems.all }

    // ---------------- 无限循环：页号取一个很大的区间，用 page % size 映射回真实下标 ----------------
    val loopPageCount = Int.MAX_VALUE
    // 起始页落在区间中部，且保证 startPage % size == 0（首屏仍停在「拍摄」）
    val startPage = remember(items.size) {
        val middle = loopPageCount / 2
        middle - (middle % items.size)
    }
    val pagerState = rememberPagerState(initialPage = startPage) { loopPageCount }
    val scope = rememberCoroutineScope()

    // 注：排查"某些角度滑动不翻页"时，这里曾有一段 snapshotFlow 插桩把
    // pagerState.currentPage / currentPageOffsetFraction 写进 cover_debug.log。
    // 结论已确认（路线 A 后四个方向都能翻页），故移除以免刷日志。
    // 若日后再排查手势问题，按下面这段加回即可：
    //
    //   androidx.compose.runtime.LaunchedEffect(Unit) {
    //       androidx.compose.runtime.snapshotFlow {
    //           Triple(pagerState.currentPage, pagerState.currentPageOffsetFraction,
    //                  pagerState.isScrollInProgress)
    //       }.collect { (p, frac, sc) ->
    //           com.dsh.sportscam.cover.CoverDebugLog.log(
    //               "CarouselDiag", "page=$p frac=$frac scrolling=$sc")
    //       }
    //   }
    //
    // ⚠ 同时记住：**注入手势必须显式指定副屏**（`adb shell input -d 1 swipe ...`）。
    //   不写 -d 1 时输入会打到主屏（这台机器主屏没有面板），表现为"怎么滑都没反应"。

    // 「相簿 #2」双击检测（单击延迟执行，双击则取消单击并清理后台）
    var album2TapCount by remember { mutableIntStateOf(0) }
    var album2LastTapAt by remember { mutableLongStateOf(0L) }
    var album2TapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // ---------------- 左上角时钟（第十轮第 2 项） ----------------
    // 开关：每次组合都重读设置 ⇒ 控制台改完返回主界面立刻生效 ✓；关闭时**完全不渲染** ✓。
    val showClock = com.dsh.sportscam.cover.CoverSettings.isShowClockOnHome(context)
    // 文本状态放在**界面作用域**：LaunchedEffect 里更新、下面 BoxWithConstraints 里渲染。
    var clockText by remember { mutableStateOf(clockHhMm()) }
    // 刷新策略：**睡到下一个整分**再更新（不是每秒重组 —— 那会让整个轮播每秒重组一次 ✗）；
    // 开关打开/关闭时协程随之启动/取消（key = showClock ✓）。
    LaunchedEffect(showClock) {
        if (!showClock) return@LaunchedEffect
        while (true) {
            clockText = clockHhMm()
            delay(msToNextMinute())
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(SportsCamBlack)
    ) {
      // ★ 竖屏不再整屏旋转（路线 A）：
      //   上一版把**整个内容树（含分页器）**转了 −90°，于是所有指针手势都要经过旋转
      //   坐标系，出现"沿某条对角线滑动会回弹、另一条正常"这类方向性问题。
      //   现在改为：**分页器不旋转**，竖屏用 VerticalPager（原生竖直手势，任何角度都正常），
      //   只在每一页内部把卡片内容逆时针转 90° —— 既能"上下滚动"，图标也仍是转过来的。
      //   应用列表与桌面不走这里（它们有自己的竖屏布局）。
        // ---------------- 等比缩放：以**最短边**为基准（竖屏适配） ----------------
        //
        // 原来用 maxWidth / 682（横屏宽度基准）：竖屏时宽度只有 422，会缩到约 0.62 倍，
        // 图标变小、四周大片空白，和横屏完全不像。
        // 而副屏**两个方向的最短边都是 422**（682×422 或 422×682），
        // 所以改用最短边作基准 → 横竖屏的卡片/图标/文字比例完全一致，
        // 差别只是竖屏多出来的纵向空间，滑动方式与布局逻辑都不变。
        // ★ 以**最短边**为基准（竖屏适配）。
        //
        //   注意：这里刻意保持"dp 与 px 基准量混比"的写法（得到 scale≈0.8）。
        //   我曾改成像素换算（scale=1.0）想"修正"，结果卡片放大 25%、文字超出框外，
        //   用户实测反馈"图标太大、文字出框"，已按要求改回。**不要再"顺手修正"这里**：
        //   0.8 才是这台设备上视觉正确的比例。
        val shortSide = minOf(maxWidth.value, maxHeight.value)
        val scale = (shortSide / REF_SHORT_SIDE).coerceIn(0.3f, 3f)

        val cardH: Dp = minOf(CARD_HEIGHT_REF.dp * scale, maxHeight * 0.72f)
        val cardW: Dp = minOf(CARD_WIDTH_REF.dp * scale, cardH * (CARD_WIDTH_REF / CARD_HEIGHT_REF))
        val sizeScale = cardH.value / CARD_HEIGHT_REF

        // 文字 17 -> 34（放大 2 倍）；图标 0.24 -> 0.96（比上一版再大一倍）
        val iconSize = cardH * 0.24f * ICON_SCALE
        val labelSize: TextUnit = (17f * sizeScale * TEXT_SCALE).sp

        // 让相邻项露出边缘：页宽取「卡片宽 * 1.28」与「图标宽 + 24dp」的较大者，
        // 避免放大后的图标被相邻页挤压
        val pageWidth = maxOf(cardW * 1.28f, iconSize + 24.dp)
        val sidePadding = ((maxWidth - pageWidth) / 2).coerceAtLeast(4.dp)

        // ---------------- 每页内容（横竖屏共用一份） ----------------
        //
        // rotate=true 用于竖屏：分页器本身**不旋转**（手势交给 Compose 原生处理，
        // 任意角度都能翻页），只把这一页的内容按横屏画布布局后逆时针转 90°。
        // 旋转后的落地尺寸 = maxWidth(422) × (maxHeight − 2×sidePadding)，
        // 正好落在竖直分页器的页槽内；上下留出的 sidePadding 即"渐隐邻项"露出边缘处。
        val pageContent: @Composable (page: Int, rotate: Boolean) -> Unit = { page, rotate ->
            // 循环映射：任意大页号 -> 真实选项下标
            val itemIndex = ((page % items.size) + items.size) % items.size
            val item = items[itemIndex]

            // 用滚动偏移做连续插值：拖动过程中缩放 / 透明度平滑变化
            val offset = abs((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
            val fraction = offset.coerceIn(0f, 1f)
            val pageScale = 1f - (1f - SIDE_SCALE) * fraction
            val pageAlpha = 1f - (1f - SIDE_ALPHA) * fraction

            // 卡片本体抽成 lambda：下面按方向选"直接铺满页槽"或"横屏画布 + 旋转"
            val card: @Composable () -> Unit = {
                LauncherCard(
                    item = item,
                    torchOn = flashlightOn && item.kind == LauncherItem.Kind.TORCH,
                    layoutScale = pageScale,
                    alpha = pageAlpha,
                    cardH = cardH,
                    iconSize = iconSize,
                    labelSize = labelSize,
                    onClick = {
                        // 已经居中 -> 执行功能；否则只做「平滑滚动到中央」
                        val centered = page == pagerState.currentPage &&
                            abs(pagerState.currentPageOffsetFraction) < 0.15f
                        if (!centered) {
                            scope.launch { pagerState.animateScrollToPage(page) }
                        } else if (itemIndex == LauncherItems.ALBUM2_INDEX) {
                            // 「相簿 #2」：单击仍是"暂无照片"，**快速双击**则清理后台。
                            // 单击动作延后 400ms 执行：期间若来了第二下就取消它 ——
                            // 否则双击时会先冒一个"暂无照片"提示，很突兀。
                            val now = System.currentTimeMillis()
                            if (now - album2LastTapAt > DOUBLE_TAP_MS) album2TapCount = 0
                            album2LastTapAt = now
                            album2TapCount++
                            album2TapJob?.cancel()
                            if (album2TapCount >= 2) {
                                album2TapCount = 0
                                onAlbum2DoubleTap()
                            } else {
                                album2TapJob = scope.launch {
                                    delay(DOUBLE_TAP_MS)
                                    album2TapCount = 0
                                    onExecute(itemIndex)
                                }
                            }
                        } else {
                            onExecute(itemIndex)
                        }
                    },
                    // 长按「拍摄」→ 启动第二层应用（由控制台开关 + 选定应用决定）；
                    // 其它卡片长按无动作（隐藏入口仍是相簿 #3 的右下角热区三连点）。
                    onLongPress = {
                        if (itemIndex == LauncherItems.CAMERA_INDEX) onLongPressCamera()
                    }
                )
            }

            if (rotate) {
                // 先取成局部变量：下面进了 Box 的 content lambda 之后，隐性接收者变成
                // BoxScope，就不能再直接引用 BoxWithConstraintsScope 的 maxWidth/maxHeight 了。
                val canvasW = maxHeight - sidePadding * 2
                val canvasH = maxWidth
                // ★ 竖屏：外层铺满页槽并**居中**，内层按横屏画布布局后逆时针 90°。
                //
                //   居中这一步不能省：横屏画布宽 = maxHeight − 2×sidePadding ≈ 435dp，
                //   比竖直分页器的页槽宽（maxWidth ≈ 337dp）还宽 —— 它是"故意溢出"的
                //   （旋转后正好落满 337×435）。若不居中，超出的部分全堆在右侧 →
                //   表现为"图标整体向右偏移"。
                //
                //   尺寸必须 requiredSize（不能用 size —— size 会被上层约束压回，
                //   那正是"上下两片死区"事故的成因，见 PortraitRotation.kt 注释）。
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .requiredSize(width = canvasW, height = canvasH)
                            .graphicsLayer { rotationZ = -90f },
                        contentAlignment = Alignment.Center
                    ) { card() }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    card()
                }
            }
        }   // ← pageContent lambda 结束

        // ---------------- 轮播本体 ----------------
        // 翻页手感：默认吸附阈值是「半个页宽」（约 156dp≈195px），短滑动一律回弹。
        // 这里降到 15%，并限制一次快滑最多翻 1 页（避免误跳多页）。
        val carouselFling = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
            state = pagerState,
            pagerSnapDistance = androidx.compose.foundation.pager.PagerSnapDistance.atMost(1),
            snapPositionalThreshold = 0.15f
        )

        if (isPortrait) {
            // 竖屏：竖直分页器 —— 手势不再经过旋转坐标系，任何角度滑动都能翻页
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = sidePadding),
                pageSpacing = 16.dp * sizeScale,
                beyondViewportPageCount = 1,
                flingBehavior = carouselFling
            ) { page -> pageContent(page, true) }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = sidePadding),
                pageSpacing = 16.dp * sizeScale,
                beyondViewportPageCount = 1,
                flingBehavior = carouselFling
            ) { page -> pageContent(page, false) }
        }

        // ---------------- 隐藏入口：相簿 #3 居中停稳时，右下角出现透明热区 ----------------
        //
        // 只在「相簿 #3 正好居中且没有在移动」时出现 —— 这样焦点在其它选项、
        // 或者正在滑动途中都不会误开应用列表。热区约 0.5cm（≈32dp），完全透明但可点击。
        val centeredIndex = ((pagerState.currentPage % items.size) + items.size) % items.size
        val album3Settled = centeredIndex == LauncherItems.ALBUM3_INDEX &&
            !pagerState.isScrollInProgress &&
            abs(pagerState.currentPageOffsetFraction) < 0.02f
        if (album3Settled) {
            var tapCount by remember { mutableIntStateOf(0) }
            var lastTapAt by remember { mutableLongStateOf(0L) }
            val hotZoneInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    // ⚠ 竖屏下整个卡片是逆时针 90° 的：横屏的「右下角」经 −90° 旋转后
                    //    落在**物理右上角**。所以热区必须跟着换角，否则按视觉右下角点不到
                    //    （用户实测反馈"相簿3右下角的总应用列表入口点不到"就是这里）。
                    .align(if (isPortrait) Alignment.TopEnd else Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 8.dp)
                    .size(ALBUM3_HOTZONE_DP)
                    .clickable(
                        interactionSource = hotZoneInteraction,
                        indication = null
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt > 1_500L) tapCount = 0
                        lastTapAt = now
                        tapCount++
                        if (tapCount >= 3) {
                            tapCount = 0
                            onAlbum3TripleTap()
                        }
                    }
            )
        }

        // 手电筒不可用时给出提示（改造机可能没有闪光灯）
        //
        // ★ 竖屏方向修正（与用户反馈的"暂无照片/视频 提示文字方向"属于**同一类**问题）：
        //   竖屏时**整页内容**都被逆时针转了 90°（见上面 pageContent 的 rotate 分支），
        //   而这条提示原来是直接画在**未旋转**的横屏坐标系里的 —— 位置只换了角、
        //   **文字本身没转**，于是它看起来与屏幕长边垂直（相对卡片差了 90°）。
        //   修法：把它放进与卡片**完全同一套旋转基准**里（横屏画布 requiredSize + rotationZ = −90°），
        //   这样文字方向与卡片一致；角点也自然对得上（横屏 TopEnd 经 −90° 后落在左上角）。
        if (!flashlightAvailable) {
            // 先取成局部变量：进了 Box 的 content lambda 之后隐性接收者变成 BoxScope，
            // 与上面 pageContent 里同样的原因（见那里的注释）。
            val hintCanvasW = maxHeight
            val hintCanvasH = maxWidth
            val hint: @Composable () -> Unit = {
                Box(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "未检测到闪光灯",
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = (10f * sizeScale).sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 12.dp)
                    )
                }
            }
            if (isPortrait) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .requiredSize(width = hintCanvasW, height = hintCanvasH)
                            .graphicsLayer { rotationZ = -90f }
                    ) { hint() }
                }
            } else {
                // 横屏：本来就不需要旋转，保持原样（TopEnd + 同样的内边距）
                hint()
            }
        }

        // ---------------- 左上角时钟（24 小时制 HH:mm，用户第十轮第 2 项） ----------------
        //
        // 只在设置开启时渲染（`CoverSettings.isShowClockOnHome`，关闭时**完全不渲染** ✓）。
        // 刷新策略：`LaunchedEffect` 里**睡到下一个整分**再更新文本 ——
        //   不是每秒重组（那会白白每秒重组一次整个界面 ✗）；分钟跳变仍及时（+50ms 保险）。
        // 样式：低调（13sp、白色 55%，与卡片文字不同层级）/ 不抢眼。
        // 位置：内容坐标系的**左上角**（竖屏整页 −90° 旋转 ⇒ 物理上落在左下角，
        //   与「未检测到闪光灯」提示同一套角点约定；用户把手机转过来看到的就是左上角 ✓）。
        if (showClock) {
            val clockCanvasW = maxHeight
            val clockCanvasH = maxWidth
            val clockLayer: @Composable () -> Unit = {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 颜色/亮度**直接复用桌面图标标签那套**（SquareHomeScreen.AppCellBig：
                    // Color.White.copy(alpha = 0.92f)、12sp）—— 别再自己另调一个色 ✗；
                    // 字号在标签基础上再放大一点点（用户要求"再大一点"）。
                    Text(
                        text = clockText,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = (13f * sizeScale).sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 7.dp, start = 10.dp)
                    )
                }
            }
            if (isPortrait) {
                // 与卡片/提示同一套旋转基准（否则文字会与长边垂直 ✗、角点也会错 90°）
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .requiredSize(width = clockCanvasW, height = clockCanvasH)
                            .graphicsLayer { rotationZ = -90f }
                    ) { clockLayer() }
                }
            } else {
                clockLayer()
            }
        }
    }
}

/**
 * 时钟文本：`HH:mm`（**24 小时制**，`HOUR_OF_DAY`；不是 12 小时制）。
 * 用 `Locale.US` 保证数字是 ASCII（某些区域设置会用本地数字，副屏字体不一定有 ✗）。
 */
private fun clockHhMm(): String {
    val cal = java.util.Calendar.getInstance()
    return String.format(
        java.util.Locale.US, "%02d:%02d",
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE)
    )
}

/** 距离**下一个整分**还有多少毫秒（+50ms 保险，避免刚好卡在边界上不跳） */
private fun msToNextMinute(): Long {
    val cal = java.util.Calendar.getInstance()
    val passed = cal.get(java.util.Calendar.SECOND) * 1000L + cal.get(java.util.Calendar.MILLISECOND)
    return 60_000L - passed + 50L
}

/**
 * 单个选项：大图标 + 文字标签。
 *
 * 无卡片背景 / 无描边 / 无发光 / 无选中框，图标与文字统一白色；
 * 放大倍数由 ICON_SCALE / TEXT_SCALE 控制。
 * 手电筒开启时在文字下方追加「已开启」提示。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherCard(
    item: LauncherItem,
    torchOn: Boolean,
    layoutScale: Float,
    alpha: Float,
    cardH: Dp,
    iconSize: Dp,
    labelSize: TextUnit,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 按下缩放反馈
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        label = "pressScale"
    )

    // 无卡片背景 / 无描边 / 无发光 / 无选中框：图标与文字直接浮在黑色背景上
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = layoutScale * pressScale
                scaleY = layoutScale * pressScale
                this.alpha = alpha
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongPress,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                // 手电筒开启时整枚图标变黄高亮；其余一律白色
                tint = if (torchOn) TorchAmber else Color.White,
                modifier = Modifier.size(iconSize)
            )
            Spacer(Modifier.height(cardH * 0.06f))
            Text(
                text = item.label,
                color = Color.White,
                fontSize = labelSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
