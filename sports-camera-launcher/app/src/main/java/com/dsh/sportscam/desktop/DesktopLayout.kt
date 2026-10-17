package com.dsh.sportscam.desktop

import kotlin.math.ceil

/**
 * 桌面的矩形占位计算。
 *
 * 布局是**显式坐标**（每个项自己带 page/col/row），这里只提供：
 *   · 找空位（新增项时自动放）
 *   · 碰撞/越界判定（拖动落点是否合法、两个项能否互换）
 *   · 旧数据自动排布
 */
object DesktopLayout {

    data class Slot(val page: Int, val col: Int, val row: Int)

    /** 两个矩形是否重叠（同页才可能） */
    fun overlaps(a: DesktopItem, b: DesktopItem): Boolean {
        if (a.page != b.page) return false
        return a.col < b.col + b.colSpan && b.col < a.col + a.colSpan &&
            a.row < b.row + b.rowSpan && b.row < a.row + a.rowSpan
    }

    /** 在指定位置放一个 spanW×spanH 的矩形是否合法（不越界、不与 [others] 重叠） */
    fun canPlace(
        others: List<DesktopItem>,
        spanW: Int,
        spanH: Int,
        page: Int,
        col: Int,
        row: Int,
        cols: Int,
        rows: Int
    ): Boolean {
        if (col < 0 || row < 0) return false
        if (col + spanW > cols || row + spanH > rows) return false
        return others.none { it.page == page && rectHit(it, spanW, spanH, col, row) }
    }

    private fun rectHit(other: DesktopItem, spanW: Int, spanH: Int, col: Int, row: Int): Boolean =
        col < other.col + other.colSpan && other.col < col + spanW &&
            row < other.row + other.rowSpan && other.row < row + spanH

    /** 页内某个格子上的项 */
    fun itemAt(items: List<DesktopItem>, page: Int, col: Int, row: Int): DesktopItem? =
        items.firstOrNull {
            it.page == page &&
                col >= it.col && col < it.col + it.colSpan &&
                row >= it.row && row < it.row + it.rowSpan
        }

    /** 找到第一个能放下 spanW×spanH 的位置；[startPage] 起的空白页也要考虑 */
    fun findFreeSlot(
        items: List<DesktopItem>,
        cols: Int,
        rows: Int,
        spanW: Int,
        spanH: Int,
        startPage: Int = 0,
        maxPages: Int = 50
    ): Slot? {
        if (cols <= 0 || rows <= 0) return null
        val w = spanW.coerceIn(1, cols)
        val h = spanH.coerceIn(1, rows)
        val lastContentPage = items.maxOfOrNull { it.page } ?: -1
        val lastPage = maxOf(startPage - 1, lastContentPage) + 1
        for (page in 0..maxOf(lastPage, 0) + 1) {
            if (page > maxPages) break
            for (r in 0..rows - h) {
                for (c in 0..cols - w) {
                    if (canPlace(items, w, h, page, c, r, cols, rows)) {
                        return Slot(page, c, r)
                    }
                }
            }
        }
        return null
    }

    /**
     * 旧数据迁移用：忽略原有坐标，按顺序重新排布到若干页里。
     */
    fun autoPlace(items: List<DesktopItem>, cols: Int, rows: Int, startPages: Int): List<DesktopItem> {
        val out = ArrayList<DesktopItem>(items.size)
        var pool = out
        for (item in items) {
            val slot = findFreeSlot(pool, cols, rows, item.colSpan, item.rowSpan, startPages) ?: continue
            val placed = when (item) {
                is DesktopItem.App -> item.copy(page = slot.page, col = slot.col, row = slot.row)
                is DesktopItem.Widget -> item.copy(page = slot.page, col = slot.col, row = slot.row)
                // 内置功能项（运动相机模式开关 / 私密应用入口）同样是 1×1，按显式坐标摆放
                is DesktopItem.CameraModeSwitch -> item.copy(page = slot.page, col = slot.col, row = slot.row)
                is DesktopItem.PrivateAppsEntry -> item.copy(page = slot.page, col = slot.col, row = slot.row)
                is DesktopItem.Folder -> item.copy(page = slot.page, col = slot.col, row = slot.row)
                is DesktopItem.TrashEntry -> item.copy(page = slot.page, col = slot.col, row = slot.row)
            }
            out += placed
            pool = out
        }
        return out
    }

    /**
     * **横屏 → 竖屏**的旋转映射（第十轮第 6 项，用户给的示意图）。
     *
     * 用户给的图（横屏 4×2 → 竖屏 2×4）：
     * ```
     * 横屏:  1 2 3 4       竖屏:  5 1
     *        5 6 7 8              6 2
     *                             7 3
     *                             8 4
     * ```
     * 就是"把横屏网格**顺时针旋转 90°**"：`(col,row) → ((R_L-1)-row, col)`。
     * 校验：#1(0,0)→(1,0) ✓  #5(0,1)→(0,0) ✓  #2(1,0)→(1,1) ✓  #6(1,1)→(0,1) ✓ 与示意图完全一致。
     *
     * @param landRows 横屏网格的行数（= 2；竖屏的列数正好等于它，两个方向互为转置）
     */
    fun rotateToPortrait(col: Int, row: Int, landRows: Int): Pair<Int, Int> =
        (landRows - 1 - row) to col

    /** 上面的**逆映射**：竖屏 → 横屏。`col = row'`，`row = (R_L-1)-col'` */
    fun rotateToLandscape(colPortrait: Int, rowPortrait: Int, landRows: Int): Pair<Int, Int> =
        rowPortrait to (landRows - 1 - colPortrait)

    /** 该页被占用的格子集合，形如 "col,row"（编辑模式画 + 号用） */
    fun occupiedCells(items: List<DesktopItem>, page: Int): Set<String> {
        val set = HashSet<String>()
        items.filter { it.page == page }.forEach { it ->
            for (r in it.row until it.row + it.rowSpan) {
                for (c in it.col until it.col + it.colSpan) set += "$c,$r"
            }
        }
        return set
    }
}

/**
 * 小组件占几格。
 *
 * 单元格在副屏上大约 120dp 一格，按最小尺寸换算再夹到当前页尺寸内；
 * 返回值也用于小组件选择器里显示的「n×m」。
 */
object WidgetSpan {

    private const val CELL_DP = 120f

    fun of(minWidthDp: Int, minHeightDp: Int, cols: Int, rows: Int): Pair<Int, Int> {
        val w = ceil((minWidthDp.coerceAtLeast(1) + 30) / CELL_DP).toInt()
            .coerceIn(1, cols.coerceAtLeast(1))
        val h = ceil((minHeightDp.coerceAtLeast(1) + 30) / CELL_DP).toInt()
            .coerceIn(1, rows.coerceAtLeast(1))
        return w to h
    }
}
