package com.dsh.sportscam.ui.common

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.sportscam.cover.CoverDebugLog
import com.dsh.sportscam.cover.CoverSettings
import com.dsh.sportscam.ui.theme.Accent
import com.dsh.sportscam.ui.theme.SportsCamSurface
import com.dsh.sportscam.ui.theme.SportsCamSurfaceAlt
import kotlin.math.roundToInt

/**
 * 启动器密码（四位数字）——**同一棵组合树里的浮层**，不是系统 Dialog。
 *
 * ⚠ 为什么绝不能用 `Dialog` / `AlertDialog` / `Toast`：
 *   常驻悬浮窗所在的窗口上下文类型是 `TYPE_APPLICATION_OVERLAY`(2038)，
 *   系统 Dialog 会新建 `TYPE_APPLICATION`(2) 窗口，系统直接抛
 *     `IllegalArgumentException: Window type mismatch. Window Context's window type is 2038,
 *      while LayoutParams' type is set to 2`
 *   并**崩进程** —— 表现就是"点一下界面整个消失、回到 vivo 桌面"（本项目实测踩过）。
 *   所以这里和 [CompactMenuDialog] / [ConfirmOverlay] 一样，画成同树浮层。
 *
 * 用法（三个拦截点都一样）：
 * ```kotlin
 * val gate = rememberPasswordGate()          // ① 在界面顶部创建
 * Button(onClick = { gate.guard(context, PasswordGate.Purpose.Console, "输入密码进入控制台") { push(Screen.Console) } })
 * // ② 在**最外层 Box 内**（所有内容之上）渲染宿主：
 * PasswordGateHost(gate)
 * ```
 * 未启用 / 该用途没勾选保护时 [PasswordGateState.guard] 会直接执行动作，不会弹任何东西。
 */
object PasswordGate {

    /** 默认标题 */
    const val DEFAULT_TITLE: String = "输入启动器密码"

    /** 默认错误提示 */
    const val DEFAULT_ERROR: String = "密码错误，请重试"

    /**
     * 密码**保护范围**的四个入口（第二轮 B1 三个；第十轮新增「最近删除」；都默认关）。
     *
     * 每个入口对应 `CoverSettings` 里的一个独立布尔；总开关「启用启动器密码」关掉时
     * 四者一律不拦（见 [needsPassword]）。
     */
    enum class Purpose {
        /** 控制台（`MainActivity` 里的 `push(Screen.Console)`） */
        Console,

        /** 总应用列表（`push(Screen.AppList)`，含桌面上拉那条路） */
        AppList,

        /** 桌面编辑模式（`SquareHomeScreen` 里进入 editMode 那一刻） */
        DesktopEdit,

        /** 最近删除（`push(Screen.Trash)`，控制台 ④ 页那个入口）—— 第十轮新增 */
        Trash
    }

    /** 密码系统是否"已武装"：总开关开着 + 确实设置过密码。 */
    fun isArmed(context: Context): Boolean =
        CoverSettings.isPasswordEnabled(context) && CoverSettings.hasPassword(context)

    /**
     * 按**用途**判断该入口是否需要密码（第二轮 B1）。
     *
     *  · 总开关关 或 没设置密码（哈希为空）→ 四者**全部** false（语义与其它三个一致 ✓）；
     *  · 否则看该用途自己那个布尔（默认全 false = 默认都不拦）。
     *
     * 只看总开关是不够的：历史设置里可能有"开关 true 但哈希为空"（清过数据），
     * 那种状态下弹密码框只会把用户永久挡在门外。
     */
    fun needsPassword(context: Context, purpose: Purpose): Boolean {
        if (!isArmed(context)) return false
        return when (purpose) {
            Purpose.Console -> CoverSettings.isPasswordGuardConsole(context)
            Purpose.AppList -> CoverSettings.isPasswordGuardAppList(context)
            Purpose.DesktopEdit -> CoverSettings.isPasswordGuardDesktopEdit(context)
            Purpose.Trash -> CoverSettings.isPasswordGuardTrash(context)
        }
    }

    /**
     * ⚠ **兼容重载**（上一轮 `SquareHomeScreen` 已在用的无参形式）：
     * 等价于 [Purpose.DesktopEdit]。新代码请用带 purpose 的那个。
     */
    fun needsPassword(context: Context): Boolean = needsPassword(context, Purpose.DesktopEdit)
}

