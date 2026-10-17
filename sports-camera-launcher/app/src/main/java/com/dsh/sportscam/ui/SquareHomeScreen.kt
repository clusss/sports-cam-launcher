package com.dsh.sportscam.ui

import android.appwidget.AppWidgetProviderInfo
import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import com.dsh.sportscam.ui.common.CompactMenuDialog
import com.dsh.sportscam.ui.common.PasswordGate
import com.dsh.sportscam.ui.common.PasswordGateHost
import com.dsh.sportscam.ui.common.rememberPasswordGate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import com.dsh.sportscam.desktop.DesktopItem
import com.dsh.sportscam.desktop.DesktopLayout
import com.dsh.sportscam.desktop.DesktopStore
import com.dsh.sportscam.desktop.WidgetHost
import com.dsh.sportscam.desktop.WidgetSpan
import com.dsh.sportscam.ui.common.showToast
import com.dsh.sportscam.ui.theme.Accent
import com.dsh.sportscam.ui.theme.SportsCamBlack
import com.dsh.sportscam.ui.theme.TorchAmber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 「上拉 → 总应用列表」：**屏幕最下缘的排除带**（dp）。
 *
 * 起手点落在这条带子里就**不认这次上拉** —— 用户实测：从最底部上划会被 vivo 的
 * 系统手势抢走并**直接退出启动器**（✗）。取 24dp（用户给的 20~24dp 区间的上界，
 * 留足余量，因为系统手势热区是按物理边缘算的、且不同 ROM 略有差异）。
 */
private const val PULL_BOTTOM_EXCLUSION_DP = 24f

/** 「上拉 → 总应用列表」：位移阈值（向上滑过 60dp 即触发） */
private const val PULL_DISTANCE_THRESHOLD_DP = 60f

/** 「上拉 → 总应用列表」：走"速度"分支时的最小位移（防止把轻点/抖动当成上划） */
private const val PULL_FLICK_MIN_DP = 24f

/** 「上拉 → 总应用列表」：速度阈值（px/s，向上为正）。900 介于用户给的 800~1000 之间 */
private const val PULL_VELOCITY_THRESHOLD = 900f

/**
 * 「上拉 → 总应用列表」的**方向判据**：|向上位移| 至少要达到 |横向位移| 的这么多倍。
 *
 * 取 2 → `tanθ = |dx|/|dy| ≤ 1/2` → 与水平夹角 ≤ 26.6°，也就是**离竖直 63.4° 以内**才算"上划"。
 * 为什么需要它：用户实测**斜向上 30° 的微弱上划也会进列表**✗ ——
 * 30° 时 |dx|/|dy| ≈ 1.73，远大于 1/2，加这条就被挡住了 ✓。
 * 位移分支、速度分支都必须过这一条（方向不合格一律不算）。
 */
private const val PULL_DIRECTION_RATIO = 2f

/**
 * **跟手动画**的启动门槛（比"能不能进列表"更严，两者互不影响）：
 *   · 方向比取 3 → `tanθ = |dx|/|dy| ≤ 1/3` → 与水平夹角 ≤ 18.4°（离竖直 ≤18°）；
 *   · 且竖直位移要先真正累计到 [PULL_FOLLOW_MIN_DP]（12dp）。
 *
 * ★ 为什么必须加这两条（用户第二轮实测："斜着划桌面也会弹一下"✗）：
 *   根因是**手势刚开始那几个事件里 |dx| 极小**（常常只有 1~2px，甚至 dx=0），
 *   那一瞬间 `|dy| >= 2|dx|` 极易成立 → 跟手被"锁存"→ 之后一路跟着斜划的
 *   **竖直分量**往上走（斜向上 30° 划 200px 就能拉起 ~85px），松手又不满足提交
 *   → 动画原路退回 = 用户看到的那"弹一下" ✗。
 *   现在要求 `|dy|` 先真正走过 12dp **并且那一刻**方向比 ≤ 1/3 才启动跟手：
 *   斜向上 30° 的手势在 |dy| = 12dp 时 |dx| ≈ 6.9dp（比值 1.73 ≫ 1/3）→ **永远不启动** ✓
 *   → 斜划时桌面连 0.x px 都不会动 ✓。
 *
 * ⚠ 只用于**动画**：能否进列表仍按 [PULL_DIRECTION_RATIO]（2）+ 60dp / 900px/s 判定，
 *   既不放宽也不收紧（避免"门槛提高后进不了列表"）。
 */
private const val PULL_FOLLOW_DIRECTION_RATIO = 3f

/** 跟手启动前必须先累计的**竖直**位移（dp，见 [PULL_FOLLOW_DIRECTION_RATIO] 的说明） */
private const val PULL_FOLLOW_MIN_DP = 12f

/** 上拉"跟手 + 滑出/回弹"的动画时长（ms） */
private const val PULL_ANIM_MS = 180

/** 跟手阶段最多把桌面拉走屏幕高度的这个比例（再拉就橡皮筋式停住，避免整屏被拖出） */
private const val PULL_FOLLOW_MAX_FRACTION = 0.45f

/**
 * 拖动落点 → **目标格子坐标**（唯一真源：拖动高亮、松手提交、边缘翻页后的落点都用它）。
 *
 * 公式：**指针落在哪一格就是哪一格** —— `floor(指针页内坐标 / 单格尺寸)`，再按被拖项的
 * 跨格尺寸把锚点夹回合法范围（多格小组件不能把锚点夹到越界，否则 canPlace 一定失败 → 回弹）。
 *
 * ★ 为什么不再用"原格 + 拖动位移"那一套（历史 bug 记录，别再改回去）：
 *   老写法 `(col*cell + cell/2 + dx)/cell` 再 `roundToInt()` 等价于
 *   `roundToInt(col + 0.5 + dx/cell)`，而 Kotlin 的 `roundToInt` 对 .5 **向 +∞ 取整**
 *   → dx=0 时也会得到 col+1，落点整体右下偏一格：
 *     · 拖到空位 → 算出来的是空位右下方那格，命中到别的项 → 回弹；
 *     · 拖到 A 上 → 换成了 A 右下方那格的 B → "覆盖"而不是互换；
 *     · 位移正好 1 格的对角拖动 → 又恰好落回原格 → "拖到空位却退回原地"。
 *   改成"指针所在格"后这些问题天然消失：空位也能落（不看有没有命中项）、
 *   没有半格取整、**而且跨页/翻页后只要换一个页内坐标系就仍然正确**（② 跨屏拖动的关键）。
 *
 * @param px/py 指针在**当前页网格区**里的坐标（px，原点 = 网格左上角）
 */
private fun cellAtPointer(
    px: Float,
    py: Float,
    cellWpx: Float,
    cellHpx: Float,
    cols: Int,
    rows: Int,
    spanCols: Int,
    spanRows: Int
): Pair<Int, Int> {
    if (cellWpx <= 0f || cellHpx <= 0f) return 0 to 0
    val maxCol = (cols - spanCols).coerceAtLeast(0)
    val maxRow = (rows - spanRows).coerceAtLeast(0)
    val col = (px / cellWpx).toInt().coerceIn(0, maxCol)
    val row = (py / cellHpx).toInt().coerceIn(0, maxRow)
    return col to row
}

// ---------------- 「拖动跨屏」用到的两个**非 Compose 状态**容器 ----------------
//
// 为什么不直接用 mutableStateOf：指针每个移动事件都会更新它们、而边缘翻页看门狗
// 每 50ms 读一次；做成 Compose 状态会让整棵桌面跟着重组，还会让轮询协程不断被
// 快照失效打断（计时状态一旦被重置，防抖就废了）。所以这两个容器只给
// **回调与协程**读写，Compose 那边另有专门的 state 只用于绘制高亮。

/** 指针在**根坐标系**里的位置（拖动期间由根 Column 上的旁观指针输入写入） */
private class PointerState {
    var x = 0f
    var y = 0f
    var valid = false

    /**
     * 拖动过程中**锁定**的那根手指（PointerId.value）。
     *
     * 为什么要锁 id：拖动可能跨页/跨屏（自动翻页会把原页的手势节点销毁），
     * 而"松手 ⇒ 提交"改由根节点负责；不锁 id 的话多指时会把"另一根手指抬起"误判成松手 ✗。
     * `Long.MIN_VALUE` = 当前没有锁定（不在拖动中）。
     */
    var dragId: Long = Long.MIN_VALUE

    // ---- 拖拽中的"第二根手指翻页"（第十一轮第 4 项）----
    /** 第二根手指的 id（Long.MIN_VALUE = 当前没有） */
    var secondId: Long = Long.MIN_VALUE
    var secondStartX = 0f
    var secondStartY = 0f
    /** 本次第二指滑动是否已经触发过翻页（一次按下只翻一页，防连翻） */
    var secondTriggered = false
}


/** 边缘自动翻页的计时状态（跨「看门狗协程重启」也要保留，否则防抖失效） */
private class EdgePageState {
    /** 当前停留在哪个边缘：-1 左 / +1 右 / 0 不在边缘 */
    var dir = 0
    /** 本次进入该边缘的时刻（ms） */
    var sinceAt = 0L
    /** 上次自动翻页的时刻（ms），用于最小间隔防抖 */
    var lastFlipAt = 0L
}

/** 边缘自动翻页：左右热区各占页宽的多少 */
private const val EDGE_PAGE_ZONE_FRACTION = 0.12f

/** 边缘自动翻页：指针要在热区内停留多久（ms）才翻页 —— 用户拖动时来得及"放回去" */
private const val EDGE_PAGE_DWELL_MS = 400L

/**
 * 边缘自动翻页的**最小间隔**（ms）：同一方向两次翻页至少隔这么久。
 * 手指停在边缘不动时，靠这个防止"连翻多页"，用户实测最忌讳一下跳好几页。
 */
private const val EDGE_PAGE_COOLDOWN_MS = 700L

/** 边缘自动翻页：指针位置轮询周期（ms） */
private const val EDGE_PAGE_POLL_MS = 50L

/**
 * 「拖应用进文件夹」的判定窗口（ms，第十轮第 5 项）。
 *
 * 用户要求：拖应用停在文件夹上
 *   · **未满 3 秒就松手** → 加入文件夹 ✓（与"交换手势"区分开）；
 *   · **停留 ≥ 3 秒还没松手** → 才切换成**交换模式** ✓（与文件夹换位）。
 * 所以 3000ms 是"加入 / 交换"的分界线；到点会落盘一行 [Drag] 悬停文件夹=… 模式=交换。
 */
private const val FOLDER_MERGE_HOVER_MS = 3000L

/**
 * 桌面上要**隐藏**的包名集合（= 用户设为"私密应用"的那些）。
 *
 * 来源是 settings-console 的**单一真相**：`CoverSettings.privateAppPackages(context)` ——
 *   · 返回的是**生效值**：`isPrivateAppsEnabled == false` 时为空集 → 桌面不隐藏任何东西 ✓；
 *   · 启用后返回用户选过的包名集合（prefs 写坏/超 200 条都由他那边容错）；
 *   · ⚠ **不要**换成 `privateAppsPreferred()`：那是"不看启停的原始集合"，只给管理界面用。
 *
 * 每次组合都重读一次（代价很小：内存里的 prefs + 一个很小的集合）⇒
 * "在私密应用界面改完、回到桌面"立刻生效，不需要额外的刷新钩子 ✓，也不会拿到陈旧值 ✓。
 */
private fun desktopHiddenPackages(context: android.content.Context): Set<String> =
    com.dsh.sportscam.cover.CoverSettings.privateAppPackages(context)

/**
 * 刷新键「② 重投启动器自身」前的等待（ms）。
 *
 * 取 500ms 的三个理由：
 *  1. 第 ① 步 `recreateOverlay` 只是给服务发一条指令，服务里是"先 hide 再 sync 重新挂出"，
 *     窗口的移除/重建不是同步完成的 —— 等 500ms 让它稳定下来，再去动前台；
 *  2. `CoverAppLauncher.launchCoverHomeToCover` 内部有 **400ms 自身投放去重**
 *     （`SELF_PLACE_DEDUP_MS`，防止 singleTask 界面被连投两次 → onNewIntent 重置导航栈）。
 *     等 500ms > 400ms，可以保证"点击前刚好有别的路径投过一次"时，我们这次**不会被误判去重** ✓；
 *     而真正的连投（相隔几十毫秒）仍然会被它自己的去重挡住 ✓ —— 我们**不绕过**它的语义 ✓；
 *  3. 副屏很小，500ms 在用户感知上仍然是"点完马上就好了"。
 */
private const val REFRESH_REPLACE_DELAY_MS = 500L

/**
 * 桌面右上角「刷新」的两步动作：**① 重挂悬浮窗 →（500ms）→ ② 把启动器自身重投到副屏**。
 *
 * ★ 为什么不能直接用界面的 `rememberCoroutineScope` + `delay`（本轮唯一的实现取舍）：
 *   悬浮窗模式下，桌面这棵 Compose 树就跑在**那个 2038 悬浮窗里**；而第 ① 步
 *   `recreateOverlay` 会把这个悬浮窗**连同这棵树一起销毁/重建** ——
 *   `rememberCoroutineScope` 随组合取消，于是"500ms 后执行第 ② 步"的那个协程
 *   **永远不会跑到** ✗。而这恰恰是本功能唯一要生效的场景（悬浮窗模式）✗✗。
 *   所以第 ② 步放在这个**进程级、一次性、自动结束**的协程里：
 *   最多跑 ~1 秒就结束（不是常驻/轮询），不存在泄漏；进程被杀自然停止 ✓。
 *
 * ★ 第 ① 步仍走 `CoverKeeperService.recreateOverlay`（与之前完全一样），
 *   第 ② 步走 `CoverAppLauncher.launchCoverHomeToCover(context, coverDisplayId)`，
 *   它自己的 **400ms 去重** 与"不做 presence 跳过"的既有语义**都保留**（我们不绕过 ✓）。
 *   若被它去重，返回的 `Result.Success.via` 里就写着"去重丢弃…"，会原样落盘 ✓。
 */
