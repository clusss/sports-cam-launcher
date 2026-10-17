package com.dsh.sportscam.desktop

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf

/**
 * 桌面上的一个格子内容。
 *
 * 位置是**显式保存**的（page/col/row），不是"顺序 + 运行时打包"——
 * 因为要支持不同大小的小组件之间互相换位、以及"空间不足就不换"的判定，
 * 必须有确定的坐标才能算得清楚。
 *
 * ⚠ 位置是**分方向**存的（见 [DesktopStore]）：横屏是 4×2、竖屏是 2×4，
 *   同一份坐标在另一套网格里会越界 —— 这正是"竖屏下只剩左上 2×2"那个 bug 的成因。
 */
/**
 * 桌面内置项的 key（「运动相机模式」开关 / 「私密应用」入口）。
 * 这两种项是**我们启动器自己桌面上的功能项**，不是系统 launcher 的 AppWidget ——
 * 所以它们走的就是这套 DesktopItem 数据模型：能被拖动/换位/删除，位置也是显式存的。
 */
const val KEY_ITEM_CAMERA_MODE_SWITCH = "switch:camera_mode"
const val KEY_ITEM_PRIVATE_APPS = "entry:private_apps"

    /** 第十一轮第 2 项：「最近删除」入口项（1×1 垃圾桶）的 key */
    const val KEY_ITEM_TRASH_ENTRY = "entry:trash"

sealed interface DesktopItem {
    val key: String
    val colSpan: Int
    val rowSpan: Int
    val page: Int
    val col: Int
    val row: Int

    data class App(
        val packageName: String,
        override val page: Int = 0,
        override val col: Int = 0,
        override val row: Int = 0
    ) : DesktopItem {
        override val key: String get() = "app:$packageName"
        override val colSpan: Int get() = 1
        override val rowSpan: Int get() = 1
    }

    data class Widget(
        val appWidgetId: Int,
        val provider: String,
        override val colSpan: Int = 1,
        override val rowSpan: Int = 1,
        override val page: Int = 0,
        override val col: Int = 0,
        override val row: Int = 0
    ) : DesktopItem {
        override val key: String get() = "widget:$appWidgetId"
    }

    /**
     * 「运动相机模式」开关项（1×1）。
     *
     * 显示当前模式的开关状态（开/关），点击切换 —— 行为与「控制台」里那个总开关完全一致：
     * 写 `CoverSettings.setCameraModeEnabled` + `syncNavStackHomeToCameraMode`。
     * 与应用/小组件**同权**：能拖动、换位、在编辑模式里用 × 删除。
     */
    data class CameraModeSwitch(
        override val page: Int = 0,
        override val col: Int = 0,
        override val row: Int = 0
    ) : DesktopItem {
        override val key: String get() = KEY_ITEM_CAMERA_MODE_SWITCH
        override val colSpan: Int get() = 1
        override val rowSpan: Int get() = 1
    }

    /**
     * 「私密应用」入口项（1×1，**只有一个锁形图标、没有文字**）。
     *
     * 点击 = 请求打开"私密应用"界面（要密码，界面本身由 list-viewer 提供；
     * 桌面这边只发一个回调，见 SquareHomeScreen 的 onOpenPrivateApps）。
     */
    data class PrivateAppsEntry(
        override val page: Int = 0,
        override val col: Int = 0,
        override val row: Int = 0
    ) : DesktopItem {
        override val key: String get() = KEY_ITEM_PRIVATE_APPS
        override val colSpan: Int get() = 1
        override val rowSpan: Int get() = 1
    }

    /**
     * 「最近删除」入口项（1×1，第十一轮第 2 项）。
     *
     * 桌面上显示的是**垃圾桶图标 + 短标签**（不是"最近删除"四个字）；
     * 点击 → [SquareHomeScreen] 的 `onOpenTrash` 回调（导航/密码门都在界面侧，
     * 桌面这边**不加密码门** ✓ —— 与文件夹、私密应用入口一致）。
     */
    data class TrashEntry(
        override val page: Int = 0,
        override val col: Int = 0,
        override val row: Int = 0
    ) : DesktopItem {
        override val key: String get() = KEY_ITEM_TRASH_ENTRY
        override val colSpan: Int get() = 1
        override val rowSpan: Int get() = 1
    }

    /**
     * 桌面上的**文件夹**项（1×1，第十轮第 5 项）。
     *
     * 与应用/小组件同权：可拖动、可换位、编辑模式可删除 ✓。
     * [apps] 是文件夹里的包名列表 —— **随项一起存档**（`F|id|name|pkg1,pkg2,…`）：
     * 这样任何一次 `save()` 都不会把文件夹内容弄丢 ✓（不会像"只存 id、内容另存"那样漏写）。
     * 文件夹里的应用**不再在桌面单独显示**（加入时桌面上的那个 App 项被移除 ✓，
     * 数据保留在这里；见 [DesktopStore.addAppToFolder] / [DesktopStore.removeFromFolder]）。
     */
    data class Folder(
        val folderId: Long,
        val name: String = "文件夹",
        val apps: List<String> = emptyList(),
        override val page: Int = 0,
        override val col: Int = 0,
        override val row: Int = 0
    ) : DesktopItem {
        override val key: String get() = "folder:$folderId"
        override val colSpan: Int get() = 1
        override val rowSpan: Int get() = 1
    }
}