/**
 * 密码拦截状态机（挂在 Compose 里的一个小控制器）。
 *
 * 为什么不做成 `suspend fun require()`：拦截点都在点击回调里，
 * 用"控制器 + 同树宿主"最直接，也不需要把 `rememberCoroutineScope` 传进每个界面。
 */
class PasswordGateState internal constructor() {

    internal var visible by mutableStateOf(false)
        private set

    internal var title by mutableStateOf(PasswordGate.DEFAULT_TITLE)
        private set

    internal var subtitle by mutableStateOf<String?>(null)
        private set

    internal var errorText by mutableStateOf(PasswordGate.DEFAULT_ERROR)
        private set

    private var pendingAction: (() -> Unit)? = null
    private var pendingCancel: (() -> Unit)? = null

    /**
     * 该入口当前是否需要密码（按用途）。
     *
     * 只看总开关是不够的：历史设置里可能有"开关 true 但哈希为空"（比如清过数据），
     * 那种状态下弹密码框只会把用户永久挡在门外 —— 判断统一在
     * [PasswordGate.needsPassword] 里做。
     */
    fun enabled(context: Context, purpose: PasswordGate.Purpose): Boolean =
        PasswordGate.needsPassword(context, purpose)

    /** ⚠ 兼容重载：等价于 [PasswordGate.Purpose.DesktopEdit]（上一轮的调用形式）。 */
    fun enabled(context: Context): Boolean =
        PasswordGate.needsPassword(context, PasswordGate.Purpose.DesktopEdit)

    /**
     * 拦截一个入口（按用途）。
     *
     *  · 该用途不需要密码 → **直接**执行 [action]（零影响）；
     *  · 需要 → 先弹密码浮层，验证通过才执行 [action]；取消则执行 [onCancel]（可省）。
     *
     * 最后一个是 lambda，所以调用点可以写成：
     * `gate.guard(context, PasswordGate.Purpose.Console, "输入密码进入控制台") { push(...) }`
     */
    fun guard(
        context: Context,
        purpose: PasswordGate.Purpose,
        title: String = PasswordGate.DEFAULT_TITLE,
        subtitle: String? = null,
        errorText: String = PasswordGate.DEFAULT_ERROR,
        onCancel: (() -> Unit)? = null,
        action: () -> Unit
    ) {
        if (!enabled(context, purpose)) {
            action()
            return
        }
        this.title = title
        this.subtitle = subtitle
        this.errorText = errorText
        pendingAction = action
        pendingCancel = onCancel
        visible = true
    }

    /**
     * ⚠ **兼容重载**（上一轮 `SquareHomeScreen` 已在用的形式）：
     * 等价于 `guard(context, Purpose.DesktopEdit, title, ...)`。
     *
     * 新代码请带 purpose —— 否则会绕过"保护范围"的三个开关。
     */
    fun guard(
        context: Context,
        title: String = PasswordGate.DEFAULT_TITLE,
        subtitle: String? = null,
        errorText: String = PasswordGate.DEFAULT_ERROR,
        onCancel: (() -> Unit)? = null,
        action: () -> Unit
    ) = guard(context, PasswordGate.Purpose.DesktopEdit, title, subtitle, errorText, onCancel, action)

    /** 校验（未设置密码一律通过，见 [CoverSettings.verifyPassword]） */
    internal fun verify(context: Context, code: String): Boolean =
        CoverSettings.verifyPassword(context, code)

    /** 取走待执行动作并关闭浮层 */
    internal fun consumeAction(): (() -> Unit)? {
        val action = pendingAction
        pendingAction = null
        pendingCancel = null
        visible = false
        return action
    }

    /** 用户取消 */
    internal fun cancel() {
        val cancel = pendingCancel
        pendingAction = null
        pendingCancel = null
        visible = false
        cancel?.invoke()
    }
}

/** 在界面里创建一份拦截状态（每个界面树一份，不要跨树共享） */
@Composable
fun rememberPasswordGate(): PasswordGateState = remember { PasswordGateState() }

/**
 * 密码浮层宿主。**必须放在最外层 `Box` 内**（它会 fillMaxSize 铺满并盖住下面的内容）。
 *
 * 只在 [state] 处于拦截中时才渲染任何东西；其余时候是完全无副作用的空组合。
 */