private object OverlayRefreshBridge {

    /** 一次性动作的专用作用域（进程级；单个任务 ~1s 结束，不常驻） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun recreateThenReplaceSelf(context: android.content.Context) {
        val appContext = context.applicationContext

        // ---------------- ① 重挂悬浮窗 ----------------
        // 注：recreateOverlay 只负责发指令（服务内部 hide → syncOverlayWindow 重新挂出），
        //    这里拿不到"挂出成功"的回执；服务侧会落盘 `[Keep] 收到 RECREATE_OVERLAY` 与 Overlay show/hide。
        runCatching { com.dsh.sportscam.cover.CoverKeeperService.recreateOverlay(appContext) }
            .onFailure {
                com.dsh.sportscam.cover.CoverDebugLog.log("Desktop", "刷新①：重挂悬浮窗指令发送失败 ${it.message}")
            }
        com.dsh.sportscam.cover.CoverDebugLog.log("Desktop", "刷新①：已请求重挂悬浮窗（服务侧见 [Keep] RECREATE_OVERLAY）")

        // ---------------- ② 稍后重投启动器自身 ----------------
        scope.launch {
            delay(REFRESH_REPLACE_DELAY_MS)
            val coverId = runCatching { com.dsh.sportscam.cover.CoverDisplay.coverDisplayId(appContext) }
                .getOrDefault(android.view.Display.DEFAULT_DISPLAY)
            if (coverId == android.view.Display.DEFAULT_DISPLAY) {
                com.dsh.sportscam.cover.CoverDebugLog.log("Desktop", "刷新②：没有副屏，跳过重投启动器")
                showToast(appContext, "已重挂悬浮窗（无副屏，未重投）")
                return@launch
            }
            val result = runCatching {
                com.dsh.sportscam.cover.CoverAppLauncher.launchCoverHomeToCover(appContext, coverId)
            }.getOrElse {
                com.dsh.sportscam.cover.CoverAppLauncher.Result.Failure("调用异常：${it.message}")
            }
            // 落盘：成功（含 via/verified，去重也会在这里显形）/ 失败原因 —— 后续排查就看这两行
            when (result) {
                is com.dsh.sportscam.cover.CoverAppLauncher.Result.Success ->
                    com.dsh.sportscam.cover.CoverDebugLog.log(
                        "Desktop",
                        "刷新②：重投启动器成功 via=${result.via} verified=${result.verified} cover=$coverId"
                    )
                is com.dsh.sportscam.cover.CoverAppLauncher.Result.Failure ->
                    com.dsh.sportscam.cover.CoverDebugLog.log(
                        "Desktop",
                        "刷新②：重投启动器失败 reason=${result.reason} cover=$coverId"
                    )
            }
            // 提示走自绘 CoverToast（独立的小 2038 窗口，不会随悬浮窗重建而消失）——
            // 绝不能用系统 Toast：悬浮窗上下文(2038)弹 2005 会直接崩进程。
            // 文案按**实际结果**给，失败了不会假装成功 ✓（去重也算成功：说明 400ms 内刚投过，界面本来就是新的）。
            showToast(
                appContext,
                when (result) {
                    is com.dsh.sportscam.cover.CoverAppLauncher.Result.Success -> "已重挂悬浮窗并重投启动器"
                    is com.dsh.sportscam.cover.CoverAppLauncher.Result.Failure -> "已重挂悬浮窗，重投启动器失败"
                }
            )
        }
    }
}


/**
 * SquareHome 式桌面。
 *
 * 布局：每页 8 格（横屏 4×2、竖屏 2×4），**显式坐标**摆放，
 * 应用 1×1、小组件按自身最小尺寸占 1~n 格，另有两种**内置功能项**（1×1）：
 *   · **[DesktopItem.CameraModeSwitch]**「运动相机模式」开关：显示 开/关，点击即切换；
 *   · **[DesktopItem.PrivateAppsEntry]**「私密应用」入口：只有锁形图标，点击发 [onOpenPrivateApps]。
 * 这两种项**不是系统 AppWidget**，就是本桌面数据模型里的普通项（能拖动/换位/删除），
 * 首次运行由 [DesktopStore.ensurePresetItems] **预置一次**。
 *
 * 交互（应用与小组件**完全同权**）：
 *  · 长按任一图标/小组件 → 进入**编辑模式**并立即开始拖动；所有项右上角常驻红色 ×；
 *  · 双指捏合 → 同样进入编辑模式；
 *  · 拖动松手：落在空位就移动；落在另一项上就**换位**（大小不同也可以，
 *    但两边都要放得下，放不下则不换）；
 *  · 点红色 × → 从桌面移除（小组件同时回收 appWidgetId）；
 *  · 编辑模式下桌面**不收缩、两侧也没有按钮**（那对「添加应用/添加小组件」按钮已删：
 *    网格列宽因此编辑前后完全一致，图标不会跳位）；添加入口 = **点空位的「＋」**；
 *    底部有「增加新页面 / 删除本页 / 完成」；
 *  · 编辑模式下把项拖到左/右边缘并停住 ≥400ms → **自动翻页**（跨屏移动，见 [watchEdgePage]）；
 *  · **屏幕任意位置快速上划** → 打开总应用列表（跟手 + 松手滑出/回弹动画；
 *    最下缘 24dp 内起手不认，避免被 vivo 系统手势抢走，见 [detectPullUpToAppList]）；
 *  · 右上角刷新按钮 → 重挂悬浮窗（`CoverKeeperService.recreateOverlay`，**仅悬浮窗模式显示**）；
 *  · 入口隐藏：相簿界面右上角「xx 项」连点三次。
 */
@Composable
fun SquareHomeScreen(
    appListUiState: AppListUiState,
    onBack: () -> Unit,
    onRequestAddApps: () -> Unit,
    /**
     * 「底边上拉 → 总应用列表」的导航回调。
     *
     * 默认复用 [onRequestAddApps]（上层 MainActivity 里它就是 `push(Screen.AppList)`），
     * 所以不传也能用、也不用改 MainActivity 的签名；上层若想把这条路单独接进
     * 「启动器密码」校验或其它前置逻辑，显式传一个自己的 lambda 即可覆盖。
     */
    onOpenAppList: () -> Unit = onRequestAddApps,
    /**
     * 「私密应用」入口项（桌面上的锁形 1×1）被点击时的回调。
     *
     * 桌面这边**只发一个回调**（界面本体 = list-viewer 的 `ui/PrivateAppsScreen.kt`）。
     * MainActivity 已接线：`onOpenPrivateApps = { push(Screen.PrivateApps) }`（第七轮）。
     *
     * ⚠ **这里刻意不弹密码**：密码门在 `PrivateAppsScreen` 内部的 `PrivateAccessGate` 里
     *   （每次进入必弹一次）。入口若再弹一次，用户会**连输两遍** ✗ —— 所以别在这里加 guard。
     * 默认空实现 ⇒ 万一以后没人传，点击也不会崩、不会瞎猜界面。
     */
    onOpenPrivateApps: () -> Unit = {},
    /**
     * 点击桌面**文件夹**项时的回调（第十轮第 5 项）。
     * 文件夹内容界面由 list-viewer 负责；桌面这边只发一个 id。
     * ⚠ MainActivity 需要接线：onOpenFolder = { id -> push(Screen.Folder(id)) }（他那边加枚举）。
     * 默认空实现 ⇒ 没接线时点击无动作、也不会崩。
     */
    onOpenFolder: (Long) -> Unit = {},
    /**
     * 点击桌面**「最近删除」入口**（1×1 垃圾桶项）时的回调（第十二轮第 2 项）。
     * ⚠ 最近删除可能需要密码（「保护最近删除」开关）—— **密码门不在这边** ✓：
     *   与文件夹/私密应用入口一致，交给目标界面（settings-console 已用 guardedPush 接好）。
     * MainActivity 接线：`onOpenTrash = { guardedPush(Screen.Trash, PasswordGate.Purpose.Trash, "输入密码进入最近删除") }`
     */
    onOpenTrash: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val version = DesktopStore.versionState().intValue

    // ★ 方向按**显示器真实尺寸**判断，不读 LocalConfiguration 的值。
    //   本工程 Activity 声明了 configChanges，旋转时不重建，配置更新有滞后 ——
    //   原来用 Configuration 会导致"刚进桌面时横屏却按竖屏渲染成 2×4"，
    //   要手动转一次屏才恢复（用户实测反馈）。见 CoverDisplay.isPortraitNow 的注释。
    //
    //   但仍要**读一下** LocalConfiguration：它是配置变化的订阅点，
    //   保证旋转后本界面会重组（只用它触发重组，不用它的 orientation 值）。
    LocalConfiguration.current
    val isPortrait = com.dsh.sportscam.cover.CoverDisplay.isPortraitNow(context)
    val cols = if (isPortrait) 2 else 4
    val rows = DesktopStore.PER_PAGE / cols

    val items = remember(version, isPortrait) { DesktopStore.items(context, isPortrait) }
    val pages = remember(version, isPortrait) { DesktopStore.totalPages(context, isPortrait) }
    val pagerState = rememberPagerState { pages }

    var editMode by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<DesktopItem?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var showWidgetPicker by remember { mutableStateOf(false) }
    var showEmptyMenu by remember { mutableStateOf(false) }

    // 拖动落点换算用的格子像素
    var cellWpx by remember { mutableStateOf(0f) }
    var cellHpx by remember { mutableStateOf(0f) }

    // ---------------- 拖动跨屏（②）：指针位置 / 当前页网格原点 ----------------
    // dragPointer：指针在**根坐标系**里的位置。只在"正在拖动"时写（高亮要跟着它重组）；
    //             平时（点击/翻页）不写 → 不会每次触摸都重组整棵桌面。
    // pageOrigin ：当前页网格区左上角在根坐标系里的位置（DesktopPage 用 positionInRoot 上报，
    //             只收"当前页"那一份）。指针是根坐标系的，落点/边缘判定都要靠它换到页内坐标。
    //             页内坐标 = 指针 − pageOrigin。
    var dragPointer by remember { mutableStateOf<Offset?>(null) }
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }
    // 上面两个的"非 Compose 镜像"，给协程/回调读（原因见 PointerState / EdgePageState 注释）
    val pointerState = remember { PointerState() }
    val edgePageState = remember { EdgePageState() }
    var edgePageJob by remember { mutableStateOf<Job?>(null) }

    // ---------------- 「上拉 → 总应用列表」的跟手/动画状态 ----------------
    // pullActive/pullPx：手指按住期间的**跟随位移**（px，向上为正）。按下期间不做动画，
    //                   位移与手指 1:1 —— 这样"跟手"才不会像隔着一层橡皮。
    // releaseAnim：松手后的**滑出/回弹**动画（180ms）。手指在动的时候它不参与，
    //             松手时先 snapTo 到当前位移再 animateTo，避免"从 0 突然跳一下"。
    var pullActive by remember { mutableStateOf(false) }
    var pullPx by remember { mutableStateOf(0f) }
    val pullReleaseAnim = remember { Animatable(0f) }

    // 悬浮窗模式开关：右上角「重挂悬浮窗」只在悬浮窗模式下才有意义（非悬浮窗模式没有 2038 窗口）。
    // 这里在**组合期直接读一次设置**：本界面每次进入（控制台改完返回时会被重新组合）都会重新读，
    // 所以开关一改、回到桌面立刻生效；不需要给 prefs 挂观察者，也不会多一次弹层。
    val overlayModeOn = com.dsh.sportscam.cover.CoverSettings.isOverlayModeEnabled(context)

    // 运动相机模式开关（桌面上的 1×1 功能项要显示它、点击切换它）。
    //
    // ★ 这里刻意**不缓存**在 `remember` 里：每次组合都重新读一次设置，
    //   这样"从控制台改完返回桌面"立刻是新值 ✓（否则会读到进入桌面时的旧值 ✗）。
    //   `cameraModeTick` 只是用来在**本界面内**点开关后强制再读一次 —— 满足
    //   用户要求的"切换后立即重绘该格子"✓（读一下这个 state 就建立了依赖，tick++ 即失效重组）。
    var cameraModeTick by remember { mutableStateOf(0) }
    cameraModeTick
    val cameraModeOn = com.dsh.sportscam.cover.CoverSettings.isCameraModeEnabled(context)

    /** 点桌面上的「运动相机模式」开关：与 ConsoleScreen 完全一致的两步（写设置 + 同步导航栈底页） */
    val toggleCameraMode: () -> Unit = {
        val next = !com.dsh.sportscam.cover.CoverSettings.isCameraModeEnabled(context)
        com.dsh.sportscam.cover.CoverSettings.setCameraModeEnabled(context, next)
        // 只换导航栈的底页（stack[0]），当前停留的界面不动 —— 桌面不会被踢掉 ✓
        com.dsh.sportscam.syncNavStackHomeToCameraMode(next)
        cameraModeTick++
    }

