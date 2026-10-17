package com.dsh.sportscam.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 一条媒体（照片或视频） */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    /** 拍摄时间（毫秒）；DATE_TAKEN 缺失时退回 DATE_ADDED*1000 */
    val timestampMs: Long,
    /** 视频时长（毫秒），照片为 0 */
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L
)

/** 按日期分组后的一组媒体 */
data class MediaGroup(val title: String, val items: List<MediaItem>)

/**
 * MediaStore 数据源。
 *
 * 相簿 #1：Images，路径包含 DCIM/Camera，按时间倒序。
 * 视频 #1：Video，全部视频，按时间倒序。
 */
object MediaRepository {

    private const val TAG = "MediaRepository"
    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 相机拍摄目录（RELATIVE_PATH 形如 "DCIM/Camera/"） */
    private const val CAMERA_DIR = "DCIM/Camera"

    suspend fun loadImages(context: Context): List<MediaItem> = withContext(Dispatchers.IO) {
        query(context, images = true).filterNot { HiddenMedia.isHidden(context, it.id, false) }
    }

    suspend fun loadVideos(context: Context): List<MediaItem> = withContext(Dispatchers.IO) {
        query(context, images = false).filterNot { HiddenMedia.isHidden(context, it.id, true) }
    }

    /** 按 id 取回（供「最近删除」列出被隐藏的项：它们仍是正常的 MediaStore 条目） */
    suspend fun loadByIds(context: Context, ids: Set<Long>, isVideo: Boolean): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext emptyList()
            query(context, images = !isVideo).filter { it.id in ids }
        }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    private fun query(context: Context, images: Boolean): List<MediaItem> {
        val collection = if (images) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.SIZE)
            // 用于过滤"文件已不存在"的失效记录
            add(MediaStore.MediaColumns.DATA)
            if (!images) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()

        // 相簿只取 DCIM/Camera 下的照片；视频全部展示
        val selection = if (images) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" else null
        val selectionArgs = if (images) arrayOf("$CAMERA_DIR%") else null

        var result = runQuery(context, collection, projection, selection, selectionArgs)
        Log.d(TAG, "images=$images relPath查询命中 ${result.size}")

        // 兼容：个别 ROM/相机写入 RELATIVE_PATH 为空，退回用 DATA 再查一次
        if (images && result.isEmpty()) {
            result = runQuery(
                context, collection, projection,
                "${MediaStore.MediaColumns.DATA} LIKE ?", arrayOf("%$CAMERA_DIR%")
            )
            Log.d(TAG, "DATA 回退查询命中 ${result.size}")
        }

        // 按时间倒序
        return result.sortedByDescending { it.timestampMs }
    }

    private fun runQuery(
        context: Context,
        collection: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?
    ): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
                ?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val takenIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    val addedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val durationIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
                    val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIdx)
                        val taken = cursor.longOrNull(takenIdx) ?: 0L
                        val added = cursor.longOrNull(addedIdx) ?: 0L
                        // DATE_TAKEN 是毫秒，DATE_ADDED 是秒
                        val timestamp = if (taken > 0L) taken else added * 1000L
                        // ★ 过滤掉"文件已经不在了"的失效记录：
                        //   之前删除失败（或文件被外部移走）时会留下这种记录，
                        //   表现为相册里还能看到、但点开打不开（用户反馈）。
                        val dataPath = cursor.stringOrNull(dataIdx)
                        if (dataPath != null && !java.io.File(dataPath).exists()) {
                            Log.d(TAG, "跳过失效记录: $dataPath")
                            continue
                        }
                        items.add(
                            MediaItem(
                                id = id,
                                uri = ContentUris.withAppendedId(collection, id),
                                displayName = cursor.stringOrNull(nameIdx) ?: "",
                                timestampMs = timestamp,
                                durationMs = cursor.longOrNull(durationIdx) ?: 0L,
                                sizeBytes = cursor.longOrNull(sizeIdx) ?: 0L
                            )
                        )
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "查询 MediaStore 失败: $collection", t)
        }
        return items
    }

    private fun Cursor.longOrNull(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)

    private fun Cursor.stringOrNull(index: Int): String? =
        if (index < 0 || isNull(index)) null else getString(index)

    // ------------------------------------------------------------------
    // 分组
    // ------------------------------------------------------------------

    /**
     * 按自然日倒序分组，标题规则：今天 / 昨天 / yyyy-MM-dd
     */
    fun groupByDate(
        items: List<MediaItem>,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<MediaGroup> {
        if (items.isEmpty()) return emptyList()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)

        return items
            .groupBy { item ->
                Instant.ofEpochMilli(item.timestampMs).atZone(zone).toLocalDate()
            }
            .toSortedMap(compareByDescending { it })
            .map { (date, list) ->
                val title = when (date) {
                    today -> "今天"
                    yesterday -> "昨天"
                    else -> date.format(DATE_FORMATTER)
                }
                MediaGroup(title, list.sortedByDescending { it.timestampMs })
            }
    }

    /** 时长格式化 m:ss */
    fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