@Composable
fun PasswordGateHost(state: PasswordGateState) {
    if (!state.visible) return
    val context = LocalContext.current
    PasswordPadOverlay(
        title = state.title,
        subtitle = state.subtitle,
        errorText = state.errorText,
        onCancel = { state.cancel() },
        onComplete = { code ->
            if (state.verify(context, code)) {
                // 先取出动作再执行：动作里可能改导航栈/设置，顺序上必须先关浮层
                val action = state.consumeAction()
                action?.invoke()
                true
            } else {
                false
            }
        }
    )
}

/**
 * [PasswordGateHost] 的**对接别名**（Lead 指定的名字）。
 *
 * 与宿主完全等价：放在最外层 `Box` 内、只在拦截中才渲染，同树浮层不会撞窗口类型。
 * 外部界面（例如 SquareHomeScreen 的"进入编辑模式"）用哪个名字都行。
 */
@Composable
fun PasswordGateDialog(state: PasswordGateState) = PasswordGateHost(state)

/**
 * **自包含**的一次性密码浮层（不需要控制器/宿主那一对，调用方只需要一个 Boolean）。
 *
 * 适合 SquareHomeScreen 那种"只有一两个入口"的地方：
 * ```kotlin
 * var askEdit by remember { mutableStateOf(false) }
 * // 触发点（第二轮 B1：按用途判断，桌面编辑模式用 Purpose.DesktopEdit）：
 * onPinchIn = {
 *     if (PasswordGate.needsPassword(context, PasswordGate.Purpose.DesktopEdit)) askEdit = true
 *     else editMode = true
 * }
 * // 渲染（放在该界面最外层 Box 内）：
 * if (askEdit) {
 *     PasswordAskOverlay(
 *         title = "输入密码进入编辑模式",
 *         onDismiss = { askEdit = false },
 *         onConfirmed = { askEdit = false; editMode = true }
 *     )
 * }
 * ```
 * ⚠ 它**不会**自己判断"是否需要密码"：一定要先过 [PasswordGate.needsPassword]，
 *   否则该用途没勾保护时也会弹出来。
 */
@Composable
fun PasswordAskOverlay(
    title: String,
    subtitle: String? = null,
    errorText: String = PasswordGate.DEFAULT_ERROR,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit
) {
    val context = LocalContext.current
    PasswordPadOverlay(
        title = title,
        subtitle = subtitle,
        errorText = errorText,
        onCancel = onDismiss,
        onComplete = { code ->
            if (CoverSettings.verifyPassword(context, code)) {
                onConfirmed()
                true
            } else {
                false      // 浮层自己提示错误 + 抖动 + 清空重输
            }
        }
    )
}

/**
 * 「输入 4 位数字」的数字键盘浮层（同树浮层）。
 *
 * 自带：4 个进度圆点、数字键盘、删除键、错误提示与**抖动**反馈。
 * 输入满 4 位即自动提交（调用 [onComplete]）：
 *   · 返回 `true`  → 调用方负责关掉本浮层（或换成下一个浮层）；
 *   · 返回 `false` → 显示错误提示、抖动一次并**清空重输**。
 *
 * 也用于"设置密码"的两次输入（第一次输入 → 换标题再输一次比对）。
 *
 * ⚠ 这是 [NumberPadOverlay] 的**密码专用包装**（显示圆点、不显示数字）。
 *   其它"输入 4 位数字"的场景（例如时间 HHMM）请直接用 [NumberPadOverlay] + `format`。
 */
@Composable
fun PasswordPadOverlay(
    title: String,
    subtitle: String? = null,
    errorText: String = PasswordGate.DEFAULT_ERROR,
    /** 需要几位（默认 4） */
    length: Int = 4,
    onComplete: (String) -> Boolean,
    onCancel: () -> Unit
) = NumberPadOverlay(
    title = title,
    subtitle = subtitle,
    errorText = errorText,
    length = length,
    format = null,      // null = 圆点（密码：不显示数字，避免肩窥）
    onComplete = onComplete,
    onCancel = onCancel
)