    // 桌面上要**隐藏**的包名集合（= 私密应用）。
    // 来源是 settings-console 的单一真相 `CoverSettings.privateAppPackages`（生效值；
    // 私密功能关掉时返回空集 ⇒ 不隐藏任何东西 ✓）。这里不另存一份 ✓。
    val hiddenPackages = desktopHiddenPackages(context)

    // ---------------- 「拖应用进文件夹」悬停判定（第十轮第 5 项） ----------------
    // 悬停目标 = 指针所在格上的 Folder 项（与落点用同一个 cellAtPointer ✓）；
    // 只有"拖的是应用"才算（拖小组件/文件夹碰到文件夹仍走常规换位 ✓）。
    var folderSwapMode by remember { mutableStateOf(false) }
    // ⚠ 这里**不能用 `dragCell` 那个 val**（它声明在本段之后，局部声明不能先用）；
    //   直接按同一个顶层公式 `cellAtPointer` 现算一次 ⇒ 与落点算法完全一致 ✓。
    val hoverFolder: DesktopItem.Folder? = run {
        val dragItem = dragging
        val ptr = dragPointer
        if (dragItem !is DesktopItem.App || ptr == null || cellWpx <= 0f || cellHpx <= 0f) return@run null
        val cell = cellAtPointer(
            ptr.x - pageOrigin.x, ptr.y - pageOrigin.y,
            cellWpx, cellHpx, cols, rows, dragItem.colSpan, dragItem.rowSpan
        )
        DesktopLayout.itemAt(items, pagerState.currentPage, cell.first, cell.second) as? DesktopItem.Folder
    }
    // 进入悬停 → 记"模式=加入"；满 3s 还没松手 → 切"模式=交换"（并把提示画在格子上 ✓）
    LaunchedEffect(hoverFolder?.key) {
        val f = hoverFolder
        if (f == null) {
            folderSwapMode = false
            return@LaunchedEffect
        }
        folderSwapMode = false
        val startedAt = System.currentTimeMillis()
        com.dsh.sportscam.cover.CoverDebugLog.log(
            "Drag", "[Drag] 悬停文件夹=() 已停留=0ms 模式=加入（<3000ms 松手即加入）"
        )
        delay(FOLDER_MERGE_HOVER_MS)
        folderSwapMode = true
        com.dsh.sportscam.cover.CoverDebugLog.log(
            "Drag",
            "[Drag] 悬停文件夹=() " +
                "已停留=ms 模式=交换（松手将换位）"
        )
    }