/** 文件夹的公开信息（给 list-viewer / 控制台的文件夹界面用） */
data class FolderInfo(val id: Long, val name: String, val apps: List<String>) {
    /** 文件夹内部每页放 4 个应用（用户要求：pages = ceil(apps / 4)） */
    val pages: Int get() = DesktopStore.folderPages(apps.size)
}

/**
 * 桌面布局的持久化。
 *
 * 数据分两部分：
 *   1. **有序清单**（`items`）：桌面上有什么、顺序如何。与方向无关。
 *      `A|包名` 或 `W|appWidgetId|provider|cols|rows`
 *   2. **位置表**（`pos_land` / `pos_port`）：每个项在**各自方向**的网格里的
 *      `page,col,row`。横屏 4×2 与竖屏 2×4 的坐标系不同，必须分开存，
 *      否则切方向时会出现"坐标越界、图标跑到屏幕外点不到"。
 *
 * 某个方向缺少位置时（比如刚在横屏加的应用），会在该方向首次使用时
 * 按顺序自动补位并落盘。
 */
object DesktopStore {

    private const val PREFS = "cover_desktop"
    private const val KEY_ITEMS = "items"
    private const val KEY_PAGES = "pages"
    private const val KEY_POS_LAND = "pos_land"
    private const val KEY_POS_PORT = "pos_port"

    /** 第十轮第 6 项：竖屏位置改为"横屏旋转派生"的一次性迁移标记（见 [migrateLayoutMirrorIfNeeded]） */
    private const val KEY_LAYOUT_MIRROR_V1 = "layout_mirror_v1"

    /**
     * 「内置项已预置过」的标记（见 [ensurePresetItems]）。
     *
     * 为什么要它：用户要的是"**装好后桌面上就有**这两个 1×1 项"，
     * 但绝不能每次启动都往桌面上再加一遍 ✗ —— 预置**只做一次**，
     * 之后用户把它们删了也不会自己回来。
     */
    private const val KEY_PRESET_ITEMS = "preset_items_v1"

    /** 每页格子数：横屏 4×2、竖屏 2×4，都是 8 个 */
    const val PER_PAGE = 8

    /** 横向 4 列；竖屏 [gridOf] 里按 2 列算 */
    const val COLS_LANDSCAPE = 4

    private val version = mutableIntStateOf(0)

    fun versionState() = version

    /** 当前方向的网格尺寸 */
    fun gridOf(portrait: Boolean): Pair<Int, Int> =
        if (portrait) 2 to (PER_PAGE / 2) else COLS_LANDSCAPE to (PER_PAGE / COLS_LANDSCAPE)

    private fun posKey(portrait: Boolean) = if (portrait) KEY_POS_PORT else KEY_POS_LAND

    // ---------------- 有序清单（与方向无关） ----------------

    private data class Identity(val type: String, val payload: String, val colSpan: Int, val rowSpan: Int)

    private fun identities(context: Context): List<Identity> =
        prefs(context).getString(KEY_ITEMS, "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val p = line.split('|')
                when {
                    p.size >= 2 && p[0] == "A" -> Identity("A", p[1], 1, 1)
                    p.size >= 5 && p[0] == "W" -> Identity(
                        "W", "${p[1]}|${p[2]}",
                        p[3].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                        p[4].toIntOrNull()?.coerceAtLeast(1) ?: 1
                    )
                    // 兼容更早的格式：W|id|provider（无跨格信息）
                    p.size == 3 && p[0] == "W" -> Identity("W", "${p[1]}|${p[2]}", 1, 1)
                    // 内置功能项：S|switch:camera_mode、P|entry:private_apps（都是 1×1）
                    p.size >= 2 && p[0] == "S" -> Identity("S", p[1], 1, 1)
                    p.size >= 2 && p[0] == "P" -> Identity("P", p[1], 1, 1)
                    // 「最近删除」入口（1×1 垃圾桶）：T|trash
                    p.size >= 2 && p[0] == "T" -> Identity("T", p[1], 1, 1)
                    // 文件夹：F|id|name|pkg1,pkg2,…（名字里若含 | 会被 sanitize 掉，见 writeIdentities）
                    p.size >= 2 && p[0] == "F" -> Identity("F", p.drop(1).joinToString("|"), 1, 1)
                    // 认不出的行**直接跳过**（不抛异常）：这样以后加/减类型都不会把旧存档读崩
                    else -> null
                }
            }
            .toList()