/**
 * 通用**数字键盘浮层**（同树浮层）：0-9 + 删除 + 取消，输满 [length] 位自动提交。
 *
 * @param format `null` = 用 [length] 个圆点表示已输入（密码场景）；
 *               否则把"已输入的原始串"映射成显示文本 —— 例如时间输入传
 *               `{ d -> if (d.isEmpty()) "--:--" else if (d.length <= 2) d else d.substring(0,2) + ":" + d.substring(2) }`
 *               就能边输边显示 `07:30` ✓。
 * @param onComplete 返回 `false` → 显示 [errorText] + 抖动 + **清空重输**（范围校验用这个）。
 *
 * 尺寸按副屏最窄的横屏（546×338dp）排：宽 320dp、键高 56dp、数字 20sp（见下方注释）。
 */
@Composable
fun NumberPadOverlay(
    title: String,
    subtitle: String? = null,
    errorText: String = PasswordGate.DEFAULT_ERROR,
    /** 需要几位（默认 4） */
    length: Int = 4,
    /** 已输入内容的呈现方式；null = 圆点（密码） */
    format: ((String) -> String)? = null,
    /** [format] 非空时显示文本的字号（时间那种大字号输入） */
    displayFontSize: androidx.compose.ui.unit.TextUnit = 30.sp,
    onComplete: (String) -> Boolean,
    onCancel: () -> Unit
) {
    // ⚠ 用显式 State 对象（不用 by 委托）：下面的 press() 是嵌套的局部函数，
    //   显式 .value 读写最不容易踩闭包捕获的坑。
    val inputState = remember { mutableStateOf("") }
    val showErrorState = remember { mutableStateOf(false) }
    val failTickState = remember { mutableIntStateOf(0) }
    val input = inputState.value

    val shake = remember { Animatable(0f) }
    LaunchedEffect(failTickState.intValue) {
        if (failTickState.intValue == 0) return@LaunchedEffect
        runCatching {
            shake.snapTo(0f)
            repeat(3) {
                shake.animateTo(1f, tween(55))
                shake.animateTo(-1f, tween(55))
            }
            shake.animateTo(0f, tween(55))
        }
    }
    val shakePx = with(LocalDensity.current) { 5.dp.toPx() }

    /** 按下一个数字键；满 [length] 位就自动提交 */
    fun press(digit: String) {
        if (inputState.value.length >= length) return
        val next = inputState.value + digit
        inputState.value = next
        if (next.length < length) return
        val ok = onComplete(next)
        if (!ok) {
            showErrorState.value = true
            failTickState.intValue++
            inputState.value = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.66f))
            // 点遮罩 = 取消（与 ConfirmOverlay 一致）。密码浮层必须留一条退出路，
            // 否则"没启用密码但误弹"时用户会被卡死在这个界面上。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel
            ),
        contentAlignment = Alignment.Center
    ) {
        // ★ 尺寸（第二轮 B2：用户反馈"键盘太小、按不准"）——
        //   副屏默认 682x422@200dpi ≈ **546x338dp**（最窄的预设约 334dp 高），
        //   所以整体按"宽 320dp、总高 ≈330dp"排：既满足"宽 ≥300dp / 键高 ≥56dp /
        //   数字字号 ≥20sp / 标题 ≥15sp"，又不会在横屏下被裁掉上下两端。
        Column(
            modifier = Modifier
                .offset { IntOffset((shake.value * shakePx).roundToInt(), 0) }
                .width(320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SportsCamSurface)
                // 面板本体吃掉点击，避免穿透到遮罩被误关
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(Modifier.height(3.dp))

            // 状态行：**一行**同时承担"错误提示 / 副标题 / 默认提示"（错误优先）。
            // 合并成一行是为了把省下的高度让给键盘 —— 横屏只有 338dp 可排。
            Text(
                text = if (showErrorState.value) errorText
                else (subtitle ?: "输入 $length 位数字"),
                color = if (showErrorState.value) Accent.current
                else Color.White.copy(alpha = 0.45f),
                fontSize = 11.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(5.dp))

            if (format == null) {
                // 进度圆点（不显示数字本身，避免肩窥）
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(length) { i ->
                        Box(
                            modifier = Modifier
                                .size(15.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i < input.length) Accent.current
                                    else Color.White.copy(alpha = 0.22f)
                                )
                        )
                    }
                }
            } else {
                // 大字号显示已输入内容（时间输入：边输边显示 07:30）
                Text(
                    text = format(input),
                    color = Accent.current,
                    fontSize = displayFontSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(6.dp))

            // 数字键盘：按键用 weight 均分面板宽度（面板固定 320dp，永远放得下）
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
                .forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        row.forEach { key ->
                            KeyButton(key, modifier = Modifier.weight(1f)) { press(key) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 「取消」/「删除」比数字键宽一点，但字号小一档（它们不是数字键）
                KeyButton("取消", modifier = Modifier.weight(1.5f), fontSize = 16.sp, onClick = onCancel)
                KeyButton("0", modifier = Modifier.weight(1f)) { press("0") }
                KeyButton(
                    "删除",
                    modifier = Modifier.weight(1.5f),
                    fontSize = 16.sp
                ) {
                    if (inputState.value.isNotEmpty()) {
                        inputState.value = inputState.value.dropLast(1)
                    }
                }
            }
        }
    }
}