    // 文件夹重命名浮层（同树浮层，绝不用系统 Dialog ✗ —— 2038 上下文弹 Dialog 会崩）
    var renameFolderId by remember { mutableStateOf<Long?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    // ---------------- 桌面壁纸（第十轮第 4 项） ----------------
    // 规则：有自定义壁纸就按 **ContentScale.Crop** 铺满桌面；`load()` 返回 null 就用默认黑底 ✓。
    // 刷新时机：桌面是"push 出去再回来"就会**重新组合**，这里的 LaunchedEffect(Unit) 会重跑
    //   ⇒ 编辑器里设完壁纸返回桌面立刻生效 ✓（不需要额外的 refreshTick）。
    // ⚠ `load()` 是**小同步解码**（~10-20ms）：只在 IO 线程解一次，**绝不在 draw/每帧里调** ✗。
    var wallpaperBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        wallpaperBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.dsh.sportscam.cover.WallpaperStore.load(context)?.asImageBitmap()
            }.getOrNull()
        }
    }

    // ---------------- 启动器密码：进入编辑模式的拦截 ----------------
    // 用 ui/common/PasswordGate.kt 的「控制器 + 同树宿主」这一对：
    //   · 它是**同树浮层**，不是系统 Dialog —— 悬浮窗的窗口上下文是 2038，
    //     系统 Dialog 会新建 TYPE_APPLICATION(2) 窗口，窗口类型冲突直接把进程崩掉；
    //   · `guard()` 内部先看「开关开着 + 真设过密码」（PasswordGate.needsPassword），
    //     不满足就**直接执行动作、完全不弹层** —— 所以没启用密码时下面这条路上
    //     的行为与改动前一字不差（不会多一次弹层/多一次重建）。
    // 每个界面树各自一份状态（不要跨树共享）：悬浮窗那棵树重建后会重新 remember。
    val gate = rememberPasswordGate()

    /**
     * 进入编辑模式的**唯一入口**（长按图标 / 双指捏合都走它）：
     * 需要密码就先弹密码层，验证通过（或本来就不需要密码）才真的进编辑模式。
     */
    val enterEditMode: () -> Unit = {
        if (!editMode) gate.guard(context, "输入密码进入编辑模式") { editMode = true }
    }

    // 编辑模式：**不做过渡动画**（之前用 animateFloatAsState 驱动整页缩放/边框宽度，
    // 在副屏上明显掉帧，直接量化为 0/1 后反而更跟手）
    val editProgress = if (editMode) 1f else 0f

    // 桌面里的应用图标/名称复用总应用列表的缓存。
    //
    // ⚠ key 必须是 Unit：之前写成 LaunchedEffect(loaded, loading)，而协程内部又把
    //   loading 置为 true —— key 一变，**正在执行的加载协程当场被取消**，
    //   withContext(IO) 半途夭折、loaded 永远为 false。
    //   表现就是：清后台重进后桌面只剩包名、图标和名称都没有，
    //   直到进编辑模式添加一个应用（触发另一次加载）才恢复。
    LaunchedEffect(Unit) {
        if (!appListUiState.loaded) {
            appListUiState.loading = true
            appListUiState.apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                queryLaunchableApps(context)
            }
            appListUiState.loaded = true
            appListUiState.loading = false
        }
    }
    LaunchedEffect(pages) {
        if (pagerState.currentPage > pages - 1) pagerState.scrollToPage(pages - 1)
    }

    // ★ 首次运行**预置**两个内置 1×1 项（「运动相机模式」开关 + 「私密应用」入口）。
    //   用户要的"装好就摆在桌面上"就是靠这一步（不是系统 AppWidget 那条路 ✗）。
    //   `ensurePresetItems` 内部有 flag，**只做一次**；而且它只写当前方向的位置，
    //   另一个方向由 DesktopStore.items() 既有的"自动补位"补齐 ✓。
    //   放在这里（桌面第一次被组合时）执行：这两个项只有桌面用得到；
    //   若你希望"首次启动 App 就预置"，也可以在 MainActivity 里调一次同名函数（一行）。
    LaunchedEffect(Unit) {
        DesktopStore.ensurePresetItems(context, isPortrait)
    }

    val iconMap: Map<String, ImageBitmap?> = remember(appListUiState.apps) {
        appListUiState.apps.associate { it.packageName to it.icon }
    }
    val labelMap: Map<String, String> = remember(appListUiState.apps) {
        appListUiState.apps.associate { it.packageName to it.label }
    }

    // ---------------- 拖动落点 / 边缘翻页（② 跨屏拖动） ----------------

    /**
     * 起手点是否落在**当前页的某个小组件**矩形里（根坐标系）。
     *
     * 用途（用户第 7 项）：小组件内部可能是可滑动列表，我们从外面**无法知道**它有没有
     * 消费掉事件 ✗，所以用保守规则：起手点落在小组件上就**让位** —— 不注册上拉手势、
     * 也不 consume，让小组件自己处理。空白格 / 图标上照常拉起总应用列表 ✓。
     * 读的都是**调用时刻**的当前值（currentPage / pageOrigin / cell 尺寸），所以不会用到陈旧页。
     */
    fun isDownOnWidget(position: Offset): Boolean {
        if (cellWpx <= 0f || cellHpx <= 0f) return false
        val page = pagerState.currentPage
        val ox = pageOrigin.x
        val oy = pageOrigin.y
        return items.any { item ->
            item is DesktopItem.Widget && item.page == page &&
                androidx.compose.ui.geometry.Rect(
                    ox + cellWpx * item.col,
                    oy + cellHpx * item.row,
                    ox + cellWpx * (item.col + item.colSpan),
                    oy + cellHpx * (item.row + item.rowSpan)
                ).contains(position)
        }
    }

    /**
     * 当前拖动落点（页内格子坐标）：指针位置 → 格子。
     *
     * ⚠ 刻意做成**本地函数**而不是组合期算好的 val：`onDragEnd` 这个回调是挂在
     * 图标自己的 `pointerInput(label)` 里的，那个块不会因为重组而重建 ——
     * 它捕获的是**旧组合**的闭包。所以落点必须在"调用那一刻"重新读
     * dragPointer / pageOrigin / cellWpx…（它们都是 `remember` 的状态对象，闭包再旧读到的也是当前值 ✓）。
     * 若把组合期的 `dragCell` 那个 val 直接捕获进去，拿到的会是"上次拖动开始前"的 null ✗。
     */
    fun currentDragCell(): Pair<Int, Int>? {
        val item = dragging ?: return null
        val ptr = dragPointer ?: return null
        if (cellWpx <= 0f || cellHpx <= 0f) return null
        return cellAtPointer(
            ptr.x - pageOrigin.x, ptr.y - pageOrigin.y,
            cellWpx, cellHpx, cols, rows, item.colSpan, item.rowSpan
        )
    }

    // 组合期算一份给"落点高亮"用（读的是同一个函数，天然一致）
    val dragCell: Pair<Int, Int>? = currentDragCell()

    /**
     * 松手提交：落点 = **指针当前所在的格子**（[cellAtPointer]），页 = **当前显示的页**。
     *   · 落回自己原来的格子（同页同格）→ 什么都不做；
     *   · 空位 → 移动过去（只要自己放得下）；
     *   · 另一项 → 两者**互换**（大小不同也行；跨页也能换，两边都得放得下）。
     *
     * ★ 调用时机（第十轮修的两个 bug 就在这里）：
     *   · **只在手指真的抬起时**调用（由根节点的指针观察者发起，onDragEnd 只作兜底）；
     *   · **绝不**在手势 cancel 时调用（自动翻页会销毁原页 → 手势 cancel）；
     *   · 所以"翻页瞬间被放下/换位" ✗ 和"同页拖动总是回弹" ✗ 都不会再出现。
     *
     * 每次提交都会落盘一行 `[Drag] …`，用户复现一次即可判读（原因写在各分支的 `reason` 里）。
     */
    fun handleDrop() {
        val dragged = dragging ?: return      // 没有拖动（可能已被另一个回调提交过）：静默返回
        // 必须在清状态**之前**读（currentDragCell 依赖 dragging / dragPointer）
        val ptr = dragPointer
        val cell = currentDragCell()
        val page = pagerState.currentPage
        val samePage = page == dragged.page
        var committed = false
        var reason = ""

        // 先清拖动状态（下面所有分支都算"本次拖动结束"）
        dragging = null
        dragPointer = null
        dragOffset = Offset.Zero
        pointerState.dragId = Long.MIN_VALUE
        // 拖动结束：停掉边缘翻页看门狗（若还在跑）
        edgePageJob?.cancel()
        edgePageJob = null

        if (cell == null) {
            reason = "无指针位置或格子尺寸（未提交）"
        } else {
            val targetCol = cell.first
            val targetRow = cell.second
            if (samePage && targetCol == dragged.col && targetRow == dragged.row) {
                reason = "落回原格（未提交）"
            } else {
                // ★★ 第十一轮第 2 项（"跨页后两项重合"的根因）：占用判断必须用**最新落盘数据**。
                //    不能再用组合期捕获的 items：根节点的 pointerInput(Unit) 只创建一次，
                //    捕获的是**第一帧**的列表 —— 之后的移动/翻页都不会更新它 ⇒ "目标格已占用"判断失效
                //    ⇒ 把项写到别人的格子上 ⇒ 两项重合 ✗（用户存档里 deepseek 与 private_apps 都写着 4,3,1）
                val fresh = DesktopStore.items(context, isPortrait)
                val target = DesktopLayout.itemAt(fresh, page, targetCol, targetRow)
                val others = fresh.filterNot { it.key == dragged.key || it.key == target?.key }
                // ★ 拖应用停在文件夹上：**<3s 松手 = 加入文件夹**（≥3s 已切交换模式 → 走下面常规换位 ✓）。
                //   现场重算悬停（局部函数会被旧闭包捕获，不能直接用组合期的 hoverFolder）。
                if (target is DesktopItem.Folder && dragged is DesktopItem.App && !folderSwapMode) {
                    val ok = DesktopStore.addAppToFolder(context, target.folderId, dragged.packageName)
                    committed = ok
                    reason = if (ok) "拖入文件夹「" + target.name + "」（停留<3s → 加入）✓"
                    else "加入文件夹失败（已在文件夹里？）"
                } else if (target == null || target.key == dragged.key) {
                    // 落到空位：只要求自己放得下
                    val ok = DesktopLayout.canPlace(
                        others, dragged.colSpan, dragged.rowSpan,
                        page, targetCol, targetRow, cols, rows
                    )
                    if (ok) {
                        DesktopStore.moveTo(context, isPortrait, dragged.key, page, targetCol, targetRow)
                        committed = true
                        reason = if (samePage) "落到空位 → 移动 ✓" else "跨页落到空位 → 移动 ✓"
                    } else {
                        reason = "空位放不下（未提交）"
                    }
                } else {
                    // 换位：交换后两边都得放得下（小组件大小不同也能换；跨页时各回各的页）
                    val okDragged = DesktopLayout.canPlace(
                        others, dragged.colSpan, dragged.rowSpan,
                        target.page, target.col, target.row, cols, rows
                    )
                    val okTarget = DesktopLayout.canPlace(
                        others, target.colSpan, target.rowSpan,
                        dragged.page, dragged.col, dragged.row, cols, rows
                    )
                    if (okDragged && okTarget) {
                        // ★ 互换必须**成对、原子**地写入：DesktopStore.swap 内部是
                        //   `save()` 里基于同一份 list 先把两者位置都改好、再一次性落盘。
                        //   绝不能拆成"先 moveTo 把 A 挪到 B 的位置、再 moveTo 把 B 挪到 A"——
                        //   第一次写盘之后 A、B 就重叠在同一格（中间任何一次读盘/渲染都会画出
                        //   两张叠在一起的卡片），第二次一旦失败/被打断，重叠状态就永久留在数据里，
                        //   这正是用户看到的"交换时覆盖而非互换"。
                        DesktopStore.swap(context, isPortrait, dragged.key, target.key)
                        committed = true
                        reason = "与 ${target.key} 换位 ✓"
                    } else {
                        reason = "换位放不下（未提交）"
                    }
                }
            }
        }

        // ★ 落盘日志（用户复现一次就能判读）：起手项 / 当前页 / 指针 / 目标格 / 是否同页 / 是否提交 + 原因
        com.dsh.sportscam.cover.CoverDebugLog.log(
            "Drag",
            "[Drag] 起手=${dragged.key} 起手格=(${dragged.page},${dragged.col},${dragged.row}) " +
                "当前页=$page 指针=${ptr?.let { "(${it.x.toInt()},${it.y.toInt()})px" } ?: "无"} " +
                "目标格=${cell?.let { "($page,${it.first},${it.second})" } ?: "无"} " +
                "是否同页=$samePage 提交=$committed($reason)"
        )
    }


    /**
     * 拖拽中的"第二根手指翻页"（第十一轮第 4 项）。
     *
     * 关键：**只翻页、不动 dragging** ⇒ 拖拽不被中断、落点自动按新页算（复用第 3 项那套机制 ✓）。
     * 方向约定与分页器一致：第二指**右滑(sdx>0) → 上一页**，左滑 → 下一页。
     */
    suspend fun turnPageBy(delta: Int) {
        val from = pagerState.currentPage
        val to = (from + delta).coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
        if (to == from) return
        com.dsh.sportscam.cover.CoverDebugLog.log(
            "Drag",
            "[Drag] 双指翻页：方向=" + (if (delta > 0) "左" else "右") +
                " 页=" + (from + 1) + "→" + (to + 1) + "（拖拽中，未提交）"
        )
        pagerState.animateScrollToPage(to)
    }

    /**
     * 拖动期间的**边缘自动翻页**看门狗（由 onDragStart 启动、松手时取消）。
     *
     * 规则（用户第 6 项）：
     *   · 指针进入左右边缘热区（各占页宽 [EDGE_PAGE_ZONE_FRACTION] = 12%）并**停留** ≥ [EDGE_PAGE_DWELL_MS]（400ms）
     *     → 切上一页/下一页；切页过程中**保持拖动状态**（dragging 不动），落点按新页计算 ✓;
     *   · **防抖**：同一方向两次翻页至少隔 [EDGE_PAGE_COOLDOWN_MS]（700ms），且每次翻页后
     *     重新计 400ms 停留 —— 手指一直按在边缘也只会每 ~700ms 翻一页，不会连跳 ✗；
     *   · 到第 1 页/最后一页就不动了（只把停留计时往后挪，不再尝试）。
     *
     * 为什么轮询 50ms 而不是用事件：指针位置由根 Column 的旁观指针输入写进 [pointerState]，
     * 用轮询读的是"当前的静止位置"，正好对应"停留多久"这个语义，也顺带躲开
     * Compose 状态读取导致协程重启/动画被打断的坑（计时状态放在 [edgePageState] 里）。
     */
    suspend fun watchEdgePage() {
        // 每次拖动重新计时（不放在协程外层 LaunchedEffect 里，避免协程重启把防抖清零）
        edgePageState.dir = 0
        edgePageState.sinceAt = 0L
        edgePageState.lastFlipAt = 0L
        while (true) {
            delay(EDGE_PAGE_POLL_MS)
            val pageW = cellWpx * cols
            if (pageW <= 0f) continue
            if (!pointerState.valid) {
                edgePageState.dir = 0
                edgePageState.sinceAt = 0L
                continue
            }
            val leftEdge = pageOrigin.x + pageW * EDGE_PAGE_ZONE_FRACTION
            val rightEdge = pageOrigin.x + pageW * (1f - EDGE_PAGE_ZONE_FRACTION)
            val dir = when {
                pointerState.x <= leftEdge -> -1
                pointerState.x >= rightEdge -> 1
                else -> 0
            }
            val now = System.currentTimeMillis()
            if (dir == 0) {
                edgePageState.dir = 0
                edgePageState.sinceAt = 0L
                continue
            }
            if (dir != edgePageState.dir) {
                // 刚进入这一侧的边缘：开始计停留时间
                edgePageState.dir = dir
                edgePageState.sinceAt = now
                continue
            }
            if (now - edgePageState.sinceAt < EDGE_PAGE_DWELL_MS) continue
            if (now - edgePageState.lastFlipAt < EDGE_PAGE_COOLDOWN_MS) continue

            val from = pagerState.currentPage
            val to = from + dir
            if (to < 0 || to > pages - 1) {
                // 已经在头/尾：不要每个周期都试，重新等一轮停留
                edgePageState.sinceAt = now
                continue
            }
            edgePageState.lastFlipAt = now
            edgePageState.sinceAt = now
            com.dsh.sportscam.cover.CoverDebugLog.log(
                "Desktop",
                "边缘自动翻页：方向=${if (dir < 0) "左" else "右"} 第${from + 1}→${to + 1}页 " +
                    "指针x=${pointerState.x.toInt()}px 页宽=${pageW.toInt()}px"
            )
            // 直接 await：翻页动画期间不再判定，天然多一层防抖
            pagerState.animateScrollToPage(to)
        }
    }

    // 底边上拉手势是否可用：
    //   · 编辑模式下手势关闭（避免和"拖动图标 / 点空位 + / 工具条"抢事件）；
    //   · 同树全屏浮层（小组件选择器 / 空位菜单 / 密码层）打开时也关闭 ——
    //     它们是画在桌面**之上**的，但自身只吃"点击"，上滑/拖动会穿透到本层的
    //     Initial 观察者（我这条手势特意用 requireUnconsumed=false 才能不干扰图标拖动），
    //     不锁的话会在浮层背后把总应用列表推进导航栈。
    val swipeLocked = editMode || showWidgetPicker || showEmptyMenu || gate.visible

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 桌面背景：有自定义壁纸就 **ContentScale.Crop 手工铺满**（等比放大到填满 + 居中裁切，
            // 不变形 ✓）；没有（load 返回 null）就纯黑（默认风格）✓。
            // 壁纸画在**最外层 drawBehind**：上拉"滑出"动画只移动内容、背景不动 ✓（层次正确）。
            .drawBehind {
                drawRect(SportsCamBlack)
                val bmp = wallpaperBitmap ?: return@drawBehind
                if (bmp.width <= 0 || bmp.height <= 0) return@drawBehind
                val scale = max(size.width / bmp.width, size.height / bmp.height)
                val w = bmp.width * scale
                val h = bmp.height * scale
                drawImage(
                    image = bmp,
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        ((size.width - w) / 2f).roundToInt(),
                        ((size.height - h) / 2f).roundToInt()
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(w.roundToInt(), h.roundToInt())
                )
            }
            // ★ ★ 拖动的"指针生命周期"由**根节点**统一负责（而不是图标自己的手势）：
            //   ① 自动翻页会把**原页**连同图标那个手势节点一起销毁 → 手势收到 cancel；
            //      若把 cancel 当成"松手"，就会在手指还没抬起时提交落点 ✗ ——
            //      这正是用户实测的"应用一移动到下一界面就立刻被放下/换位"✗。
            //   ② 松手事件必须先于"清指针"被 handleDrop 读到，否则同一页拖动永远拿不到落点
            //      → 表现为"怎么拖都回弹"✗（用户实测第二条）。
            //   所以：根节点锁定一根手指 → 持续更新 dragPointer / pointerState →
            //   这根手指一抬起就**在这里**提交（根节点不会被"翻页销毁某页"影响 ✓）。
            //   图标自己的 onDragEnd 只作兜底（重复调用是幂等的 ✓），onDragCancel 一律**不提交** ✓。
            .pointerInput(Unit) {
                // 第二指翻页的最小水平位移（第十一轮第 4 项）：40dp
                val secondFingerMinPx = 40.dp.toPx()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // 刚进入拖动：把当前按下的那根手指锁定为"拖动手指"
                        if (pointerState.dragId == Long.MIN_VALUE && dragging != null) {
                            event.changes.firstOrNull { it.pressed }?.let {
                                pointerState.dragId = it.id.value
                            }
                        }
                        if (pointerState.dragId == Long.MIN_VALUE) {
                            // 不在拖动：只维护 pointerState（上拉手势 / 边缘自动翻页要读它）
                            val c = event.changes.firstOrNull()
                            if (c != null && c.pressed) {
                                pointerState.x = c.position.x
                                pointerState.y = c.position.y
                                pointerState.valid = true
                            } else {
                                pointerState.valid = false
                            }
                            if (dragPointer != null) dragPointer = null
                            continue
                        }
                        // 拖动中：只跟锁定那根手指
                        val change = event.changes.firstOrNull { it.id.value == pointerState.dragId }
                        if (change == null) continue
                        pointerState.x = change.position.x
                        pointerState.y = change.position.y
                        pointerState.valid = change.pressed
                        // ★ 第十一轮第 4 项：拖拽中**第二根手指水平滑动 = 翻页**（不打断拖拽 ✓）。
                        //   判据：|dx| >= 40dp 且 |dx| > 2|dy|（防误触）；一次按下只翻一页（secondTriggered）。
                        val second = event.changes.firstOrNull {
                            it.pressed && it.id.value != pointerState.dragId
                        }
                        if (second != null) {
                            if (pointerState.secondId != second.id.value) {
                                pointerState.secondId = second.id.value
                                pointerState.secondStartX = second.position.x
                                pointerState.secondStartY = second.position.y
                                pointerState.secondTriggered = false
                            } else if (!pointerState.secondTriggered) {
                                val sdx = second.position.x - pointerState.secondStartX
                                val sdy = second.position.y - pointerState.secondStartY
                                if (abs(sdx) >= secondFingerMinPx && abs(sdx) > 2f * abs(sdy)) {
                                    pointerState.secondTriggered = true
                                    // 右滑 → 上一页；左滑 → 下一页
                                    val delta = if (sdx > 0f) -1 else 1
                                    scope.launch { turnPageBy(delta) }
                                }
                            }
                        } else {
                            pointerState.secondId = Long.MIN_VALUE
                            pointerState.secondTriggered = false
                        }
                        if (change.pressed) {
                            dragPointer = change.position
                        } else {
                            // ★ 手指抬起：用"抬起那一刻的位置"提交一次，然后清指针
                            dragPointer = change.position
                            pointerState.dragId = Long.MIN_VALUE
                            handleDrop()
                            dragPointer = null
                        }
                    }
                }
            }
            // 上拉 → 总应用列表：**屏幕任意位置**快速上划即可（最下缘 24dp 内不认起手，
            // 否则会被 vivo 的系统手势抢走并直接退出启动器，见 PULL_BOTTOM_EXCLUSION_DP）
            .pointerInput(swipeLocked) {
                // 手势块（重）启动时先清掉上一次的跟手残留：
                // 万一上一次手势是被 key 变化打断的（没走到 onRelease），
                // 这里保证不会在桌面上留下一块"冻住的位移"。
                pullActive = false
                pullPx = 0f
                if (swipeLocked) return@pointerInput
                detectPullUpToAppList(
                    bottomExclusionPx = PULL_BOTTOM_EXCLUSION_DP.dp.toPx(),
                    distanceThresholdPx = PULL_DISTANCE_THRESHOLD_DP.dp.toPx(),
                    flickMinPx = PULL_FLICK_MIN_DP.dp.toPx(),
                    followMinPx = PULL_FOLLOW_MIN_DP.dp.toPx(),
                    velocityThresholdPxPerSec = PULL_VELOCITY_THRESHOLD,
                    followMaxFraction = PULL_FOLLOW_MAX_FRACTION,
                    // ③ 起手点落在小组件上 → 让位（没有别的入口能"让小组件先处理"）
                    yieldAt = { pos -> isDownOnWidget(pos) },
                    onPull = { px ->
                        // 手指按住期间：直接 1:1 跟随（不做动画，"跟手"才不隔一层橡皮）
                        pullActive = true
                        pullPx = px
                    },
                    onRelease = { committed, at, fullSlidePx ->
                        pullActive = false
                        // ★ `at` = 手势期间**真正应用过**的跟手位移（没跟手就是 0，见 appliedPullPx 注释）。
                        //   没跟手又没提交 → **连 snapTo/animateTo 都不发**：这条路上一个动画帧都不会产生，
                        //   桌面绝对静止（连 1px 都没有）✓。
                        //   （上一轮的 bug 正是无条件 `snapTo(松手位移)`：让从未显示过的位移在松手瞬间
                        //    被"显示"出来 = 用户看到的"跳一下"✗）
                        if (committed || at > 0f) {
                            scope.launch {
                                // 先从**当前实际位移**接着动画（保证连续），不会从 0 突然跳一下
                                pullReleaseAnim.snapTo(at)
                                if (committed) {
                                    // 过阈值：桌面平滑滑出整屏（180ms），到位后再切总应用列表
                                    pullReleaseAnim.animateTo(fullSlidePx, tween(PULL_ANIM_MS))
                                    onOpenAppList()
                                } else {
                                    // 跟手过但没提交：平滑回弹到原位
                                    pullReleaseAnim.animateTo(0f, tween(PULL_ANIM_MS))
                                }
                            }
                        }
                    }
                )
            }
            // ★ 跟手/滑出只改**绘制**，所以放在手势节点内侧：
            //   放在外侧的话命中测试会跟着位移走，手势坐标会越滑越偏。
            .graphicsLayer {
                val pulled = if (pullActive) pullPx else pullReleaseAnim.value
                translationY = -pulled
                val maxPull = size.height * PULL_FOLLOW_MAX_FRACTION
                val p = if (maxPull > 0f) (pulled / maxPull).coerceIn(0f, 1f) else 0f
                // 轻微淡出 + 缩小，做出"整块被拉走"的层次（不花哨，副屏上也不掉帧）
                alpha = 1f - 0.45f * p
                scaleX = 1f - 0.06f * p
                scaleY = 1f - 0.06f * p
            }
    ) {
        // ---------------- 顶栏 ----------------
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ★ 运动相机模式关闭时**不渲染**"退出/返回"按钮（不是置灰 —— 用户实测"点了也没用"）：
            //   那种模式下启动器一进来就是桌面（MainActivity 里导航栈初始 = [SquareHome]），
            //   桌面本身就是"家"，栈里只有一页 → `onBack` 的 pop() 什么也不会发生。
            //   写法和右上角刷新按钮一致：组合期读设置 + 整块 if。
            //   ⚠ 它后面的 6dp 间距也一起收进分支，避免少一个按钮后标题顶着左边框。
            if (cameraModeOn) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "返回",
                        tint = Color.White, modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            Text("桌面", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "第 ${pagerState.currentPage + 1}/$pages 页 · $cols×$rows" +
                    if (editMode) " · 编辑中" else "",
                color = if (editMode) TorchAmber else Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp
            )
            Spacer(Modifier.weight(1f))
            if (editMode) {
                Text(
                    text = "双指捏合可进入编辑",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 9.sp
                )
                Spacer(Modifier.width(6.dp))
            }
            // ---------------- 右上角：手动刷新（**仅悬浮窗模式**） ----------------
            // 为什么需要它：vivo 对"存活较久"的 2038 悬浮窗会周期性拦截它发起的启动请求
            // （弹出「翻开手机以继续」）。用户实测：光**重挂悬浮窗**有时还不够 ✗ ——
            // 说明拦截还与"界面驻留/前台状态"有关，所以刷新键现在做**两步**：
            //   ① 重挂悬浮窗（CoverKeeperService.recreateOverlay）
            //   ② 稍等，再把**启动器自身**重新投到副屏（CoverAppLauncher.launchCoverHomeToCover）
            // 详见 [OverlayRefreshBridge]（那里解释了为什么不能用界面的 rememberCoroutineScope）。
            //
            // ★ 非悬浮窗模式**不显示这个按钮**：那时根本没有 2038 悬浮窗可重挂，
            //   recreateOverlay 是空动作，用户只会看到一个"点了没用、还提示'已重挂悬浮窗'"的假按钮。
            //   按钮整个不出现 → 那两句提示自然也不可能被触发（不留"按钮在、提示说重挂"的半状态）。
            if (overlayModeOn) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { OverlayRefreshBridge.recreateThenReplaceSelf(context) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Refresh, "重挂悬浮窗并重投启动器",
                        tint = Color.White, modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ---------------- 分页（编辑模式**不再**收缩、两侧也不再放按钮，见下） ----------------
        HorizontalPager(
            state = pagerState,
            // ★ 第十一轮第 4 项：一根手指正在拖拽时，分页器自带的触摸滚动关掉 ——
            //   此刻翻页统一由"第二根手指水平滑动"检测来驱动（见根节点指针观察者），
            //   否则第二指会被 pager 原生拖动 + 我们的检测各翻一次 ⇒ 一次滑两页 ✗。
            userScrollEnabled = dragging == null,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            // ★ 编辑模式两侧的「添加应用 / 添加小组件」按钮**整块删掉**（用户第 5 项）：
            //   · 它们会在编辑时把桌面挤窄（网格列宽跟着变 → 图标跳位 ✗），现在页面在横竖屏、
            //     编辑/非编辑下宽度完全一致；
            //   · 添加入口仍然在：**编辑模式下点空位的「+」** → 弹出菜单「应用（在总列表里长按选择）/
            //     小组件」（见 DesktopPage 的 EmptyPlus → showEmptyMenu → CompactMenuDialog），
            //     与原来的两个侧边按钮是同一批动作，所以没有功能损失。
            DesktopPage(
                page = page,
                items = items,
                cols = cols,
                rows = rows,
                editProgress = editProgress,
                dragging = dragging,
                dragOffset = dragOffset,
                // 落点高亮 / 跨页浮动副本：只给"当前显示的那一页"（指针就在这一页上）
                dragTargetCol = if (page == pagerState.currentPage) dragCell?.first else null,
                dragTargetRow = if (page == pagerState.currentPage) dragCell?.second else null,
                dragFromOtherPage = dragging != null && dragging?.page != page,
                onPageOrigin = { origin ->
                    if (page == pagerState.currentPage) pageOrigin = origin
                },
                // 内置功能项 + 私密应用隐藏
                cameraModeOn = cameraModeOn,
                hiddenPackages = hiddenPackages,
                onToggleCameraMode = { if (!editMode) toggleCameraMode() },
                onOpenPrivateApps = { if (!editMode) onOpenPrivateApps() },
                onOpenFolder = { id -> if (!editMode) onOpenFolder(id) },
                onOpenTrash = { if (!editMode) onOpenTrash() },
                folderHoverId = hoverFolder?.folderId,
                folderSwapMode = folderSwapMode,
                onRenameFolder = { f -> renameFolderId = f.folderId; renameDraft = f.name },
                iconMap = iconMap,
                labelMap = labelMap,
                onCellMetrics = { w, h -> cellWpx = w; cellHpx = h },
                onAppClick = { pkg ->
                    if (!editMode && !CoverAppLaunchBridge.launchToCover(context, pkg)) {
                        showToast(context, "无法打开：$pkg")
                    }
                },
                onRemove = { item ->
                    // ★ 文件夹：删除时**把里面的应用放回桌面** ✓（不是连数据一起删 ✗）
                    if (item is DesktopItem.Folder) {
                        DesktopStore.removeFolderAndRestoreApps(context, isPortrait, item.folderId)
                        com.dsh.sportscam.cover.CoverDebugLog.log(
                            "Desktop", "删除文件夹 ${item.name}(id=${item.folderId}) → 应用已放回桌面"
                        )
                    } else {
                        DesktopStore.remove(context, isPortrait, item)
                    }
                    if (item is DesktopItem.Widget) WidgetHost.delete(context, item.appWidgetId)
                },
                onDragStart = { item ->
                    // 长按 = 进入编辑模式 + 立即拖动（与"移动图标"合并）。
                    //
                    // ★ 密码拦截：设了密码时**先要密码、再进编辑模式**。
                    //   关键细节：需要密码这条路上**绝不能设 dragging** ——
                    //   此时手指已经抬起（长按后没有继续拖），若把 dragging 设成 item，
                    //   就留下一个永远不会收到 onDragEnd 的"幽灵拖拽"，
                    //   图标会一直跟着残留的 dragOffset 漂着、且 handleDrop 再也不执行。
                    //   所以只弹密码层，等验证通过后由 enterEditMode 的 action 设 editMode。
                    if (editMode || !PasswordGate.needsPassword(context)) {
                        // 没启用密码（或已经在编辑模式里）：与原来的行为一字不差
                        editMode = true
                        dragging = item
                        dragOffset = Offset.Zero
                        // ② 启动"边缘自动翻页"看门狗（松手/取消时在 handleDrop 里取消）
                        edgePageJob?.cancel()
                        edgePageJob = scope.launch { watchEdgePage() }
                    } else {
                        enterEditMode()
                    }
                },
                onDrag = { delta -> dragOffset += delta },
                // 兜底：正常松手时根节点的指针观察者已经提交过（这里会因 dragging==null 静默返回）；
                // 万一观察者没拿到（极端情况）这里还能补一次 ✓
                onDragEnd = { handleDrop() },
                // ★ 手势"取消"**不提交**：自动翻页会把原页连同图标手势节点一起销毁 → 收到 cancel；
                //   把它当松手就会在手指还没抬起时提交落点 ✗（用户实测的"一翻页就被放下/换位"）。
                //   拖动的生命周期已改由根节点的指针观察者接管 ✓，所以这里什么都不做 ✓。
                onDragCancel = { },
                onEmptyCellClick = { showEmptyMenu = true },
                // 双指捏合进编辑模式：同样要过密码（否则指纹捏合就能绕过）
                onPinchIn = { enterEditMode() },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ---------------- 底部：页码 / 编辑工具条 ----------------
        if (editMode) {
            Row(
                modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditButton("+ 增加新页面") {
                    DesktopStore.setStoredPages(context, pages + 1)
                    scope.launch { pagerState.scrollToPage(pages) }
                }
                Spacer(Modifier.width(8.dp))
                EditButton("删除本页", warn = true) {
                    DesktopStore.removePage(context, isPortrait, pagerState.currentPage)
                }
                Spacer(Modifier.weight(1f))
                EditButton("完成") {
                    dragging = null
                    editMode = false
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pages) { i ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (i == pagerState.currentPage) 6.dp else 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                // 页码指示跟随「风格（强调色）」
                                if (i == pagerState.currentPage) Accent.current
                                else Color.White.copy(alpha = 0.25f)
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
    }

    // ---------------- 编辑模式：点 + 号空格 ----------------
    if (showEmptyMenu) {
        // ★ 用同树浮层（CompactMenuDialog），**不用 AlertDialog**：
        //   Dialog/AlertDialog 会新建 TYPE_APPLICATION(2) 窗口，而悬浮窗的窗口上下文是
        //   TYPE_APPLICATION_OVERLAY(2038)，系统直接抛窗口类型不匹配 → 崩进程 →
        //   表现就是"点一下界面整个消失、回到 vivo 桌面"（用户实测反馈）。
        CompactMenuDialog(
            title = "在此处添加",
            actions = listOf(
                "应用（在总列表里长按选择）" to {
                    showEmptyMenu = false; onRequestAddApps()
                },
                "小组件" to {
                    showEmptyMenu = false; showWidgetPicker = true
                },
                // 第十轮第 5 项：新建文件夹（1×1，放好后可在编辑模式里改名/拖应用进去）
                "文件夹" to {
                    showEmptyMenu = false
                    val newId = DesktopStore.addFolder(context, isPortrait)
                    if (newId == null) {
                        showToast(context, "桌面放不下新文件夹")
                    } else {
                        com.dsh.sportscam.cover.CoverDebugLog.log("Desktop", "新建文件夹 id=")
                    }
                }
            ),
            onDismiss = { showEmptyMenu = false }
        )
    }

    // ---------------- 小组件选择器（全屏：真实预览图 + 名称 + 占格） ----------------
    if (showWidgetPicker) {
        WidgetPickerFullScreen(
            cols = cols,
            rows = rows,
            onDismiss = { showWidgetPicker = false },
            onPicked = { provider, id, spanCols, spanRows ->
                val ok = DesktopStore.addWidget(
                    context, id, provider.provider.packageName,
                    isPortrait, cols, rows, spanCols, spanRows
                )
                com.dsh.sportscam.cover.CoverDebugLog.log(
                    "Desktop",
                    "onPicked widget id=$id provider=${provider.provider.packageName} " +
                        "span=${spanCols}x$spanRows page=${cols}x$rows addWidget=$ok " +
                        "items=${DesktopStore.items(context, isPortrait).size}"
                )
                if (!ok) {
                    WidgetHost.delete(context, id)
                    showToast(context, "桌面放不下 ${spanCols}×$spanRows 的小组件")
                }
                showWidgetPicker = false
            }
        )
    }

    // ---------------- 密码浮层宿主（放在本界面最上层） ----------------
    // 必须画在所有内容之后，否则会被桌面/选择器盖住；
    // 它自己在没拦截时是完全无副作用的空组合（见 PasswordGateHost 实现）。
    PasswordGateHost(gate)

    // ---------------- 文件夹重命名浮层（第十轮第 5 项，同树浮层 ✓ 不用系统 Dialog） ----------------
    renameFolderId?.let { fid ->
        FolderRenameOverlay(
            initial = renameDraft,
            onCancel = { renameFolderId = null },
            onConfirm = { newName ->
                DesktopStore.renameFolder(context, fid, newName)
                com.dsh.sportscam.cover.CoverDebugLog.log(
                    "Desktop", "文件夹重命名 id=$fid → \"$newName\""
                )
                renameFolderId = null
            }
        )
    }
}

@Composable
private fun MenuText(text: String, onClick: () -> Unit) {
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

/** 编辑模式的按钮 */
@Composable
private fun EditButton(text: String, warn: Boolean = false, onClick: () -> Unit) {
    Text(
        text = text,
        // 警示按钮跟随「风格（强调色）」，不再是写死的浅红
        color = if (warn) Accent.current else Color.White.copy(alpha = 0.9f),
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

/**
 * 一页桌面：按显式坐标绝对定位每一项（小组件要跨格，嵌套行列做不干净）。
 * 编辑模式下额外画所有空位的 + 号。
 */
@Composable
private fun DesktopPage(
    page: Int,
    items: List<DesktopItem>,
    cols: Int,
    rows: Int,
    editProgress: Float,
    dragging: DesktopItem?,
    dragOffset: Offset,
    /** 拖动落点（页内格子坐标，仅"当前显示页"非 null，由外层用指针位置算出） */
    dragTargetCol: Int?,
    dragTargetRow: Int?,
    /** 被拖的项来自**别的页**（跨屏拖动）→ 在本页落点处补一个"拎在手上"的副本 */
    dragFromOtherPage: Boolean,
    /** 上报本页网格区左上角在根坐标系里的位置（外层用来把根坐标指针换成页内坐标） */
    onPageOrigin: (Offset) -> Unit,
    /** 运动相机模式当前状态（桌面上的开关项要显示它） */
    cameraModeOn: Boolean,
    /** 要隐藏的包名（私密应用）：这些 App 项**不画、不可点**，但位置仍被占住（不跳位、不被覆盖） */
    hiddenPackages: Set<String>,
    onToggleCameraMode: () -> Unit,
    onOpenPrivateApps: () -> Unit,
    onOpenFolder: (Long) -> Unit,
    /** 点击「最近删除」入口（1×1 垃圾桶项）→ 上层路由 */
    onOpenTrash: () -> Unit,
    /** 拖应用悬停在哪个文件夹上（仅 1 个）→ 画"加入/交换"提示；null = 没有悬停 */
    folderHoverId: Long?,
    folderSwapMode: Boolean,
    onRenameFolder: (DesktopItem.Folder) -> Unit,
    iconMap: Map<String, ImageBitmap?>,
    labelMap: Map<String, String>,
    onCellMetrics: (Float, Float) -> Unit,
    onAppClick: (String) -> Unit,
    onRemove: (DesktopItem) -> Unit,
    onDragStart: (DesktopItem) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    /**
     * 拖动手势被"取消"（不是松手）。**绝不能**在这里提交落点：
     * 自动翻页会把原页连同图标的手势节点一起销毁 → 必然收到 cancel，
     * 提交就变成"手指还没抬起就放下/换位"✗。拖动的生命周期由根节点接管。
     */
    onDragCancel: () -> Unit,
    onEmptyCellClick: () -> Unit,
    onPinchIn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .pointerInput(Unit) { detectPinchIn(onPinchIn) }
            // 上报"本页网格区左上角在根坐标系里的位置"：
            // 指针是根坐标系的，落点/边缘判定要用它换成本页的页内坐标（页内 = 指针 − 原点）。
            // ⚠ `positionInRoot` 是 ui.layout 包里的**扩展函数**（不是 LayoutCoordinates 的成员），
            //   必须 `import androidx.compose.ui.layout.positionInRoot` 并带括号调用。
            .onGloballyPositioned { coords -> onPageOrigin(coords.positionInRoot()) }
    ) {
        // ★ 编辑模式**不再整体缩放**（原来 `1f - 0.04f*editProgress`，是为了给两侧的
        //   「添加应用/添加小组件」边框按钮让出空间）。现在那两个按钮已删除（用户第 5 项），
        //   再缩放只会让"编辑模式一进来图标就整体缩一下/跳一下" ✗ —— 网格列宽也因此
        //   在编辑前后**完全一致** ✓。落点换算（cellWpx/cellHpx）也跟着变成真实的格子像素。
        val cellW = maxWidth / cols
        val cellH = maxHeight / rows

        LaunchedEffect(cellW, cellH) {
            onCellMetrics(
                with(density) { cellW.toPx() },
                with(density) { cellH.toPx() }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val pageItems = items.filter { it.page == page }
            val occupied = DesktopLayout.occupiedCells(items, page)

            // 空位 + 号（仅编辑模式）
            if (editProgress > 0.5f) {
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        if ("$c,$r" !in occupied) {
                            Box(
                                modifier = Modifier
                                    .offset(x = cellW * c, y = cellH * r)
                                    .size(cellW, cellH)
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                EmptyPlus(onClick = onEmptyCellClick)
                            }
                        }
                    }
                }
            }

            // 拖动落点高亮 +（跨屏拖动时）被拖项的"浮动副本"。
            // ★ 落点由外层用**指针位置**算好传进来（cellAtPointer，唯一真源），
            //   所以"高亮在哪、松手就落在哪"，跨页后也照样指向新页的格子 ✓。
            // 取成局部 val：`dragging` 是委托属性，用局部变量才能对 `when` 里的子类型做智能转换。
            val draggedItem = dragging
            if (dragTargetCol != null && dragTargetRow != null && draggedItem != null) {
                val shape = RoundedCornerShape(12.dp)
                Box(
                    modifier = Modifier
                        .offset(x = cellW * dragTargetCol, y = cellH * dragTargetRow)
                        .size(cellW * draggedItem.colSpan, cellH * draggedItem.rowSpan)
                        .padding(4.dp)
                        .clip(shape)
                        .border(1.5.dp, Accent.current, shape)
                        .background(Accent.current.copy(alpha = 0.10f))
                ) {
                    // 跨屏拖动：被拖的项还在**上一页**（随那页滑走了，看不见），
                    // 这里在落点处补一个半透明副本，免得用户"手里什么都没有"。
                    if (dragFromOtherPage) {
                        Box(
                            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.85f },
                            contentAlignment = Alignment.Center
                        ) {
                            when (draggedItem) {
                                is DesktopItem.App -> {
                                    val icon = iconMap[draggedItem.packageName]
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (icon != null) {
                                            Image(
                                                bitmap = icon,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(minOf(cellW, cellH) * 0.5f)
                                                    .clip(RoundedCornerShape(12.dp))
                                            )
                                        }
                                        Text(
                                            text = labelMap[draggedItem.packageName] ?: "",
                                            color = Color.White.copy(alpha = 0.9f),
                                            fontSize = 10.sp, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                is DesktopItem.Widget -> Text(
                                    "小组件", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp
                                )
                                is DesktopItem.CameraModeSwitch -> Text(
                                    "运动相机", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp
                                )
                                is DesktopItem.PrivateAppsEntry -> Icon(
                                    Icons.Filled.Lock, "私密应用",
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(minOf(cellW, cellH) * 0.35f)
                                )
                                is DesktopItem.Folder -> Text(
                                    draggedItem.name, color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                is DesktopItem.TrashEntry -> Icon(
                                    Icons.Filled.Delete, "最近删除",
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(minOf(cellW, cellH) * 0.35f)
                                )
                            }
                        }
                    }
                }
            }

            // 内容（后画 → 命中优先级更高）
            pageItems.forEach { item ->
                // ★ 私密应用：**不画、不可点**，但 `items` 里仍有它 ⇒ occupiedCells 仍然占着这一格
                //   （编辑模式不会在这里冒「+」，别的项也换不进来）✓
                //   于是"隐藏"就等于"这一格留空"——布局不重排、其它图标一像素都不动 ✓。
                if (item is DesktopItem.App && item.packageName in hiddenPackages) return@forEach
                val isDragging = dragging?.key == item.key
                val dx = if (isDragging) with(density) { dragOffset.x.toDp() } else 0.dp
                val dy = if (isDragging) with(density) { dragOffset.y.toDp() } else 0.dp
                Box(
                    modifier = Modifier
                        .offset(x = cellW * item.col + dx, y = cellH * item.row + dy)
                        .size(cellW * item.colSpan, cellH * item.rowSpan)
                        .zIndex(if (isDragging) 2f else 1f),
                    contentAlignment = Alignment.Center
                ) {
                    when (item) {
                        is DesktopItem.App -> AppCellBig(
                            icon = iconMap[item.packageName],
                            label = labelMap[item.packageName] ?: item.packageName,
                            cellW = cellW,
                            cellH = cellH,
                            editMode = editProgress > 0.5f,
                            onClick = { onAppClick(item.packageName) },
                            onRemove = { onRemove(item) },
                            onDragStart = { onDragStart(item) },
                            onDrag = onDrag,
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel
                        )
                        is DesktopItem.Widget -> WidgetCell(
                            appWidgetId = item.appWidgetId,
                            editMode = editProgress > 0.5f,
                            onRemove = { onRemove(item) },
                            onDragStart = { onDragStart(item) },
                            onDrag = onDrag,
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel
                        )
                        // 内置功能项 ①：「运动相机模式」开关（显示 开/关，点击切换）
                        is DesktopItem.CameraModeSwitch -> DesktopTileCell(
                            cellW = cellW,
                            cellH = cellH,
                            editMode = editProgress > 0.5f,
                            title = "运动相机",
                            value = if (cameraModeOn) "开" else "关",
                            valueAccent = cameraModeOn,
                            lockGlyph = false,
                            onClick = onToggleCameraMode,
                            onRemove = { onRemove(item) },
                            onDragStart = { onDragStart(item) },
                            onDrag = onDrag,
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel,
                            dragKey = item.key
                        )
                        // 文件夹（1×1）：**文件夹图标**（Compose 画的，不是"文件夹"三个字 ✓）
                        // + 名字标签（与 AppCellBig 同一样式）+ 数量角标；点击 → onOpenFolder ✓
                        // 拖应用停在上面：<3s → 强调色描边（松手=加入）；≥3s → 放大 +「交换」
                        is DesktopItem.Folder -> FolderCell(
                            name = item.name,
                            appCount = item.apps.size,
                            cellW = cellW,
                            cellH = cellH,
                            editMode = editProgress > 0.5f,
                            swapHover = folderHoverId == item.folderId,
                            swapMode = folderHoverId == item.folderId && folderSwapMode,
                            onClick = { onOpenFolder(item.folderId) },
                            onRemove = { onRemove(item) },
                            onRename = { onRenameFolder(item) },
                            onDragStart = { onDragStart(item) },
                            onDrag = onDrag,
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel
                        )
                        // 「最近删除」入口（1×1 垃圾桶）：点击 → onOpenTrash（**密码门在界面侧** ✓）
                        is DesktopItem.TrashEntry -> TrashEntryCell(
                            cellW = cellW,
                            cellH = cellH,
                            editMode = editProgress > 0.5f,
                            onClick = onOpenTrash,
                            onRemove = { onRemove(item) },
                            onDragStart = { onDragStart(item) },
                            onDrag = onDrag,
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel
                        )
                        // 内置功能项 ②：「私密应用」入口（只有锁形图标、无文字）
                        is DesktopItem.PrivateAppsEntry -> DesktopTileCell(
                            cellW = cellW,
                            cellH = cellH,
                            editMode = editProgress > 0.5f,
                            title = null,
                            value = null,
                            valueAccent = false,
                            lockGlyph = true,
                            onClick = onOpenPrivateApps,
                            onRemove = { onRemove(item) },
                            onDragStart = { onDragStart(item) },
                            onDrag = onDrag,
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel,
                            dragKey = item.key
                        )
                    }
                }
            }
        }
    }
}

/** 空格上的 + 号 */
@Composable
private fun EmptyPlus(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Add, "添加",
            tint = Color.White.copy(alpha = 0.25f),
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 编辑模式常驻的 ×（无动画，直接显示/隐藏）；颜色跟随「风格（强调色）」 */
@Composable
private fun RemoveBadge(visible: Boolean, onRemove: () -> Unit) {
    if (!visible) return
    Box(
        modifier = Modifier
            .padding(3.dp)
            .size(24.dp)
            .clip(RoundedCornerShape(50))
            .background(Accent.current)
            .clickable(onClick = onRemove),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Close, "移除",
            tint = Color.White,
            modifier = Modifier.size(15.dp)
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AppCellBig(
    icon: ImageBitmap?,
    label: String,
    cellW: androidx.compose.ui.unit.Dp,
    cellH: androidx.compose.ui.unit.Dp,
    editMode: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    /** 手势被取消（不是松手）：**不要提交**（拖动的生命周期由根节点接管，见 handleDrop 注释） */
    onDragCancel: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = remember { RoundedCornerShape(16.dp) }
    val iconSize = minOf(cellW, cellH) * 0.62f
    // 编辑模式图标轻微缩小（无动画）
    // 交换模式下手对应放大一点点（"要换位了"的视觉提示 ✓）
    val scale = if (editMode) 0.90f else 1f

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onDragStart
                )
                .pointerInput(label) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, delta -> change.consume(); onDrag(delta) },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragCancel() }
                    )
                }
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = label,
                    modifier = Modifier.size(iconSize).clip(shape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(iconSize)
                        .clip(shape)
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Android, null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(iconSize * 0.5f)
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            RemoveBadge(visible = editMode, onRemove = onRemove)
        }
    }
}

/**
 * 桌面上的**内置功能项**格子：「运动相机模式」开关 / 「私密应用」入口。
 *
 * ★ 与应用、小组件**完全同权**：长按进编辑并立即拖动、编辑模式右上角常驻 ×、
 *   能被换位与删除（数据侧就是 [DesktopItem.CameraModeSwitch] / [DesktopItem.PrivateAppsEntry]）。
 *
 * ★ 它**不是系统 AppWidget**：这是我们自己桌面上的一个普通 Compose 项，
 *   所以不受 RemoteViews 的白名单/矢量图限制（那套坑只影响系统小组件）——
 *   锁形直接用 `Icons.Filled.Lock`（material-icons-core 里的矢量图标）画即可，
 *   不依赖副屏字体里有没有 🔒 emoji、也不会有"矢量整行消失/位图被反色"的问题 ✓。
 *
 * 内容：
 *   · [lockGlyph] = true → 只画锁形图标（私密应用入口，无文字）；
 *   · 否则画 [title]（小字）+ [value]（大字；开=强调色、关=灰）。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DesktopTileCell(
    cellW: androidx.compose.ui.unit.Dp,
    cellH: androidx.compose.ui.unit.Dp,
    editMode: Boolean,
    title: String?,
    value: String?,
    valueAccent: Boolean,
    lockGlyph: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    /** 拖应用正悬停在这格上（文件夹）：画"松手=加入"的强调色描边 */
    swapHover: Boolean = false,
    /** 悬停已满 3 秒 → 进入交换模式：放大 + 显示「交换」 */
    swapMode: Boolean = false,
    /** 非 null 时在编辑模式左上角显示「改名」小按钮（文件夹用） */
    onRename: (() -> Unit)? = null,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    /** 手势被取消（不是松手）：**不要提交**（拖动的生命周期由根节点接管，见 handleDrop 注释） */
    onDragCancel: () -> Unit,
    /** 拖动长按手势的 key（用项自己的 key，避免同页多个功能项共用同一个手势块） */
    dragKey: String
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = remember { RoundedCornerShape(16.dp) }
    // 交换模式下手对应放大一点点（"要换位了"的视觉提示 ✓）
    val scale = (if (editMode) 0.90f else 1f) * (if (swapMode) 1.10f else 1f)

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .padding(4.dp)
                .clip(shape)
                // 功能项给一点底色，和应用图标（无底色）区分开
                .background(Color.White.copy(alpha = 0.07f))
                // 悬停提示：<3s 细描边（松手=加入）；≥3s 更粗的描边（交换）
                .then(
                    if (swapHover) Modifier.border(
                        width = if (swapMode) 2.dp else 1.5.dp,
                        color = Accent.current,
                        shape = shape
                    ) else Modifier
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onDragStart
                )
                .pointerInput(dragKey) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, delta -> change.consume(); onDrag(delta) },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragCancel() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (lockGlyph) {
                Icon(
                    Icons.Filled.Lock, "私密应用",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(minOf(cellW, cellH) * 0.42f)
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (title != null) {
                        Text(
                            text = title,
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 9.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    if (value != null) {
                        Text(
                            text = value,
                            // 开 = 强调色（一眼看出是"已开启"）；关 = 灰
                            color = if (valueAccent) Accent.current else Color.White.copy(alpha = 0.4f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            RemoveBadge(visible = editMode, onRemove = onRemove)
        }
        // 编辑模式左上角「改名」（文件夹用；输入走同树浮层，绝不用系统 Dialog ✗）
        if (editMode && onRename != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2A2A2E))
                    .clickable(onClick = onRename)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text("改名", color = Color.White.copy(alpha = 0.85f), fontSize = 8.sp)
            }
        }
        // 悬停满 3s 时的底部提示：此时松手 = 与文件夹**换位**（不是加入）
        if (swapMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Accent.current)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text("交换", color = Color.White, fontSize = 8.sp)
            }
        }
    }
}

/**
 * 小组件格子。与应用**同权**：长按进入编辑并拖动，编辑模式右上角常驻红色 ×。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WidgetCell(
    appWidgetId: Int,
    editMode: Boolean,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    /** 手势被取消（不是松手）：**不要提交**（拖动的生命周期由根节点接管，见 handleDrop 注释） */
    onDragCancel: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = if (editMode) 0.94f else 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {},
                    onLongClick = onDragStart
                )
                .pointerInput(appWidgetId) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, delta -> change.consume(); onDrag(delta) },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragCancel() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(2.dp),
                factory = { ctx ->
                    val hostView = WidgetHost.createView(ctx, appWidgetId)
                    com.dsh.sportscam.cover.CoverDebugLog.log(
                        "Desktop",
                        "WidgetCell createView id=$appWidgetId -> ${hostView?.javaClass?.simpleName ?: "null"}"
                    )
                    hostView ?: View(ctx).also {
                        it.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                onReset = null
            )
        }
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            RemoveBadge(visible = editMode, onRemove = onRemove)
        }
    }
}

