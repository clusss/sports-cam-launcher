package com.dsh.sportscam.cover

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** 一块显示器（主要是副屏）的快照信息 */
data class CoverDisplayInfo(
    val displayId: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    /** Surface.ROTATION_0 / 90 / 180 / 270 */
    val rotation: Int,
    val isDefault: Boolean,
    /** 反射取到的 Display flags（拿不到就是 0） */
    val flags: Int
) {
    /** Display.FLAG_PRIVATE 的隐藏常量值；拿不到 flags 时为 false */
    val isPrivate: Boolean get() = flags and 0x00000004 != 0

    /** Display.FLAG_TRUSTED */
    val isTrusted: Boolean get() = flags and 0x00000040 != 0

    val resolution: String get() = "${widthPx}x${heightPx}"
}

/**
 * 副屏发现工具。
 *
 * 反编译结论（《超级小副屏》DisplayUtil）：
 *  - **不要硬编码 displayId = 1**，要运行时枚举；
 *  - 三级兜底：getDisplays() → 暴力探测 1..16 → 反射 getDisplays(category)。
 *
 * 本工程 minSdk 33，Display.Mode / getRealMetrics 都可直接用。
 */
object CoverDisplay {

    private const val TAG = "CoverDisplay"

    /** Display flag 常量（部分在公开 API 里没有，这里用已知数值） */
    private const val FLAG_PRIVATE = 0x00000004
    private const val FLAG_TRUSTED = 0x00000040