/**
 * 数字键盘上的一颗按键（自绘；不用 Material 按钮）。
 *
 * 尺寸按第二轮 B2 放大：**高 56dp**（手指粗也能按准）、数字字号 20sp。
 * 宽度交给调用方的 `Modifier.weight(…)` 均分，避免不同面板宽度下溢出。
 */
@Composable
private fun KeyButton(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 20.sp,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SportsCamSurfaceAlt)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

// ======================================================================
// 「私密应用」访问门（第七轮）
// ======================================================================

/**
 * **私密应用访问门**（推荐给"私密界面"用的包壳形式）。
 *
 * 规则（用户明确要求）：
 *  · 每次进入私密界面都要**重新输入**启动器密码 —— 不做"一次授权永久有效"
 *    （本函数只在**本次组合**内记 unlocked，界面一销毁、下次进来就是新的组合 ⇒ 重新问 ✓）；
 *  · 没设过密码（或密码已被关闭）→ **不放行**，显示引导（去控制台设密码），
 *    绝不允许"无密码直接进" ✗；
 *  · 一律同树浮层（[PasswordPadOverlay]），绝不是系统 Dialog（悬浮窗 2038 会崩进程）。
 *
 * 用法（list-viewer 的私密界面）：
 * ```kotlin
 * @Composable fun PrivateAppsScreen(onBack: () -> Unit) {
 *     PrivateAccessGate(onExit = onBack) {
 *         // 真正的内容：这里才拿得到"已通过密码"的状态
 *         ...
 *     }
 * }
 * ```
 */
@Composable
fun PrivateAccessGate(
    /** 取消 / 没有密码可验证时的退出动作（一般就是返回上一页） */
    onExit: () -> Unit,
    subtitle: String? = null,
    /**
     * 可选：**没设过密码**时，引导浮层上多一个「去设置密码」按钮，点了就回调这里
     * （调用方一般传"打开控制台 ② 页"或"打开控制台 → 隐私与安全"）✓。
     *
     * 不传也没关系：引导浮层仍会显示"请先到控制台设密码"，只是用户要自己找路。
     */
    onNeedSetup: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // ★ unlocked 刻意只在本次组合内有效：离开私密界面 → 组合被销毁 → 再进来重新问密码 ✓
    var unlocked by remember { mutableStateOf(false) }
    if (unlocked) {
        content()
        return
    }

    if (!CoverSettings.hasPassword(context)) {
        // 没密码可用：只给"返回"（+ 可选的"去设置密码"）引导，**绝不放行**
        PrivateGateBlockedOverlay(onExit = onExit, onGoSetup = onNeedSetup)
        return
    }

    PasswordPadOverlay(
        title = "进入私密应用",
        subtitle = subtitle ?: "每次进入都需要输入启动器密码",
        onCancel = onExit,
        onComplete = { code ->
            if (CoverSettings.verifyPassword(context, code)) {
                CoverDebugLog.log("Privacy", "私密应用：验证通过，放行本次进入")
                unlocked = true
                true
            } else {
                CoverDebugLog.log("Privacy", "私密应用：密码错误（已提示重试）")
                false
            }
        }
    )
}

/**
 * 「私密应用」访问门的**控制器形式**（给"先验证、再执行动作"的入口用，
 * 例如桌面上的锁图标：验证通过才打开私密界面；也用于控制台里"选择私密应用"的管理浮层）。
 *
 * ```kotlin
 * val guard = rememberPrivateAccessGuard()
 * LockIcon(onClick = { guard.guard(context, subtitle = "打开私密应用前请验证密码") { openPrivateScreen() } })
 * // 该界面最外层 Box 内：
 * PrivateAccessGuardHost(guard)
 * ```
 * ⚠ 与 [PrivateAccessGate] 一样：**每次点都要验证**（guard 不缓存"已通过"状态）。
 */