/**
 * 「上拉 → 总应用列表」手势：**屏幕任意位置**快速上划都触发（不再只认底部窄条）。
 *
 * ★ **方向约束（后加，用户实测反馈）**：只认"主方向向上"的手势 ——
 *   要求 `|dy| ≥ [directionRatio] × |dx|`（默认 2 倍）。
 *   换算成角度：tanθ = |dx|/|dy| ≤ 1/2 → 与水平夹角 θ ≤ 26.6°，即**离竖直 63.4° 以内**才算上划。
 *   用户实测"斜向上 30° 的微弱上划也会进列表"✗：30° 时 |dx|/|dy| ≈ 1.73 ≫ 1/2 → 被这条挡掉 ✓。
 *   **位移分支与速度分支都要过这一条**（方向不合格一律不算）。
 *
 * ★ **跟手动画的门槛比提交更严**（用户第三轮反馈："斜着划桌面也会弹一下"✗）：
 *   跟手要 `|dy| ≥ [PULL_FOLLOW_DIRECTION_RATIO]（3）× |dx|`（离竖直 ≤18°）**且**
 *   `|dy| ≥ [PULL_FOLLOW_MIN_DP]（12dp）` 才启动；不满足时**一像素都不动**。
 *   详见 [PULL_FOLLOW_DIRECTION_RATIO] 的注释（根因：手势开头 |dx| 只有 1~2px，
 *   旧的 `|dy|>=2|dx|` 会被偶然满足而"锁存"住跟手 → 斜划也跟手 → 松手回弹 = 弹一下）。
 *   ⚠ 两道门槛互不影响：能不能进列表**仍按**上面的 2 倍 + 60dp / 900px/s，没有变难。
 *
 * 判定（按要求写成"或"，各分支都写进日志便于真机核对）：
 *   · **位移**：松手时相对起手点向上的位移 ≥ [distanceThresholdPx]（60dp）；**或**
 *   · **速度**：向上速度 ≥ [velocityThresholdPxPerSec]（900px/s）**且**位移 ≥ [flickMinPx]
 *     （24dp —— 没有这个下限的话，一次轻点/手指抖动也会被算成"高速上划"）。
 * 排除：
 *   · 起手点落在屏幕**最下缘 [bottomExclusionPx]**（24dp）内 → 直接放弃。用户实测：
 *     从最底部上划会被 vivo 的系统手势抢走并**直接退出启动器**；
 *   · 多指（捏合/换指）→ 放弃，让给 [detectPinchIn]。
 *
 * 为什么不用 `detectVerticalDragGestures`：它会把竖直拖动整体吃进自己的手势循环，
 * 和「长按拖动图标」「双指捏合」抢同一批事件。这里仍在 **Initial 阶段观察、判定前不消费**：
 *   · 轻点 / 长按图标 → 只是旁观，不影响点击与长按拖动；
 *   · 横向滑动 / 斜向滑动 → 方向或位移不合格，不触发（分页器竖直方向本来也不吃事件）。
 * 只有**判定成立的那一刻**才 consume，避免松手时下面的元素又接一次点击。
 *
 * @param onPull 跟手回调（当前向上位移 px，已夹到跟手上限 —— 返回时也会回调，用于回弹）
 * @param onRelease 松手回调：是否提交 + **已经应用过的**跟手位移 px（没跟手时 = 0）+ 整屏高度 px
 * @param yieldAt 起手点判定：返回 true 表示"这一点该让给别人"（= 落在可滑动的小组件上），
 *                此时本手势**直接放弃且不消费任何事件**，让小组件自己处理
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectPullUpToAppList(
    bottomExclusionPx: Float,
    distanceThresholdPx: Float,
    flickMinPx: Float,
    followMinPx: Float,
    velocityThresholdPxPerSec: Float,
    followMaxFraction: Float,
    yieldAt: (Offset) -> Boolean,
    onPull: (Float) -> Unit,
    /** 第三个参数 = 整屏高度 px（滑出动画的目标）；第二个 = **已经应用过的**跟手位移（没跟手时为 0） */
    onRelease: (committed: Boolean, appliedPullPx: Float, fullSlidePx: Float) -> Unit
) {
    awaitEachGesture {
        // 尺寸一律从**手势节点自己**取（PointerInputScope/AwaitPointerEventScope 的 size）：
        // 这样旋转后不需要把尺寸塞进 pointerInput 的 key 里重启手势，也不会有陈旧值。
        val fullSlidePx = size.height.toFloat()
        val maxPullPx = fullSlidePx * followMaxFraction

        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

        // ① 起手点在最下缘的排除带里 → 放弃本次手势（系统手势区，抢不过 vivo）
        if (down.position.y >= size.height - bottomExclusionPx) return@awaitEachGesture

        // ② 起手点落在**小组件**上 → 直接让位（用户第 7 项）：
        //    小组件内部可能是可滑动列表，我们从外面**无法知道**它有没有消费事件 ✗，
        //    所以保守处理：既不启动上拉判定、也**不消费**任何事件，整段手势交给小组件。
        //    空白格 / 图标上照常拉起总应用列表 ✓。
        if (yieldAt(down.position)) {
            com.dsh.sportscam.cover.CoverDebugLog.log(
                "Desktop",
                "上拉手势：起手点落在小组件上 → 让位（未注册上拉，交给小组件）" +
                    "x=${down.position.x.toInt()} y=${down.position.y.toInt()}"
            )
            return@awaitEachGesture
        }

        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)

        /** 手指相对起手点向上的位移（向下拉为负 → 视觉上夹到 0，不跟着往上顶） */
        fun upOf(y: Float): Float = (down.position.y - y).coerceIn(0f, maxPullPx)

        // 跟手只在**门槛真的满足之后**才开始：
        //   ① 方向比 ≤ 1/3（离竖直 ≤18°）**且** ② 竖直位移先累计 ≥ followMinPx（12dp）。
        // 为什么不能只写 `directionOk`（用户第二轮反馈"斜着划也会弹一下"）：
        //   手势最开始那几个事件里 |dx| 往往只有 1~2px（甚至 0），`|dy| >= 2|dx|` 会**偶然成立**
        //   → 跟手被锁存 → 之后一路跟着斜划的竖直分量走 → 松手又不提交 → 原路弹回 ✗。
        //   加上"先走过 12dp"这道门槛后，斜向上 30° 的手势在那一刻 |dx| ≈ 6.9dp、比值 1.73，
        //   永远过不了 ① → **跟手从不启动**。
        var following = false

        // ★ `appliedPullPx` = **真正被应用过**的跟手位移（没跟手时恒为 0）。
        //
        //   它修的是用户第四轮实测"斜划还是跳一下"的**真正根因**：
        //   旧代码把 `upOf(松手位置)` **无条件**交出去（不管跟手有没有启动过），
        //   调用方拿到非 0 值就 `pullReleaseAnim.snapTo(该值)`；而此刻 pullActive=false，
        //   根 Column 的 graphicsLayer 读的正是 `pullReleaseAnim.value`
        //   → **松手瞬间凭空位移一次**，随后 180ms 动画回 0 = "跳一下" ✗。
        //   真机日志实锤（修前）：
        //     `dx=113px dy=100px 跟手=false 提交=false` → 松手时会 snapTo(100px) ✗
        //   现在只交出跟手时实际应用过的值：没跟手 → 交 0 → snapTo(0)+animateTo(0)，
        //   桌面绝对静止（连 1px 都没有）✓。
        var appliedPullPx = 0f

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            // ② 多指 → 放弃（交给双指捏合进编辑模式）；放弃路径同样只交出已应用的位移
            if (event.changes.size > 1) {
                onRelease(false, appliedPullPx, fullSlidePx)
                return@awaitEachGesture
            }
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                onRelease(false, appliedPullPx, fullSlidePx)
                return@awaitEachGesture
            }
            tracker.addPosition(change.uptimeMillis, change.position)

            // 相对起手点的净位移（dy 向上为正）
            val dxTravel = change.position.x - down.position.x
            val dyTravel = down.position.y - change.position.y

            // ① 提交用（较松）：|dy| >= 2|dx|（离竖直 ≤63°）+ 60dp 或 900px/s
            val directionOk = dyTravel > 0f &&
                abs(dyTravel) >= PULL_DIRECTION_RATIO * abs(dxTravel)
            // ② 跟手用（更严）：|dy| >= 3|dx|（离竖直 ≤18°）**且** |dy| 已累计 ≥12dp
            val followDirectionOk = dyTravel > 0f &&
                abs(dyTravel) >= PULL_FOLLOW_DIRECTION_RATIO * abs(dxTravel)
            val followOk = followDirectionOk && dyTravel >= followMinPx

            if (!following && followOk) following = true
            // 跟手：门槛满足后才把桌面拉走对应距离，并**记下这个被真正应用的位移**
            if (following) {
                appliedPullPx = upOf(change.position.y)
                onPull(appliedPullPx)
                // ★ 一旦确认是"上拉"（而不是斜划/横划），就把事件吃掉：
                //   否则横向分页器还会跟着把页面拖走一点再弹回，看起来又是"动了一下"。
                //   只在跟手成立后消费 —— 判定前一律不消费，不影响点击/长按/横划翻页。
                change.consume()
            }

            if (!change.pressed) {
                val velocityY = runCatching { tracker.calculateVelocity().y }.getOrDefault(0f)
                val byDistance = dyTravel >= distanceThresholdPx
                val byVelocity = dyTravel >= flickMinPx && -velocityY >= velocityThresholdPxPerSec
                // ★ 位移分支 / 速度分支都必须过方向判据：方向不合格**一律不算**
                //   （用户实测的"斜向上 30° 微弱上划进列表"就是被这一条挡掉的）
                val committed = directionOk && (byDistance || byVelocity)
                if (committed) {
                    change.consume()
                    event.changes.forEach { it.consume() }
                }
                // 只记录"真的想上划"的手势（轻点也走这里，全记会把日志刷满）。
                // `起手在小组件上=false`：能走到这里就说明起手点**不在**小组件上
                // （在小组件上那条路在 awaitFirstDown 之后就直接让位了，另有一条日志）。
                if (committed || dyTravel >= flickMinPx) {
                    com.dsh.sportscam.cover.CoverDebugLog.log(
                        "Desktop",
                        "上拉手势：dx=${dxTravel.toInt()}px dy=${dyTravel.toInt()}px " +
                            "起手在小组件上=false " +
                            // 跟手门槛（动画）
                            "跟手=$following(需|dy|>=${PULL_FOLLOW_DIRECTION_RATIO.toInt()}|dx| " +
                            "且 |dy|>=${PULL_FOLLOW_MIN_DP.toInt()}dp，实际跟手方向=$followDirectionOk) " +
                            // ★ 交给动画的位移：没跟手时必须是 0；启动动画=false 时承诺"一帧都不动"
                            "交出位移=${appliedPullPx.toInt()}px " +
                            "启动动画=${committed || appliedPullPx > 0f} " +
                            // 提交门槛（能不能进列表）
                            "提交=$committed(需|dy|>=${PULL_DIRECTION_RATIO.toInt()}|dx| 且 " +
                            "(位移>=${distanceThresholdPx.toInt()}px 或 速度>=${velocityThresholdPxPerSec.toInt()}px/s)，" +
                            "实际方向=$directionOk 位移够=$byDistance 速度够=$byVelocity " +
                            "速度=${(-velocityY).toInt()}px/s)"
                    )
                }
                // ★ 交出的是**已经应用过的跟手位移**（没跟手时为 0）：
                //   调用方据此 snapTo 才能保证"显示器上的位移"与"动画起点"连续，
                //   不会在松手瞬间凭空多出一次位移（那就是"斜划也跳一下"）。见 appliedPullPx 注释。
                onRelease(committed, appliedPullPx, fullSlidePx)
                return@awaitEachGesture
            }
        }
    }
}

