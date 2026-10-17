package com.dsh.sportscam.media

import android.content.Context
import com.dsh.sportscam.cover.CoverDebugLog

/**
 * 启动器内的"隐藏"清单 —— 「删除」的新语义。
 *
 * 为什么不再移动/删除文件：
 *  vivo（OriginOS）的安全服务会广播 `com.vivo.services.security.client.FileDelete`，
 *  相册里的 `SafeDeleteReceiver` 收到后会把被删文件留进它自己的
 *  **"第三方删除的相册"**（30 天后才真正清除，除非手动解除）。这是 ROM 级机制，
 *  第三方应用无法关闭（实测取证：`.vivoFileRecycleBin` + `SafeDeleteReceiver`）。
 *
 * 所以改为：**文件原地不动**，只在本启动器的清单里记一笔 → 我们的相册不再列出它，
 *  而系统看不到任何"删除事件"，vivo 自然不会拦截，也就没有那份 30 天的副本。
 *
 * 代价（已知并接受）：其它应用（vivo 相册、文件管理器）仍能看到这个文件 ——
 *  "删除"在启动器内是"隐藏"，不是"从系统移除"。需要真正移除时，
 *  用「最近删除」里的「彻底删除」（那时才会真的删文件，vivo 也可能再次拦截，属用户显式操作）。
 */
object HiddenMedia {

    private const val PREFS = "hidden_media"
    private const val KEY = "keys"

    /** key 形如 "image:12345" / "video:678"（不同类型 id 可能重复，所以要带类型） */
    fun keyOf(item: MediaItem, isVideo: Boolean): String =
        "${if (isVideo) "video" else "image"}:${item.id}"

    fun keyOf(id: Long, isVideo: Boolean): String = "${if (isVideo) "video" else "image"}:$id"

    fun all(context: Context): Set<String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()) ?: emptySet()

    fun isHidden(context: Context, id: Long, isVideo: Boolean): Boolean =
        all(context).contains(keyOf(id, isVideo))

    fun hide(context: Context, item: MediaItem, isVideo: Boolean): Boolean {
        val current = all(context).toMutableSet()
        if (!current.add(keyOf(item, isVideo))) return false
        save(context, current)
        CoverDebugLog.log("Hidden", "隐藏: ${keyOf(item, isVideo)} ${item.displayName}")
        return true
    }

    fun hideAll(context: Context, items: List<MediaItem>, isVideo: Boolean): Int {
        val current = all(context).toMutableSet()
        var n = 0
        items.forEach { if (current.add(keyOf(it, isVideo))) n++ }
        if (n > 0) save(context, current)
        CoverDebugLog.log("Hidden", "批量隐藏: ${items.size} 项，新增 $n")
        return n
    }

    fun unhide(context: Context, id: Long, isVideo: Boolean): Boolean {
        val current = all(context).toMutableSet()
        if (!current.remove(keyOf(id, isVideo))) return false
        save(context, current)
        CoverDebugLog.log("Hidden", "恢复显示: ${keyOf(id, isVideo)}")
        return true
    }

    fun clear(context: Context) {
        save(context, mutableSetOf())
    }

    private fun save(context: Context, keys: MutableSet<String>) {
        // 存副本：SharedPreferences 不会拷贝传入的 set，之后改动会影响落盘
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, HashSet(keys))
            .apply()
    }
}