class PrivateAccessGuardState internal constructor() {

    internal var visible by mutableStateOf(false)
        private set

    internal var subtitle by mutableStateOf<String?>(null)
        private set

    /** true = 根本没有可用密码 → 显示引导（不放行） */
    internal var blocked by mutableStateOf(false)
        private set

    private var pendingAction: (() -> Unit)? = null
    private var pendingCancel: (() -> Unit)? = null
    private var pendingNeedSetup: (() -> Unit)? = null

    /**
     * 请求一次私密访问。
     *
     *  · 有密码 → 弹同树密码浮层，验证通过才执行 [action]；
     *  · 没有密码 → **不执行** [action]，显示引导；给了 [onNeedSetup] 就显示「去设置密码」按钮；
     *  · 取消 → 执行 [onCancel]（可省）。
     */
    fun guard(
        context: Context,
        subtitle: String? = null,
        onCancel: (() -> Unit)? = null,
        onNeedSetup: (() -> Unit)? = null,
        action: () -> Unit
    ) {
        this.subtitle = subtitle
        pendingAction = action
        pendingCancel = onCancel
        pendingNeedSetup = onNeedSetup
        blocked = !CoverSettings.hasPassword(context)
        visible = true
    }

    internal fun verify(context: Context, code: String): Boolean =
        CoverSettings.verifyPassword(context, code)

    internal fun consumeAction(): (() -> Unit)? {
        val action = pendingAction
        pendingAction = null
        pendingCancel = null
        pendingNeedSetup = null
        visible = false
        return action
    }

    internal fun cancel() {
        val cancel = pendingCancel
        pendingAction = null
        pendingCancel = null
        pendingNeedSetup = null
        visible = false
        blocked = false
        cancel?.invoke()
    }

    /** 「去设置密码」：关掉本浮层并回调调用方（调用方去开设置密码流程） */
    internal fun goSetup() {
        val setup = pendingNeedSetup
        pendingAction = null
        pendingCancel = null
        pendingNeedSetup = null
        visible = false
        blocked = false
        setup?.invoke()
    }
}

/** 在界面里创建一份私密访问门控制器（每个界面树一份） */
@Composable
fun rememberPrivateAccessGuard(): PrivateAccessGuardState = remember { PrivateAccessGuardState() }

/** 私密访问门的宿主：放在该界面**最外层 Box 内**（它会 fillMaxSize 铺满） */
@Composable
fun PrivateAccessGuardHost(state: PrivateAccessGuardState) {
    if (!state.visible) return
    val context = LocalContext.current
    if (state.blocked) {
        PrivateGateBlockedOverlay(onExit = { state.cancel() }, onGoSetup = { state.goSetup() })
        return
    }
    PasswordPadOverlay(
        title = "进入私密应用",
        subtitle = state.subtitle ?: "每次进入都需要输入启动器密码",
        onCancel = { state.cancel() },
        onComplete = { code ->
            if (state.verify(context, code)) {
                CoverDebugLog.log("Privacy", "私密应用：验证通过")
                val action = state.consumeAction()
                action?.invoke()
                true
            } else {
                CoverDebugLog.log("Privacy", "私密应用：密码错误（已提示重试）")
                false
            }
        }
    )
}

/**
 * 「没有可用密码」时的引导浮层：**只给退出（和可选的"去设置密码"），绝不显示输入键盘**。
 *
 * 为什么必须有它：如果密码总开关被关掉 / 密码被清掉，而私密应用还开着，
 * 没有这道引导就会变成"要么无密码可进 ✗、要么永远进不去" —— 两者都不可接受。
 */
@Composable
private fun PrivateGateBlockedOverlay(
    onExit: () -> Unit,
    onGoSetup: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.66f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onExit
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SportsCamSurface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "私密应用需要启动器密码",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "当前还没有设置启动器密码（或密码已被关闭）。请先到「控制台 → 隐私与安全」" +
                    "设置一个 4 位数字密码，再进入私密应用。",
                color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onExit) {
                    Text("返回", fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                }
                if (onGoSetup != null) {
                    TextButton(onClick = onGoSetup) {
                        Text("去设置密码", fontSize = 14.sp, color = Accent.current)
                    }
                }
            }
        }
    }
}