    private fun writeIdentities(context: Context, list: List<DesktopItem>) {
        val text = list.joinToString("\n") { item ->
            when (item) {
                is DesktopItem.App -> "A|${item.packageName}"
                is DesktopItem.Widget ->
                    "W|${item.appWidgetId}|${item.provider}|${item.colSpan}|${item.rowSpan}"
                is DesktopItem.CameraModeSwitch -> "S|camera_mode"
                is DesktopItem.PrivateAppsEntry -> "P|private_apps"
                is DesktopItem.TrashEntry -> "T|trash"
                // 文件夹：名字去掉分隔符（| ; 换行）避免存档被撑破；应用包名不含逗号 ✓
                is DesktopItem.Folder ->
                    "F|${item.folderId}|${item.name.replace('|', ' ').replace(';', ' ').replace('\n', ' ')}" +
                        "|${item.apps.joinToString(",")}"
            }
        }
        prefs(context).edit().putString(KEY_ITEMS, text).apply()
    }

    // ---------------- 位置表 ----------------

    /** key -> "page,col,row" */
    private fun readPositions(context: Context, portrait: Boolean): MutableMap<String, String> {
        val raw = prefs(context).getString(posKey(portrait), "") ?: ""
        val map = mutableMapOf<String, String>()
        raw.split(';').forEach { entry ->
            val i = entry.indexOf('>')
            if (i > 0) map[entry.substring(0, i)] = entry.substring(i + 1)
        }
        return map
    }

    private fun writePositions(context: Context, portrait: Boolean, items: List<DesktopItem>) {
        val text = items.joinToString(";") { "${it.key}>${it.page},${it.col},${it.row}" }
        prefs(context).edit().putString(posKey(portrait), text).apply()
    }

    // ---------------- 读取（含自动补位） ----------------

    /**
     * 读取当前方向的桌面内容。
     *
     * 位置表里没有的项（在另一个方向添加的、或旧版本数据）会按顺序自动补位，
     * 补完立刻落盘 —— 这样切到任一方向都不会有点不到的图标。
     *
     * ★ 第十轮第 6 项：**竖屏布局 = 横屏布局按旋转公式派生**（见 [mirrorLayout]）。
     *   一次性迁移把老的 pos_port 重建为"横屏旋转"的结果，之后每次 save 都会同步另一方向 ✓。
     */
    fun items(context: Context, portrait: Boolean): List<DesktopItem> {
        migrateLayoutMirrorIfNeeded(context)
        return readDirection(context, portrait)
    }