/**
 * 只识别「双指」的捏合手势；在 Initial 阶段观察且不消费事件，
 * 所以单指左右滑翻页不受影响。
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectPinchIn(
    onPinchIn: () -> Unit
) {
    var lastDistance = 0f
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val changes = event.changes
            if (changes.size >= 2) {
                val d = (changes[0].position - changes[1].position).getDistance()
                if (lastDistance > 0f && d < lastDistance * 0.9f) onPinchIn()
                lastDistance = d
            } else {
                lastDistance = 0f
            }
        }
    }
}

/**
 * 小组件选择器（**全屏**）。
 *
 * 每项展示：小组件自己的**预览图**（API 31+ 用 provider.loadPreviewImage，
 * 拿不到就退化成它的图标）+ 名称 + 占用格数（如 2×2）。
 * 全屏是因为副屏只有 682×422，普通弹窗塞不下有意义的预览。
 */
@Composable
private fun WidgetPickerFullScreen(
    cols: Int,
    rows: Int,
    onDismiss: () -> Unit,
    onPicked: (AppWidgetProviderInfo, Int, Int, Int) -> Unit
) {
    val context = LocalContext.current
    val providers = remember { WidgetHost.providers(context) }
    var pendingProvider by remember { mutableStateOf<AppWidgetProviderInfo?>(null) }
    var pendingId by remember { mutableStateOf(-1) }
    var pendingSpan by remember { mutableStateOf(1 to 1) }

    val bindLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val provider = pendingProvider
        val id = pendingId
        if (provider != null && id >= 0 && result.resultCode == android.app.Activity.RESULT_OK) {
            onPicked(provider, id, pendingSpan.first, pendingSpan.second)
        } else {
            showToast(context, "未授权添加小组件")
            onDismiss()
        }
    }

    // ★ 不用 Dialog（窗口类型冲突会崩，见上面的说明）：在**同一棵树**里铺一层全屏浮层
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101012))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101012))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "选择小组件",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "共 ${providers.size} 个 · 数字是占格",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 10.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "关闭",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            if (providers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "设备上没有可用的小组件",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = providers, key = { it.provider.flattenToString() }) { provider ->
                        val label = runCatching {
                            provider.loadLabel(context.packageManager).toString()
                        }.getOrDefault(provider.provider.flattenToShortString())
                        val preview: ImageBitmap? = remember(provider.provider) {
                            loadWidgetPreview(context, provider)
                        }
                        val span = WidgetSpan.of(provider.minWidth, provider.minHeight, cols, rows)

                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .clickable {
                                    val spanNow = WidgetSpan.of(
                                        provider.minWidth, provider.minHeight, cols, rows
                                    )
                                    com.dsh.sportscam.cover.CoverDebugLog.log(
                                        "Desktop",
                                        "picker 点击 provider=${provider.provider.flattenToShortString()} " +
                                            "minW=${provider.minWidth} minH=${provider.minHeight} " +
                                            "resizeMode=${provider.resizeMode} " +
                                            "span=${spanNow.first}x${spanNow.second} page=${cols}x$rows"
                                    )
                                    val (id, allowed) = WidgetHost.allocateAndBind(context, provider)
                                    com.dsh.sportscam.cover.CoverDebugLog.log(
                                        "Desktop",
                                        "allocateAndBind id=$id allowed=$allowed"
                                    )
                                    if (id < 0) {
                                        showToast(context, "无法分配小组件")
                                        return@clickable
                                    }
                                    if (allowed) {
                                        onPicked(provider, id, spanNow.first, spanNow.second)
                                    } else {
                                        pendingProvider = provider
                                        pendingId = id
                                        pendingSpan = spanNow
                                        bindLauncher.launch(WidgetHost.bindIntent(id, provider))
                                    }
                                }
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.6f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (preview != null) {
                                    Image(
                                        bitmap = preview,
                                        contentDescription = label,
                                        modifier = Modifier.fillMaxSize().padding(4.dp)
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Android, null,
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                // 占格角标
                                Text(
                                    text = "${span.first}×${span.second}",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = label,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 10.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 桌面「最近删除」入口格子（第十二轮第 2 项） ----------------
//
// 1×1：**垃圾桶图标**（Compose 矢量 `Icons.Filled.Delete`，与 FolderCell / Lock 同一套机制，
// 不受副屏字体/emoji 影响 ✓）+ 下方**短标签「回收站」**（不是"最近删除"四个字 ✗）。
// 标签样式与 AppCellBig / FolderCell 完全一致（白 0.92、12sp、单行截断居中）。
// 点击 → onOpenTrash 回调；**密码门不在这边**（"保护最近删除"由界面侧拦截 ✓）。
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TrashEntryCell(
    cellW: androidx.compose.ui.unit.Dp,
    cellH: androidx.compose.ui.unit.Dp,
    editMode: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = remember { RoundedCornerShape(16.dp) }
    val iconSize = minOf(cellW, cellH) * 0.62f
    val scale = if (editMode) 0.90f else 1f

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onDragStart
                )
                .pointerInput("trash_entry") {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, delta -> change.consume(); onDrag(delta) },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragCancel() }
                    )
                }
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.Delete, "最近删除",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(iconSize * 0.92f)
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "回收站",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            RemoveBadge(visible = editMode, onRemove = onRemove)
        }
    }
}