    fun allDisplays(context: Context): List<Display> = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.displays.toList()
    }.getOrElse {
        Log.w(TAG, "getDisplays 失败", it)
        emptyList()
    }

    /** 副屏（非默认屏）的 displayId；找不到返回 Display.DEFAULT_DISPLAY(0) */
    fun coverDisplayId(context: Context): Int {
        allDisplays(context)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            ?.let { return it.displayId }

        // 兜底 1：直接探测 1..16（必须从 1 开始，getDisplay(0) 是主屏）
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (dm != null) {
            for (id in 1..16) {
                val d = runCatching { dm.getDisplay(id) }.getOrNull() ?: continue
                if (d.displayId != Display.DEFAULT_DISPLAY) return d.displayId
            }
        }

        // 兜底 2：反射 getDisplays(String category)
        runCatching {
            val m = DisplayManager::class.java.getMethod("getDisplays", String::class.java)
            for (category in listOf(
                "android.hardware.display.category.ALL_INCLUDING_BUT_IMPROVED",
                "android.hardware.display.category.PRESENTATION",
                "android.hardware.display.category.ALL"
            )) {
                val arr = m.invoke(dm, category) as? Array<*> ?: continue
                arr.filterIsInstance<Display>()
                    .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
                    ?.let { return it.displayId }
            }
        }

        return Display.DEFAULT_DISPLAY
    }

    /** 反射读 Display.getFlags()（隐藏 API，失败返回 0） */
    private fun displayFlags(display: Display): Int = runCatching {
        Display::class.java.getMethod("getFlags").invoke(display) as? Int ?: 0
    }.getOrDefault(0)

    fun infoOf(display: Display): CoverDisplayInfo {
        // 物理像素：优先 mode，其次 real metrics
        var width = 0
        var height = 0
        runCatching {
            val mode = display.mode
            width = mode.physicalWidth
            height = mode.physicalHeight
        }
        val metrics = DisplayMetrics()
        runCatching { @Suppress("DEPRECATION") display.getRealMetrics(metrics) }
        if (width <= 0 || height <= 0) {
            width = metrics.widthPixels
            height = metrics.heightPixels
        }
        return CoverDisplayInfo(
            displayId = display.displayId,
            name = runCatching { display.name }.getOrDefault("display-${display.displayId}"),
            widthPx = width,
            heightPx = height,
            densityDpi = if (metrics.densityDpi > 0) metrics.densityDpi else 0,
            rotation = runCatching { display.rotation }.getOrDefault(0),
            isDefault = display.displayId == Display.DEFAULT_DISPLAY,
            flags = displayFlags(display)
        )
    }

    fun info(context: Context, displayId: Int): CoverDisplayInfo? {
        val d = allDisplays(context).firstOrNull { it.displayId == displayId } ?: return null
        return infoOf(d)
    }

    fun allInfo(context: Context): List<CoverDisplayInfo> =
        allDisplays(context).map { infoOf(it) }

    /** 副屏是否属于「public + trusted」（普通 App 用 setLaunchDisplayId 就能上去的那类屏） */
    fun isLaunchableByApp(info: CoverDisplayInfo): Boolean = !info.isPrivate

    /** 强制解析真实尺寸；拿不到就用调用方给的兜底值 */
    fun coverSizeOrNull(context: Context): Point? {
        val id = coverDisplayId(context)
        if (id == Display.DEFAULT_DISPLAY) return null
        val info = info(context, id) ?: return null
        return Point(info.widthPx, info.heightPx)
    }

    /**
     * 副屏此刻是不是竖屏 —— 必须按**跟随旋转的尺寸**判断，不能读 `Configuration`。
     *
     * ⚠ 实测 bug：桌面（SquareHome）原来用 `LocalConfiguration.current.orientation` 判断方向。
     * 本工程的 Activity 声明了 `configChanges="orientation|screenSize|..."`，旋转时**不会重建**，
     * 配置更新相对显示器有滞后 —— 结果是"刚进入桌面时明明横屏，却按竖屏渲染成 2×4"，
     * 要手动转一次屏才恢复。直接问显示器当前尺寸就没有这个问题。
     *
     * ⚠ 另一个坑：[infoOf] 取的是 `display.mode.physicalWidth/Height`，那是**原生 mode 尺寸
     * （682×422，不随旋转变化）**，拿它判断方向会永远返回横屏。所以这里按顺序用：
     *   ① 该显示器的 DisplayContext.resources 尺寸（跟随旋转，就是应用实际看到的）；
     *   ② `getRealMetrics`（同样跟随旋转）；
     *   ③ 最后退回本上下文的实际尺寸。
     */
    @Volatile
    private var lastPortraitDecision: Boolean? = null

    fun isPortraitNow(context: Context): Boolean {
        val decided = decidePortrait(context)
        if (decided != lastPortraitDecision) {
            lastPortraitDecision = decided
            CoverDebugLog.log(
                "Orient",
                "副屏方向判定: ${if (decided) "竖屏" else "横屏"}（${lastPortraitDetail}）"
            )
        }
        return decided
    }

    @Volatile
    private var lastPortraitDetail: String = "-"

    /** 上一次**有效**的方向判定（解析不到副屏时用它，见下面 ③） */
    @Volatile
    private var lastValidPortrait: Boolean? = null

    /**
     * 量出来的尺寸是否**与这块屏自己的物理 mode 相符**（±40px 容差，允许旋转 90°）。
     *
     * 为什么用它，而不是"和主屏尺寸比"：
     *   实测主屏 `getRealMetrics` = 1080×2400，而 `DisplayContext.resources` 给的是
     *   1080×**2177**（差在 insets）→ 两者不相等，按"等于主屏"判断根本拦不住 ✗
     *   （用户复现：解锁瞬间解析到主屏，量出 1080×2177 → 误判竖屏 → 主界面旋转 90°）。
     *   而"这块屏自己的 physical mode"是 682×422，和 1080×2177 差了十万八千里 → 直接拦下 ✓。
     */
    private fun matchesPhysical(display: Display, w: Int, h: Int): Boolean = runCatching {
        val pw = display.mode.physicalWidth
        val ph = display.mode.physicalHeight
        if (pw <= 0 || ph <= 0) return true   // 拿不到 mode 就不拦，避免误伤
        val pMin = minOf(pw, ph)
        val pMax = maxOf(pw, ph)
        val mMin = minOf(w, h)
        val mMax = maxOf(w, h)
        kotlin.math.abs(mMin - pMin) <= 40 && kotlin.math.abs(mMax - pMax) <= 40
    }.getOrDefault(true)

    private fun decidePortrait(context: Context): Boolean {
        // ① 正常路径：解析副屏 displayId → 用它的 DisplayContext 尺寸（跟随旋转）
        val id = runCatching { coverDisplayId(context) }.getOrDefault(Display.DEFAULT_DISPLAY)
        val display = if (id != Display.DEFAULT_DISPLAY) {
            runCatching {
                (context.getSystemService(android.hardware.display.DisplayManager::class.java))
                    ?.getDisplay(id)
            }.getOrNull()
        } else {
            null
        }

        if (display != null) {
            runCatching {
                val m = context.createDisplayContext(display).resources.displayMetrics
                if (!matchesPhysical(display, m.widthPixels, m.heightPixels)) {
                    lastPortraitDetail = "displayCtx=${m.widthPixels}x${m.heightPixels}≠物理mode${display.mode.physicalWidth}x${display.mode.physicalHeight}(拒绝)"
                } else {
                    lastPortraitDetail = "displayCtx=${m.widthPixels}x${m.heightPixels}"
                    val p = m.heightPixels > m.widthPixels
                    lastValidPortrait = p
                    return p
                }
            }
            runCatching {
                val m = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(m)
                if (!matchesPhysical(display, m.widthPixels, m.heightPixels)) {
                    lastPortraitDetail = "real=${m.widthPixels}x${m.heightPixels}(尺寸不符物理 mode，拒绝)"
                } else {
                    lastPortraitDetail = "real=${m.widthPixels}x${m.heightPixels}"
                    val p = m.heightPixels > m.widthPixels
                    lastValidPortrait = p
                    return p
                }
            }
        }

        // ② 兜底：直接探测 1..16（解锁瞬间显示列表可能只回默认屏）
        runCatching {
            val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            for (probe in 1..16) {
                val d = dm?.getDisplay(probe) ?: continue
                if (d.displayId == Display.DEFAULT_DISPLAY) continue
                val m = context.createDisplayContext(d).resources.displayMetrics
                if (m.widthPixels <= 0 || m.heightPixels <= 0) continue
                if (!matchesPhysical(d, m.widthPixels, m.heightPixels)) continue
                lastPortraitDetail = "probe$probe=${m.widthPixels}x${m.heightPixels}"
                val p = m.heightPixels > m.widthPixels
                lastValidPortrait = p
                return p
            }
        }

        // ③ ★★ 绝不用"本上下文"的尺寸来判断方向（这是用户实测复现的那个 bug）：
        //    悬浮窗模式下锁屏再解锁时，CoverHomeActivity 可能已经落在**主屏**上，
        //    用它的尺寸会得到主屏的 1080x2177 → 2177>1080 → 误判"竖屏"
        //    → 主界面整体旋转 90°、变成以短边为底。
        //    解析不到副屏时，宁可用**上一次有效判定**（解锁前后显示器尺寸并不会变）。
        lastValidPortrait?.let {
            lastPortraitDetail = "cache=$it（副屏暂时解析不到）"
            return it
        }

        // ④ 真的一次都没成功过（例如冷启动就解析不到）：只能按本上下文尺寸猜，
        //    并把它记为有效值，供后续兜底。
        val m = context.resources.displayMetrics
        lastPortraitDetail = "local=${m.widthPixels}x${m.heightPixels}"
        val p = m.heightPixels > m.widthPixels
        lastValidPortrait = p
        return p
    }
}

/**
 * 可组合的副屏 displayId：随副屏热插拔 / 旋转自动更新。
 *
 * 对应报告里指出的缺口 #1（displayId 只在 onCreate 读一次）与缺口 #2（没有枚举能力）。
 */
@Composable
fun rememberCoverDisplayId(): State<Int> {
    val context = LocalContext.current
    val state = remember { mutableIntStateOf(CoverDisplay.coverDisplayId(context)) }

    DisposableEffect(context) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                state.intValue = CoverDisplay.coverDisplayId(context)
            }

            override fun onDisplayRemoved(displayId: Int) {
                state.intValue = CoverDisplay.coverDisplayId(context)
            }

            override fun onDisplayChanged(displayId: Int) {
                state.intValue = CoverDisplay.coverDisplayId(context)
            }
        }
        runCatching { dm?.registerDisplayListener(listener, null) }
        state.intValue = CoverDisplay.coverDisplayId(context)
        onDispose { runCatching { dm?.unregisterDisplayListener(listener) } }
    }
    return state
}
