package com.dsh.sportscam.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 缩略图加载器。
 *
 * - 使用 ContentResolver.loadThumbnail()（API 29+）：系统内部有缩略图缓存，
 *   比 ThumbnailUtils 更省内存，也不会解码原图；
 * - 自己再套一层 LruCache，避免 LazyGrid 上下滚动时反复请求。
 *
 * 若后续需要更顺滑的体验，可把这里替换成 Glide / Coil，调用点无需改动。
 */
object ThumbnailLoader {

    private val cache: LruCache<Long, ImageBitmap> =
        object : LruCache<Long, ImageBitmap>(maxCacheBytes()) {
            override fun sizeOf(key: Long, value: ImageBitmap): Int =
                value.width * value.height * 4
        }

    private fun maxCacheBytes(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun cached(id: Long): ImageBitmap? = synchronized(cache) { cache.get(id) }

    fun clear() = synchronized(cache) { cache.evictAll() }

    /** 读取缩略图；失败返回 null（调用方显示占位图标） */
    suspend fun load(
        context: Context,
        id: Long,
        uri: Uri,
        widthPx: Int,
        heightPx: Int
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        cached(id)?.let { return@withContext it }
        try {
            val size = Size(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
            val bitmap: Bitmap = context.contentResolver.loadThumbnail(uri, size, null)
            val image = bitmap.asImageBitmap()
            synchronized(cache) { cache.put(id, image) }
            image
        } catch (t: Throwable) {
            // 文件被删除 / 解码失败 / 权限被撤销
            null
        }
    }

    /** 解码大图（全屏查看），限制最长边避免副屏 OOM */
    suspend fun loadFullImage(context: Context, uri: Uri, maxSize: Int = 2048): ImageBitmap? =
        withContext(Dispatchers.IO) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val longest = maxOf(w, h)
                    if (longest > maxSize && longest > 0) {
                        val ratio = maxSize.toFloat() / longest
                        decoder.setTargetSize(
                            (w * ratio).toInt().coerceAtLeast(1),
                            (h * ratio).toInt().coerceAtLeast(1)
                        )
                    }
                }
                bitmap.asImageBitmap()
            } catch (t: Throwable) {
                null
            }
        }
}