// ---------------- 桌面文件夹格子（第十一轮第 3 项） ----------------
//
// 用户要求：桌面上的文件夹显示**文件夹图标**（不是"文件夹"三个字），名字照常显示在图标下方，
// 样式与其它应用图标一致（复用 AppCellBig 的标签样式：白色 0.92 alpha、12sp、居中截断）；
// 右下角可选小角标显示里面有几个应用（低调）。
//
// 图标来源：`Icons.Filled.Folder` —— 这是**我们自己的 Compose 界面**，不受 REMOTE Views 白名单限制 ✓
//（那个限制只针对系统小组件的 RemoteViews）；material-icons-extended 已是项目依赖 ✓，
// 且它和 `material-icons-core` 的 `Icons.Filled.Lock`（私密应用入口在用）一样是矢量路径，
// 副屏字体里有没有 emoji 完全不影响渲染 ✓。
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderCell(
    name: String,
    appCount: Int,
    cellW: androidx.compose.ui.unit.Dp,
    cellH: androidx.compose.ui.unit.Dp,
    editMode: Boolean,
    swapHover: Boolean,
    swapMode: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = remember { RoundedCornerShape(16.dp) }
    val iconSize = minOf(cellW, cellH) * 0.62f
    // 编辑模式轻微缩小；交换模式（悬停满 3s）放大一点点
    val scale = (if (editMode) 0.90f else 1f) * (if (swapMode) 1.10f else 1f)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onDragStart
                )
                .pointerInput(name) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, delta -> change.consume(); onDrag(delta) },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragCancel() }
                    )
                }
                .then(
                    if (swapHover) Modifier.border(
                        width = if (swapMode) 2.dp else 1.5.dp,
                        color = Accent.current,
                        shape = shape
                    ) else Modifier
                )
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Folder, "文件夹",
                    // 文件夹用强调色：和应用的彩色图标区分开、一眼认得出 ✓
                    tint = Accent.current.copy(alpha = 0.92f),
                    modifier = Modifier.size(iconSize * 0.94f)
                )
                // 可选小角标：里面有几个应用
                if (appCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "$appCount",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 8.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            // 名字：与 AppCellBig 完全同一套样式（白 0.92、12sp、单行截断、居中）
            Text(
                text = name,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            RemoveBadge(visible = editMode, onRemove = onRemove)
        }
        if (editMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2A2A2E))
                    .clickable(onClick = onRename)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text("改名", color = Color.White.copy(alpha = 0.85f), fontSize = 8.sp)
            }
        }
        if (swapMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Accent.current)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text("交换", color = Color.White, fontSize = 8.sp)
            }
        }
    }
}

