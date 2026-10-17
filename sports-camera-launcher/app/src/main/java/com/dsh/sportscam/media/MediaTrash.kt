package com.dsh.sportscam.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.dsh.sportscam.cover.CoverDebugLog
import java.io.File

/**
 * 「最近删除」——把媒体移到一个**第三方相册不会扫到**的目录。
 *
 * 目录选在 `Android/data/<包名>/files/recently_deleted/`：
 *  · Android 10+ 的媒体扫描**不会索引** `Android/data` 下的任何内容，所以第三方相册
 *    看不到这里的东西（满足"最好不会被第三方相册识别"）；
 *  · 属于应用专属外部目录，无需存储权限即可写入；
 *  · 代价是卸载应用会一并清掉 —— 对"最近删除"这种临时区来说可以接受。
 *
 * 额外放一个 `.nomedia`：万一某些设备的扫描策略更激进，也能挡住。
 */
object MediaTrash {

    private const val DIR_NAME = "recently_deleted"

    fun trashDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
        return dir
    }

    /**
     * 把媒体移动到最近删除目录。
     *
     * @return **null 表示成功**；否则返回给用户看的原因
     */
    fun move(context: Context, item: MediaItem): String? {
        val dir = trashDir(context)
        val dest = File(dir, uniqueName(dir, item.displayName))

        // ① 首选：直接 rename（同分区，瞬时完成，不占双份空间）。
        //    targetSdk 29 + requestLegacyExternalStorage 时 DATA 列给出的就是真实路径。
        val realPath = queryRealPath(context, item.uri)
        if (realPath != null) {
            val src = File(realPath)
            if (src.exists() && src.renameTo(dest)) {
                CoverDebugLog.log("Trash", "rename 成功: ${src.name} -> ${dest.name}")
                purgeRecord(context, item.uri, realPath)
                return null
            } else {
                CoverDebugLog.log(
                    "Trash",
                    "rename 未成功（exists=${src.exists()}）→ 走拷贝路径"
                )
            }
        }
        CoverDebugLog.log("Trash", "未能取得真实路径，走拷贝路径 uri=${item.uri}")

        // ② 退化：流拷贝 + 走 MediaStore 删除原条目
        return try {
            val input = context.contentResolver.openInputStream(item.uri)
                ?: return "无法读取该媒体"
            input.use { source ->
                dest.outputStream().use { target -> source.copyTo(target) }
            }
            val deleted = context.contentResolver.delete(item.uri, null, null)
            if (deleted <= 0) {
                runCatching { dest.delete() }
                "系统拒绝删除（请在控制台授予「所有文件访问」后重试）"
            } else {
                CoverDebugLog.log("Trash", "拷贝+删除成功: ${dest.name}")
                purgeRecord(context, item.uri, realPath)
                null
            }
        } catch (t: Throwable) {
            runCatching { dest.delete() }
            CoverDebugLog.log("Trash", "移动失败: ${t::class.java.simpleName} ${t.message}")
            "移动失败：${t.message ?: t::class.java.simpleName}"
        }
    }

    /** 批量移动；返回成功数 */
    fun moveAll(context: Context, items: List<MediaItem>): Int {
        var ok = 0
        items.forEach { if (move(context, it) == null) ok++ }
        CoverDebugLog.log("Trash", "批量移动: ${items.size} 项，成功 $ok")
        return ok
    }

    /**
     * **真实删除**（不再套一层回收站）。
     *
     * 用途：「最近删除」里的删除 = 直接删掉文件，而不是再移进我们的回收站目录。
     * 顺序：优先按真实路径删；拿不到路径就按 content URI 删；最后清 MediaStore 记录。
     *
     * @return null 表示成功，否则是给用户看的原因
     */
    fun deleteReally(context: Context, item: MediaItem): String? {
        val realPath = queryRealPath(context, item.uri)
        var deleted = false

        if (realPath != null) {
            deleted = runCatching { File(realPath).delete() }.getOrDefault(false)
        }
        if (!deleted) {
            deleted = runCatching { context.contentResolver.delete(item.uri, null, null) > 0 }
                .getOrDefault(false)
        }
        if (!deleted) return "删除失败（可能缺少「所有文件访问」权限）"

        purgeRecord(context, item.uri, realPath)
        CoverDebugLog.log("Trash", "真实删除成功: ${item.displayName}")
        return null
    }

    /** 列出最近删除里的文件（按修改时间倒序，最新的在前） */
    fun list(context: Context): List<File> =
        runCatching {
            trashDir(context).listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }.getOrDefault(emptyList())

    /** 恢复到公共媒体库（走 MediaStore 插入，重新出现在系统相册里） */
    fun restore(context: Context, file: File): String? {
        // ① 首选：直接把文件写回 Pictures / Movies 再通知扫描。
        //    有「所有文件访问」时这条最可靠 —— MediaStore 的 insert 在某些 ROM 上会被拒，
        //    而"写文件 + 扫描"是传统路径，系统相册一定会收录。
        val name = file.name.substringAfter('_', file.name)
        val isVideo = looksLikeVideo(name)
        // ★ 必须回到 **DCIM/Camera**。
        //   本启动器的相册只列出 DCIM/Camera 下的照片；之前写到 Pictures/Camera，
        //   于是出现"提示已恢复、但相册里看不到"（实测物证：文件都躺在 /sdcard/Pictures/Camera/ 下）。
        val targetDir = File(
            android.os.Environment.getExternalStorageDirectory(),
            "DCIM/Camera"
        )
        val direct = runCatching {
            if (!targetDir.exists()) targetDir.mkdirs()
            val dest = File(targetDir, name)
            file.copyTo(dest, overwrite = true)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
            if (file.delete()) {
                CoverDebugLog.log("Trash", "恢复成功（直接写文件）: ${dest.absolutePath}")
                return null
            }
            "已写回但原副本删除失败"
        }.getOrElse { null }
        if (direct == null || direct == "已写回但原副本删除失败") {
            if (direct == "已写回但原副本删除失败") return direct
        } else {
            CoverDebugLog.log("Trash", "直接写文件失败，退回 MediaStore 插入: $direct")
        }

        // ② 退化：MediaStore 插入（带 IS_PENDING 流程）
        return restoreViaMediaStore(context, file, name, isVideo)
    }

    private fun looksLikeVideo(name: String): Boolean =
        name.endsWith(".mp4", true) || name.endsWith(".mkv", true) ||
            name.endsWith(".avi", true) || name.endsWith(".mov", true) ||
            name.endsWith(".3gp", true) || name.endsWith(".webm", true)

    private fun restoreViaMediaStore(
        context: Context,
        file: File,
        name: String,
        isVideo: Boolean
    ): String? {
        return try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/*" else "image/*")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies" else "Pictures")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = context.contentResolver.insert(collection, values)
                ?: return "无法创建恢复条目"
            val out = context.contentResolver.openOutputStream(uri)
                ?: return "无法写入恢复目标"
            out.use { target ->
                file.inputStream().use { input -> input.copyTo(target) }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                runCatching {
                    context.contentResolver.update(
                        uri,
                        android.content.ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        },
                        null, null
                    )
                }
            }
            if (file.delete()) null else "已恢复但原副本删除失败"
        } catch (t: Throwable) {
            CoverDebugLog.log("Trash", "MediaStore 恢复失败: ${t::class.java.simpleName} ${t.message}")
            "恢复失败：${t.message ?: t::class.java.simpleName}"
        }
    }

    /** 彻底删除（不可恢复） */
    fun deletePermanently(file: File): Boolean =
        runCatching { file.delete() }.getOrDefault(false)

    private fun uniqueName(dir: File, displayName: String): String {
        val base = displayName.ifBlank { "media" }
        var candidate = "${System.currentTimeMillis()}_$base"
        var i = 1
        while (File(dir, candidate).exists()) {
            candidate = "${System.currentTimeMillis()}_${i}_$base"
            i++
        }
        return candidate
    }

    /**
     * 彻底清掉被移走那条媒体的 MediaStore 记录。
     *
     * 为什么必须做：文件 rename 走了，但数据库行还在 —— 相册重新查询照样把它列出来
     * （缩略图来自缓存，看起来完全没删）。这里三管齐下，并把每一步结果落盘，
     * 便于定位到底哪一步被系统拒绝：
     *   ① 通知媒体扫描器该**路径**已消失；
     *   ② 按 content URI 删行；
     *   ③ 按 DATA 路径删行（有"所有文件访问"时有效）。
     */
    private fun purgeRecord(context: Context, uri: Uri, path: String?) {
        if (path != null) scan(context, path)

        val byUri = runCatching { context.contentResolver.delete(uri, null, null) }
            .getOrDefault(-1)

        var byPath = -1
        if (path != null) {
            byPath = runCatching {
                context.contentResolver.delete(
                    MediaStore.Files.getContentUri("external"),
                    MediaStore.MediaColumns.DATA + "=?",
                    arrayOf(path)
                )
            }.getOrDefault(-1)
        }

        CoverDebugLog.log(
            "Trash",
            "清理记录: 扫描=${path != null} 按URI删=$byUri 按路径删=$byPath"
        )
    }

    private fun scan(context: Context, path: String) {
        runCatching {
            android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }
    }

    private fun queryRealPath(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.path
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
}