    /**
     * 一次性迁移（第十轮第 6 项）：把竖屏位置重建为"横屏按旋转公式派生"。
     *
     * 为什么需要：老版本里两个方向**各存一套位置**（竖屏那套是当初"按顺序找空位"补的，
     * 和横屏没有任何对应关系）—— 用户看到的"竖屏排版乱"就是这么来的 ✗。
     * 现在竖屏 = 横屏旋转（+ 复杂项溢出重排），所以迁移时重建一次 pos_port 即可 ✓；
     * 横屏布局**原样保留**（它是权威，用户排好的东西一格都不动 ✓）。
     */
    private fun migrateLayoutMirrorIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_LAYOUT_MIRROR_V1, false)) return
        runCatching {
            val land = readDirection(context, false)      // 横屏为权威（不递归到 items() ✓）
            writePositions(context, true, mirrorLayout(land, srcPortrait = false))
        }
        p.edit().putBoolean(KEY_LAYOUT_MIRROR_V1, true).apply()
    }

    private fun readDirection(context: Context, portrait: Boolean): List<DesktopItem> {
        val (cols, rows) = gridOf(portrait)
        val ids = identities(context)
        val positions = readPositions(context, portrait)

        val out = ArrayList<DesktopItem>(ids.size)
        var needFix = false

        ids.forEach { id ->
            val base = toItem(id, 0, 0, 0)
            val pos = positions[base.key]
            if (pos == null) {
                needFix = true
                return@forEach
            }
            val parts = pos.split(',')
            if (parts.size != 3) {
                needFix = true
                return@forEach
            }
            val page = parts[0].toIntOrNull() ?: 0
            val col = parts[1].toIntOrNull() ?: 0
            val row = parts[2].toIntOrNull() ?: 0
            // 越界（例如另一方向保存的坐标）：视为无效，交给自动补位
            if (col + base.colSpan > cols || row + base.rowSpan > rows) {
                needFix = true
                return@forEach
            }
            out += toItem(id, page, col, row)
        }

        if (!needFix) {
            // ★ 自愈（第十一轮第 2 项）：能解析、但**同一页两项占同一格**（历史版本可能写坏）时，
            //   立刻把冲突项挪开并落盘 —— 用户桌上已经压在一起的两个图标，打开桌面就自动分开 ✓
            //   （不需要清数据/重装 ✓）。落盘 `[Layout] 自愈 …`。
            val (safe, movedCount) = sanitizeLayout(out, portrait)
            if (movedCount > 0) {
                writePositions(context, portrait, safe)
                runCatching { writePositions(context, !portrait, mirrorLayout(safe, portrait)) }
                com.dsh.sportscam.cover.CoverDebugLog.log(
                    "Layout",
                    "[Layout] 自愈 方向=${if (portrait) "竖" else "横"} 项数=${out.size} 修复=$movedCount"
                )
                return safe
            }
            return out
        }

        // 自动补位：把「有合法位置的」先占住，其余按顺序找空位
        val placed = ArrayList<DesktopItem>(ids.size)
        val validKeys = out.map { it.key }.toSet()
        // 先按原顺序重建：能保持的都保持
        ids.forEach { id ->
            val existing = out.firstOrNull { it.key == toItem(id, 0, 0, 0).key }
            if (existing != null) {
                placed += existing
            } else {
                val proto = toItem(id, 0, 0, 0)
                val slot = DesktopLayout.findFreeSlot(
                    placed, cols, rows, proto.colSpan, proto.rowSpan, storedPages(context)
                )
                if (slot != null) {
                    placed += toItem(id, slot.page, slot.col, slot.row)
                } else {
                    // 实在放不下：放到末尾页的下一格（findFreeSlot 已验证过不会发生）
                    placed += proto
                }
            }
        }
        val fixed = placed.sortedBy { ids.indexOfFirst { id -> toItem(id, 0, 0, 0).key == it.key } }
        writePositions(context, portrait, fixed)
        // 另一个方向跟着派生（幂等）✓
        runCatching { writePositions(context, !portrait, mirrorLayout(fixed, portrait)) }
        if (fixed.any { it.key !in validKeys }) version.intValue++
        return fixed
    }

    // ---------------- 横竖屏方向的**旋转派生**（第十轮第 6 项） ----------------

    /**
     * **完整性检查 / 自愈**（第十一轮第 2 项）：同一页内不允许两项占同一格。
     *
     * 规则：按顺序检查每一项 ——
     *   · 越界（col+span>cols 或 row+span>rows）或与"已通过检查的项"重叠 → 视为冲突；
     *   · 冲突项用 [DesktopLayout.findFreeSlot] 挪到最近的空位（找不到就放到末尾页第一格）；
     *   · 返回（修好的列表, 被挪动的项数），调用方负责落盘与日志。
     *
     * 这里**不依赖**任何"上层传进来的占用判断"——它就是最后一道防线：
     * 不管是镜像/迁移/拖动/删除哪条路径写出来的，落盘前都会过一遍 ✓。
     */
    private fun sanitizeLayout(
        items: List<DesktopItem>,
        portrait: Boolean
    ): Pair<List<DesktopItem>, Int> {
        val (cols, rows) = gridOf(portrait)
        val placed = ArrayList<DesktopItem>(items.size)
        var moved = 0
        items.forEach { item ->
            val inBounds = item.page >= 0 && item.col >= 0 && item.row >= 0 &&
                item.col + item.colSpan <= cols && item.row + item.rowSpan <= rows
            val collides = !inBounds || placed.any {
                it.page == item.page && DesktopLayout.overlaps(it, item)
            }
            if (!collides) {
                placed += item
            } else {
                val slot = DesktopLayout.findFreeSlot(
                    placed, cols, rows, item.colSpan, item.rowSpan, item.page
                )
                val fixed = if (slot != null) withPos(item, slot.page, slot.col, slot.row)
                else withPos(item, (placed.maxOfOrNull { it.page } ?: 0) + 1, 0, 0)
                placed += fixed
                moved++
                com.dsh.sportscam.cover.CoverDebugLog.log(
                    "Layout",
                    "[Layout] 冲突修复 方向=${if (portrait) "竖" else "横"} 项=${item.key} " +
                        "原=(${item.page},${item.col},${item.row}) " +
                        "新=(${fixed.page},${fixed.col},${fixed.row})"
                )
            }
        }
        return placed to moved
    }

    /**
     * 把一个方向的布局**镜像**成另一个方向的布局 —— 即用户要的"直接旋转界面"。
     *
     * 规则（按 Lead 给的精确公式）：
     *  1. 每项先按**旋转公式**放：横→竖 `(col,row) → ((R_L-1)-row, col)`，竖→横用逆映射；
     *     1×1 / 2×2 这类**方阵**旋转后一定放得下 ✓（2×2 在 2 列网格里正好占满两列）；
     *     锚点最后再夹到目标网格合法范围（防止 2×2 在 2 列宽时越界 ✓）。
     *  2. 放不下的（1×2 / 2×1 / 3×2 这类**复杂尺寸**，或旋转后与别人重叠）记为"溢出"：
     *     用 [DesktopLayout.findFreeSlot] 依次塞到**下一页的空白处**（会继续往后找 ✓，
     *     就像 Lead 说的"下一页也放不下就继续往后挪" ✓）。
     *  3. **稳定顺序**：先按源方向 (page,col,row) 排 ⇒ 每次重排结果完全一样、不会抖 ✓；
     *     溢出队列排**应用优先、小组件最后** ⇒ 空间不够时先被挪走的是小组件（尽量保留应用 ✓）。
     *
     * 全程落盘一行 `[Layout] …`（方向 / 页面数 / 项数 / 旋转映射成功数 / 复杂项数 / 溢出移动清单），
     * 便于验收。
     */
    private fun mirrorLayout(source: List<DesktopItem>, srcPortrait: Boolean): List<DesktopItem> {
        val (_, srcRows) = gridOf(srcPortrait)
        val (dstCols, dstRows) = gridOf(!srcPortrait)
        val placed = ArrayList<DesktopItem>(source.size)
        val overflow = ArrayList<DesktopItem>()
        var rotatedOk = 0

        source.sortedWith(compareBy({ it.page }, { it.col }, { it.row })).forEach { item ->
            val (c, r) =
                if (!srcPortrait) DesktopLayout.rotateToPortrait(item.col, item.row, srcRows)
                else DesktopLayout.rotateToLandscape(item.col, item.row, srcRows)
            val anchorCol = c.coerceIn(0, (dstCols - item.colSpan).coerceAtLeast(0))
            val anchorRow = r.coerceIn(0, (dstRows - item.rowSpan).coerceAtLeast(0))
            val fits = DesktopLayout.canPlace(
                placed, item.colSpan, item.rowSpan, item.page, anchorCol, anchorRow, dstCols, dstRows
            )
            if (fits) {
                placed += withPos(item, item.page, anchorCol, anchorRow)
                rotatedOk++
            } else {
                overflow += item
            }
        }

        // 溢出项：应用优先、小组件最后；仍按源顺序稳定排列
        val list = placed.toMutableList()
        val moved = StringBuilder()
        var nextPage = (list.maxOfOrNull { it.page } ?: 0) + 1
        overflow.sortedWith(compareBy({ it is DesktopItem.Widget }, { it.page }, { it.col }, { it.row }))
            .forEach { item ->
                val slot = DesktopLayout.findFreeSlot(
                    list, dstCols, dstRows, item.colSpan, item.rowSpan, nextPage
                )
                val target = if (slot != null) withPos(item, slot.page, slot.col, slot.row)
                else withPos(item, nextPage++, 0, 0)
                list += target
                moved.append("${item.key}→第${target.page + 1}页 ")
            }

        val complexCount = source.count { it.colSpan != it.rowSpan }
        com.dsh.sportscam.cover.CoverDebugLog.log(
            "Layout",
            "[Layout] 方向=${if (srcPortrait) "竖→横" else "横→竖"} " +
                "页面=${(list.maxOfOrNull { it.page } ?: 0) + 1} 项数=${source.size} " +
                "旋转映射=$rotatedOk 复杂项=$complexCount " +
                "溢出移动=${if (moved.isEmpty()) "无" else moved.toString().trim()}"
        )
        return list
    }

    private fun toItem(id: Identity, page: Int, col: Int, row: Int): DesktopItem =
        when (id.type) {
            "A" -> DesktopItem.App(id.payload, page, col, row)
            "S" -> DesktopItem.CameraModeSwitch(page, col, row)
            "P" -> DesktopItem.PrivateAppsEntry(page, col, row)
            "T" -> DesktopItem.TrashEntry(page, col, row)
            "F" -> {
                val parts = id.payload.split('|')
                DesktopItem.Folder(
                    folderId = parts.getOrNull(0)?.toLongOrNull() ?: -1L,
                    name = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "文件夹",
                    apps = parts.getOrNull(2)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
                    page = page, col = col, row = row
                )
            }
            else -> {
                val parts = id.payload.split('|')
                DesktopItem.Widget(
                    appWidgetId = parts[0].toIntOrNull() ?: -1,
                    provider = parts.getOrElse(1) { "" },
                    colSpan = id.colSpan,
                    rowSpan = id.rowSpan,
                    page = page, col = col, row = row
                )
            }
        }

    // ---------------- 写入 ----------------

    /** 保存当前方向的完整状态（有序清单 + 本方向位置表 + **另一方向的旋转派生**） */
    private fun save(context: Context, portrait: Boolean, list: List<DesktopItem>) {
        // ★ 完整性保险（第十一轮第 2 项）：**任何写盘之前**都先做一次"同页不允许两项占同一格"的检查，
        //   发现冲突就把后写入的项挪到最近的空位（并落盘 `[Layout] 冲突修复 …`）。
        //   为什么必须在**写之前**：以前 handleDrop 用的是组合期捕获的（可能过期的）items 列表做占用判断，
        //   一旦判断失效就会把两项写到同一格 —— 用户桌面上 deepseek 与 private_apps 压在一起就是这么来的 ✗。
        val (safe, movedCount) = sanitizeLayout(list, portrait)
        val finalList = if (movedCount > 0) safe else list
        writeIdentities(context, finalList)
        writePositions(context, portrait, finalList)
        // ★ 第十轮第 6 项：另一个方向 = 本方向按旋转公式镜像（双向同步、幂等 ✓）。
        //   在竖屏里拖动/换位后，横屏布局也会按逆映射跟着更新；反之亦然 ✓。
        runCatching { writePositions(context, !portrait, mirrorLayout(finalList, portrait)) }
        version.intValue++
    }

    /** 添加应用：在当前方向找空位；已在桌面则返回 false */
    fun addApp(context: Context, packageName: String, portrait: Boolean, cols: Int, rows: Int): Boolean {
        val list = items(context, portrait)
        if (list.any { it is DesktopItem.App && it.packageName == packageName }) return false
        val slot = DesktopLayout.findFreeSlot(list, cols, rows, 1, 1, storedPages(context))
            ?: return false
        save(context, portrait, list + DesktopItem.App(packageName, slot.page, slot.col, slot.row))
        ensurePages(context, slot.page + 1)
        return true
    }

    /** 添加小组件：在当前方向找能容纳它的空位 */
    fun addWidget(
        context: Context,
        appWidgetId: Int,
        provider: String,
        portrait: Boolean,
        cols: Int,
        rows: Int,
        spanCols: Int,
        spanRows: Int
    ): Boolean {
        val list = items(context, portrait)
        val w = spanCols.coerceIn(1, cols)
        val h = spanRows.coerceIn(1, rows)
        val slot = DesktopLayout.findFreeSlot(list, cols, rows, w, h, storedPages(context))
            ?: return false
        save(
            context, portrait,
            list + DesktopItem.Widget(appWidgetId, provider, w, h, slot.page, slot.col, slot.row)
        )
        ensurePages(context, slot.page + 1)
        return true
    }

    fun moveTo(context: Context, portrait: Boolean, key: String, page: Int, col: Int, row: Int) {
        val list = items(context, portrait).map { item ->
            if (item.key != key) item else withPos(item, page, col, row)
        }
        save(context, portrait, list)
    }

    fun swap(context: Context, portrait: Boolean, keyA: String, keyB: String) {
        val list = items(context, portrait)
        val a = list.firstOrNull { it.key == keyA } ?: return
        val b = list.firstOrNull { it.key == keyB } ?: return
        save(
            context, portrait,
            list.map { item ->
                when (item.key) {
                    keyA -> withPos(item, b.page, b.col, b.row)
                    keyB -> withPos(item, a.page, a.col, a.row)
                    else -> item
                }
            }
        )
    }

    private fun withPos(item: DesktopItem, page: Int, col: Int, row: Int): DesktopItem =
        when (item) {
            is DesktopItem.App -> item.copy(page = page, col = col, row = row)
            is DesktopItem.Widget -> item.copy(page = page, col = col, row = row)
            is DesktopItem.CameraModeSwitch -> item.copy(page = page, col = col, row = row)
            is DesktopItem.PrivateAppsEntry -> item.copy(page = page, col = col, row = row)
            is DesktopItem.TrashEntry -> item.copy(page = page, col = col, row = row)
            is DesktopItem.Folder -> item.copy(page = page, col = col, row = row)
        }

    fun remove(context: Context, portrait: Boolean, item: DesktopItem) {
        val list = items(context, portrait)
        if (list.none { it.key == item.key }) return
        save(context, portrait, list.filterNot { it.key == item.key })
    }

    /** 按包名移除（总应用列表的长按菜单用）。两个方向都清掉。 */
    fun removeApp(context: Context, packageName: String) {
        listOf(false, true).forEach { portrait ->
            val list = items(context, portrait)
            if (list.none { it is DesktopItem.App && it.packageName == packageName }) return@forEach
            save(context, portrait, list.filterNot { it is DesktopItem.App && it.packageName == packageName })
        }
    }

    /** 删除整页，并把后面页的内容整体前移一页 */
    fun removePage(context: Context, portrait: Boolean, page: Int) {
        val list = items(context, portrait)
            .filterNot { it.page == page }
            .map { if (it.page > page) withPos(it, it.page - 1, it.col, it.row) else it }
        save(context, portrait, list)
        setStoredPages(context, (storedPages(context) - 1).coerceAtLeast(1))
    }

    fun containsApp(context: Context, packageName: String): Boolean =
        identities(context).any { it.type == "A" && it.payload == packageName }

    // ---------------- 文件夹（第十轮第 5 项） ----------------
    //
    // 数据模型：文件夹内容存在 `DesktopItem.Folder.apps` 里（随项一起存档 `F|id|name|pkgs`）。
    // 设计选择（回报里也写明了）：
    //   · **加入文件夹**：把应用加进 folder.apps，并把桌面上那个 App 项**移除** ✓
    //     ⇒ "文件夹内应用不在桌面单独显示" ✓（数据在文件夹里，没删掉 ✓）；
    //   · **从文件夹移除 / 删除文件夹**：把应用**放回桌面空位** ✓
    //     ⇒ "取消文件夹后应用能回到桌面" ✓；
    //   · 写操作统一走**横屏权威** + `save()` 会自动把竖屏镜像同步过去 ✓。

    /** 文件夹内部每页放 4 个应用 ⇒ pages = ceil(apps/4)（用户要求） */
    fun folderPages(appCount: Int): Int = if (appCount <= 0) 1 else (appCount + 3) / 4

    /**
     * 当前所有文件夹（按 id 排序，结果稳定）。
     *
     * ⚠ [FolderInfo.apps] 也是**过滤后的**（不含私密应用）—— 与 [folderApps] 同一个口径，
     *   避免"界面按 folders() 拿到的数量和 folderApps() 不一致 → 出现空页" ✗。
     */
    fun folders(context: Context): List<FolderInfo> {
        val hidden = hiddenForFolderView(context)
        return items(context, false).filterIsInstance<DesktopItem.Folder>()
            .sortedBy { it.folderId }
            .map {
                val apps = if (hidden.isEmpty()) it.apps else it.apps.filter { pkg -> pkg !in hidden }
                FolderInfo(it.folderId, it.name, apps)
            }
    }

    fun folderName(context: Context, folderId: Long): String =
        items(context, false).asSequence()
            .filterIsInstance<DesktopItem.Folder>()
            .firstOrNull { it.folderId == folderId }?.name ?: ""

    /**
     * 文件夹里**要显示**的应用（第十二轮补丁：**自动排除已设为私密的应用** ✓）。
     *
     * 为什么在数据层过滤：单一真相 ⇒ 文件夹界面、页数、缩略图自动一致 ✓，
     *   各界面不用各写一套过滤（写漏一处就会出现"私密应用还在文件夹里显示"✗）。
     *
     * 过滤依据是 [com.dsh.sportscam.cover.CoverSettings.privateAppPackages]（**生效值**）：
     *   私密功能总开关关掉时它返回空集 ⇒ 应用**照常显示** ✓（不能用 preferred 那版 ✗）。
     */
    fun folderApps(context: Context, folderId: Long): List<String> {
        val raw = folderAppsRaw(context, folderId)
        val hidden = hiddenForFolderView(context)
        return if (hidden.isEmpty()) raw else raw.filter { it !in hidden }
    }

    /**
     * 文件夹的**原始内容**（不过滤私密）—— 只给"管理/删除/取消私密后恢复显示"这类逻辑用 ✓。
     * 展示请用 [folderApps] ✓。
     */
    fun folderAppsRaw(context: Context, folderId: Long): List<String> =
        items(context, false).asSequence()
            .filterIsInstance<DesktopItem.Folder>()
            .firstOrNull { it.folderId == folderId }?.apps ?: emptyList()

    /** 私密应用的**生效值**（私密总开关关闭 ⇒ 空集 ⇒ 全部照常显示 ✓） */
    private fun hiddenForFolderView(context: Context): Set<String> =
        runCatching { com.dsh.sportscam.cover.CoverSettings.privateAppPackages(context) }
            .getOrDefault(emptySet())

    /** 重命名文件夹（名字里的分隔符会被 sanitize） */
    fun renameFolder(context: Context, folderId: Long, name: String): Boolean =
        updateFolder(context, folderId) { it.copy(name = name.ifBlank { "文件夹" }) }

    /** 加应用进文件夹：同时把它从桌面移除 ✓（已在文件夹里返回 false） */
    fun addAppToFolder(context: Context, folderId: Long, packageName: String): Boolean {
        val list = items(context, false)
        val idx = list.indexOfFirst { it is DesktopItem.Folder && it.folderId == folderId }
        if (idx < 0) return false
        val folder = list[idx] as DesktopItem.Folder
        if (folder.apps.contains(packageName)) return false
        val newList = list.toMutableList()
        newList[idx] = folder.copy(apps = folder.apps + packageName)
        newList.removeAll { it is DesktopItem.App && it.packageName == packageName }
        save(context, false, newList)
        return true
    }

    /** 从文件夹移除应用：同时把应用**放回桌面空位** ✓ */
    fun removeFromFolder(context: Context, folderId: Long, packageName: String): Boolean {
        val list = items(context, false)
        val idx = list.indexOfFirst { it is DesktopItem.Folder && it.folderId == folderId }
        if (idx < 0) return false
        val folder = list[idx] as DesktopItem.Folder
        if (!folder.apps.contains(packageName)) return false
        val newList = list.toMutableList()
        newList[idx] = folder.copy(apps = folder.apps - packageName)
        restoreAppToDesktop(context, newList, packageName)
        save(context, false, newList)
        return true
    }

    /** 删除文件夹（编辑模式的 ×）：**里面所有应用都放回桌面** ✓（不是连数据一起删 ✗） */
    fun removeFolderAndRestoreApps(context: Context, portrait: Boolean, folderId: Long): Boolean {
        val list = items(context, portrait)
        val folder = list.firstOrNull { it is DesktopItem.Folder && it.folderId == folderId }
            as? DesktopItem.Folder ?: return false
        val newList = list.filterNot { it.key == folder.key }.toMutableList()
        folder.apps.forEach { pkg -> restoreAppToDesktop(context, newList, pkg) }
        save(context, portrait, newList)
        return true
    }

    /** 新建文件夹并放到当前方向的空位；返回新 id（放不下返回 null） */
    fun addFolder(context: Context, portrait: Boolean, name: String = "文件夹"): Long? {
        val list = items(context, portrait)
        val (cols, rows) = gridOf(portrait)
        val slot = DesktopLayout.findFreeSlot(list, cols, rows, 1, 1, storedPages(context))
            ?: return null
        val id = (list.filterIsInstance<DesktopItem.Folder>().maxOfOrNull { it.folderId } ?: 0L) + 1L
        save(
            context, portrait,
            list + DesktopItem.Folder(id, name, emptyList(), slot.page, slot.col, slot.row)
        )
        ensurePages(context, slot.page + 1)
        return id
    }

    /** 读-改-写单个文件夹（横屏权威；save 会同步竖屏） */
    private fun updateFolder(
        context: Context,
        folderId: Long,
        change: (DesktopItem.Folder) -> DesktopItem.Folder
    ): Boolean {
        val list = items(context, false)
        val idx = list.indexOfFirst { it is DesktopItem.Folder && it.folderId == folderId }
        if (idx < 0) return false
        val newList = list.toMutableList()
        newList[idx] = change(list[idx] as DesktopItem.Folder)
        save(context, false, newList)
        return true
    }

    /** 把某个应用放回桌面空位（已在桌面就跳过） */
    private fun restoreAppToDesktop(
        context: Context,
        list: MutableList<DesktopItem>,
        packageName: String
    ) {
        if (list.any { it is DesktopItem.App && it.packageName == packageName }) return
        val (cols, rows) = gridOf(false)
        val slot = DesktopLayout.findFreeSlot(list, cols, rows, 1, 1, storedPages(context)) ?: return
        list += DesktopItem.App(packageName, slot.page, slot.col, slot.row)
        ensurePages(context, slot.page + 1)
    }

    // ---------------- 内置功能项（运动相机模式开关 / 私密应用入口） ----------------

    /** 某个 key 的桌面项是否已在桌面上（与方向无关：清单是共享的） */
    fun containsItem(context: Context, key: String): Boolean =
        identities(context).any { toItem(it, 0, 0, 0).key == key }

    /**
     * 内置功能项的**通用添加**：已在桌面则返回 false；否则找空位放下并落盘。
     *
     * 位置只写**当前方向**；另一个方向由 [items] 既有的"自动补位"补齐 ✓
     * （所以不会因为这里只写了一个方向而丢布局）。
     */
    private fun addBuiltInItem(context: Context, portrait: Boolean, proto: DesktopItem): Boolean {
        val (cols, rows) = gridOf(portrait)
        val list = items(context, portrait)
        if (list.any { it.key == proto.key }) return false
        val slot = DesktopLayout.findFreeSlot(list, cols, rows, proto.colSpan, proto.rowSpan, storedPages(context))
        val placed = if (slot != null) withPos(proto, slot.page, slot.col, slot.row) else proto
        save(context, portrait, list + placed)
        if (slot != null) ensurePages(context, slot.page + 1)
        return true
    }

    /** 给界面用：把「运动相机模式」开关加到桌面（已存在返回 false） */
    fun addCameraModeSwitch(context: Context, portrait: Boolean): Boolean =
        addBuiltInItem(context, portrait, DesktopItem.CameraModeSwitch())

    /** 给界面用：把「私密应用」入口加到桌面（已存在返回 false） */
    fun addPrivateAppsEntry(context: Context, portrait: Boolean): Boolean =
        addBuiltInItem(context, portrait, DesktopItem.PrivateAppsEntry())

    /**
     * 给界面用（list-viewer 的「最近删除」界面里的"在桌面添加入口"按钮）：
     * 把 1×1「最近删除」入口（垃圾桶图标）加到桌面 —— **幂等**，已存在返回 false ✓。
     */
    fun addTrashEntry(context: Context, portrait: Boolean): Boolean =
        addBuiltInItem(context, portrait, DesktopItem.TrashEntry())

    /** 桌面上有没有「最近删除」入口（界面可据此把按钮显示成"已在桌面"） */
    fun containsTrashEntry(context: Context): Boolean = containsItem(context, KEY_ITEM_TRASH_ENTRY)

    /**
     * **首次运行预置**：把两个内置 1×1 项放到桌面空位（找不到空位 [findFreeSlot] 会自动往后找页）。
     *
     * · 只在**第一次**执行（[KEY_PRESET_ITEMS] 未置位）；执行后就置位，**不会每次启动再加** ✓；
     * · 逐项检查 `containsItem`：万一上次只成功了一半，这次会把它补上；
     * · 只写当前方向的位置，另一个方向靠 [items] 的自动补位 ✓；
     * · 返回是否真的改动了桌面（调用方可用它决定要不要刷新）。
     */
    fun ensurePresetItems(context: Context, portrait: Boolean): Boolean {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_PRESET_ITEMS, false)) return false
        var changed = false
        runCatching {
            changed = addCameraModeSwitch(context, portrait) || changed
            changed = addPrivateAppsEntry(context, portrait) || changed
        }
        // 无论成功与否都置位：避免以后每次进桌面都重试（真失败了用户也能手动加，见上面的 add*）
        prefs.edit().putBoolean(KEY_PRESET_ITEMS, true).apply()
        return changed
    }

    // ---------------- 页数 ----------------

    fun storedPages(context: Context): Int =
        prefs(context).getInt(KEY_PAGES, 1).coerceAtLeast(1)

    fun setStoredPages(context: Context, pages: Int) {
        prefs(context).edit().putInt(KEY_PAGES, pages.coerceAtLeast(1)).apply()
        version.intValue++
    }

    fun ensurePages(context: Context, pages: Int) {
        if (pages > storedPages(context)) setStoredPages(context, pages)
    }

    fun contentPages(context: Context, portrait: Boolean): Int =
        (items(context, portrait).maxOfOrNull { it.page } ?: 0) + 1

    fun totalPages(context: Context, portrait: Boolean): Int =
        maxOf(storedPages(context), contentPages(context, portrait))

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        version.intValue++
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
