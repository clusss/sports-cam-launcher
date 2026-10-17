package com.dsh.sportscam.cover

import android.content.Context
import android.util.Log

/** 一组「副屏分辨率 + DPI」预设 */
data class DisplayPreset(
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Int
) {
    val resolution: String get() = "${width}x${height}"

    fun encode(): String = "$name|$width|$height|$dpi"

    companion object {
        fun decode(raw: String): DisplayPreset? {
            val parts = raw.split('|')
            if (parts.size != 4) return null
            val w = parts[1].toIntOrNull() ?: return null
            val h = parts[2].toIntOrNull() ?: return null
            val d = parts[3].toIntOrNull() ?: return null
            return DisplayPreset(parts[0], w, h, d)
        }
    }
}

/**
 * 副屏分辨率 / DPI 预设管理。
 *
 * 命令（来自《超级小副屏》反编译，逐字确认）：
 *   cmd window size <宽>x<高> -d <副屏id>
 *   cmd window density <dpi>  -d <副屏id>
 *   cmd window size reset     -d <副屏id>
 *   cmd window density reset  -d <副屏id>
 *
 * 为什么要改：682×422 / 200dpi 的副屏，很多第三方 App 会按"超小屏"排版，
 * 或者直接错乱。把逻辑尺寸和密度调大（例如 1080×668 / 320dpi），
 * App 会以为自己在正常手机上，系统再缩放输出到物理屏 —— 显示就正常了。
 */
object DisplayPresetStore {

    private const val TAG = "DisplayPresetStore"
    private const val PREFS = "cover_display_presets"
    private const val KEY_PRESETS = "presets"
    private const val KEY_PER_APP = "per_app"
    private const val SEP = ";"