// ---------------- 文件夹重命名浮层（第十轮第 5 项） ----------------
//
// 为什么是**同树浮层**而不是 Dialog/AlertDialog：悬浮窗的窗口上下文是 TYPE_APPLICATION_OVERLAY(2038)，
// 系统 Dialog 会新建 TYPE_APPLICATION(2) 窗口 → 窗口类型不匹配直接崩进程 ✗（本项目实测踩过）。
// 为什么用 BasicTextField：沿用项目里"分辨率输入"那种自绘输入框的做法（同一个 2038 上下文里可输入）。
// ⚠ 已知限制：悬浮窗（2038，FLAG_NOT_FOCUSABLE）下输入法可能弹不出来 —— 若用户遇到，
//   可先关掉悬浮窗模式再改名，或改用文件夹界面（list-viewer）里的改名入口。
@Composable
private fun FolderRenameOverlay(
    initial: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.66f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(240.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1C1C1E))
                // 吃掉卡片内的点击，避免点到卡片空白就关掉
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("重命名文件夹", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(12) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color.White, fontSize = 13.sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent.current),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) {
                    Text("取消", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onConfirm(text.trim().ifBlank { "文件夹" }) }) {
                    Text("确定", color = Accent.current, fontSize = 12.sp)
                }
            }
        }
    }
}

/** 取小组件预览图：优先官方预览图，退化到图标 */
private fun loadWidgetPreview(
    context: android.content.Context,
    provider: AppWidgetProviderInfo
): ImageBitmap? {
    val densityInt = context.resources.displayMetrics.density.toInt().coerceAtLeast(1)
    val drawable = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            provider.loadPreviewImage(context, densityInt)
        } else {
            null
        }
    }.getOrNull() ?: runCatching { provider.loadIcon(context, densityInt) }.getOrNull()
    return runCatching { drawable?.toBitmap(160, 100)?.asImageBitmap() }.getOrNull()
}