    /**
     * 内置预设。
     *
     * 物理屏是 682×422：下面这些"逻辑尺寸"都会被系统等比缩放到这块屏上。
     */
    fun defaultPresets(): List<DisplayPreset> = listOf(
        DisplayPreset("原厂 682x422", 682, 422, 200),
        DisplayPreset("兼容手机 1080x668", 1080, 668, 320),
        DisplayPreset("兼容手机 1280x792", 1280, 792, 360),
        DisplayPreset("平板 1600x990", 1600, 990, 420),
        DisplayPreset("超大 1920x1188", 1920, 1188, 480)
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<DisplayPreset> {
        val raw = prefs(context).getString(KEY_PRESETS, null) ?: return defaultPresets()
        val list = raw.split(SEP).mapNotNull { DisplayPreset.decode(it.trim()) }
        return list.ifEmpty { defaultPresets() }
    }

    fun save(context: Context, presets: List<DisplayPreset>) {
        prefs(context).edit()
            .putString(KEY_PRESETS, presets.joinToString(SEP) { it.encode() })
            .apply()
    }

    // ---------------- per-app 预设 ----------------

    private fun loadPerApp(context: Context): MutableMap<String, String> {
        val raw = prefs(context).getString(KEY_PER_APP, "") ?: ""
        val map = mutableMapOf<String, String>()
        raw.split(SEP).forEach { entry ->
            val i = entry.indexOf('|')
            if (i > 0) map[entry.substring(0, i)] = entry.substring(i + 1)
        }
        return map
    }

    fun presetForApp(context: Context, packageName: String): DisplayPreset? {
        val encoded = loadPerApp(context)[packageName] ?: return null
        return load(context).firstOrNull { it.encode() == encoded }
    }

    fun setPresetForApp(context: Context, packageName: String, preset: DisplayPreset?) {
        val map = loadPerApp(context)
        if (preset == null) map.remove(packageName) else map[packageName] = preset.encode()
        prefs(context).edit()
            .putString(KEY_PER_APP, map.entries.joinToString(SEP) { "${it.key}|${it.value}" })
            .apply()
    }

    // ---------------- 应用 / 还原 ----------------

    /** 套用预设：先改 size 再改 density */
    suspend fun apply(displayId: Int, preset: DisplayPreset): Boolean {
        val sizeOk = ShizukuShell.execOk("cmd window size ${preset.resolution} -d $displayId")
        val dpiOk = ShizukuShell.execOk("cmd window density ${preset.dpi} -d $displayId")
        Log.d(TAG, "apply ${preset.name} -> size=$sizeOk dpi=$dpiOk")
        return sizeOk && dpiOk
    }

    /** 还原成出厂 */
    suspend fun reset(displayId: Int): Boolean {
        val sizeOk = ShizukuShell.execOk("cmd window size reset -d $displayId")
        val dpiOk = ShizukuShell.execOk("cmd window density reset -d $displayId")
        Log.d(TAG, "reset -> size=$sizeOk dpi=$dpiOk")
        return sizeOk && dpiOk
    }

    /** 读取当前逻辑尺寸，形如 "Physical size: 682x422" */
    suspend fun querySize(displayId: Int): String? =
        ShizukuShell.exec("wm size -d $displayId")?.trim()

    /** 读取当前密度，形如 "Physical density: 200" */
    suspend fun queryDensity(displayId: Int): String? =
        ShizukuShell.exec("wm density -d $displayId")?.trim()

    // ==================================================================
    // 分辨率 与 DPI 的「分开保存、分开套用」
    // ==================================================================

    private const val KEY_CUSTOM_SIZES = "custom_sizes"
    private const val KEY_CUSTOM_DPIS = "custom_dpis"

    /** 已保存的自定义分辨率，格式 "w,h" */
    fun customSizes(context: Context): List<Pair<Int, Int>> {
        val raw = prefs(context).getString(KEY_CUSTOM_SIZES, null) ?: return emptyList()
        return raw.split(SEP).mapNotNull { entry ->
            val parts = entry.trim().split(',')
            if (parts.size != 2) return@mapNotNull null
            val w = parts[0].toIntOrNull() ?: return@mapNotNull null
            val h = parts[1].toIntOrNull() ?: return@mapNotNull null
            w to h
        }
    }

    fun saveCustomSize(context: Context, width: Int, height: Int) {
        val list = customSizes(context).toMutableList()
        if (list.none { it.first == width && it.second == height }) list.add(width to height)
        prefs(context).edit()
            .putString(KEY_CUSTOM_SIZES, list.joinToString(SEP) { "${it.first},${it.second}" })
            .apply()
    }

    fun removeCustomSize(context: Context, width: Int, height: Int) {
        val list = customSizes(context).filterNot { it.first == width && it.second == height }
        prefs(context).edit()
            .putString(KEY_CUSTOM_SIZES, list.joinToString(SEP) { "${it.first},${it.second}" })
            .apply()
    }

    /** 已保存的自定义 DPI */
    fun customDpis(context: Context): List<Int> {
        val raw = prefs(context).getString(KEY_CUSTOM_DPIS, null) ?: return emptyList()
        return raw.split(SEP).mapNotNull { it.trim().toIntOrNull() }
    }

    fun saveCustomDpi(context: Context, dpi: Int) {
        val list = customDpis(context).toMutableList()
        if (!list.contains(dpi)) list.add(dpi)
        prefs(context).edit()
            .putString(KEY_CUSTOM_DPIS, list.joinToString(SEP) { it.toString() })
            .apply()
    }

    fun removeCustomDpi(context: Context, dpi: Int) {
        prefs(context).edit()
            .putString(KEY_CUSTOM_DPIS, customDpis(context).filterNot { it == dpi }.joinToString(SEP))
            .apply()
    }

    // ==================================================================
    // 「分辨率 + DPI」**成组**保存 / 成组套用
    //
    // 分辨率与 DPI 是互相影响的（同样的 dpi 在不同逻辑尺寸下观感完全不同），
    // 所以这里把一组 (宽,高,dpi) 作为一个预设：保存一次、点一下一起套用。
    // ==================================================================

    private const val KEY_CUSTOM_GROUPS = "custom_groups"

    /** 已保存的组合 */
    fun customGroups(context: Context): List<DisplayPreset> {
        val raw = prefs(context).getString(KEY_CUSTOM_GROUPS, null) ?: return emptyList()
        return raw.split(SEP).mapNotNull { entry ->
            val parts = entry.trim().split(',')
            if (parts.size != 3) return@mapNotNull null
            val w = parts[0].toIntOrNull() ?: return@mapNotNull null
            val h = parts[1].toIntOrNull() ?: return@mapNotNull null
            val d = parts[2].toIntOrNull() ?: return@mapNotNull null
            DisplayPreset("${w}x$h@$d", w, h, d)
        }
    }

    fun saveCustomGroup(context: Context, width: Int, height: Int, dpi: Int) {
        val list = customGroups(context).toMutableList()
        if (list.none { it.width == width && it.height == height && it.dpi == dpi }) {
            list.add(DisplayPreset("${width}x$height@$dpi", width, height, dpi))
        }
        prefs(context).edit().putString(
            KEY_CUSTOM_GROUPS,
            list.joinToString(SEP) { "${it.width},${it.height},${it.dpi}" }
        ).apply()
    }

    fun removeCustomGroup(context: Context, width: Int, height: Int, dpi: Int) {
        val list = customGroups(context)
            .filterNot { it.width == width && it.height == height && it.dpi == dpi }
        prefs(context).edit().putString(
            KEY_CUSTOM_GROUPS,
            list.joinToString(SEP) { "${it.width},${it.height},${it.dpi}" }
        ).apply()
    }

    /** 成组套用：先 size 再 density（与原版命令逐字一致） */
    suspend fun applyGroup(displayId: Int, width: Int, height: Int, dpi: Int): Boolean {
        val sizeOk = ShizukuShell.execOk("cmd window size ${width}x${height} -d $displayId")
        val dpiOk = ShizukuShell.execOk("cmd window density $dpi -d $displayId")
        Log.d(TAG, "applyGroup ${width}x$height@$dpi -> size=$sizeOk dpi=$dpiOk")
        return sizeOk && dpiOk
    }

    // ---- 单独套用（与原版的 "cmd window size/density <值> -d <id>" 逐字一致）----

    suspend fun applySize(displayId: Int, width: Int, height: Int): Boolean {
        val cmd = "cmd window size ${width}x${height} -d $displayId"
        val ok = ShizukuShell.execOk(cmd)
        Log.d(TAG, "$cmd -> $ok")
        return ok
    }

    suspend fun applyDensity(displayId: Int, dpi: Int): Boolean {
        val cmd = "cmd window density $dpi -d $displayId"
        val ok = ShizukuShell.execOk(cmd)
        Log.d(TAG, "$cmd -> $ok")
        return ok
    }

    suspend fun resetSize(displayId: Int): Boolean =
        ShizukuShell.execOk("cmd window size reset -d $displayId")

    suspend fun resetDensity(displayId: Int): Boolean =
        ShizukuShell.execOk("cmd window density reset -d $displayId")
}
