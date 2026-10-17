package com.dsh.sportscam.cover

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * ============================================================================
 *  自定义桌面壁纸的存储（第十轮第 4 项）
 * ============================================================================
 *
 * 桌面（`SquareHomeScreen`）只需要三个调用（签名**固定**，desktop-ui 已按此接线 ✓）：
 * ```kotlin
 * WallpaperStore.hasCustomWallpaper(context)   // 有没有自定义壁纸
 * WallpaperStore.load(context)                 // null = 用默认（当前风格）✓
 * WallpaperStore.reset(context)                // 恢复默认（删文件 + 清 flag）✓
 * ```
 * 编辑器（`ui/WallpaperEditorScreen`）额外用 [save] / [targetSize] / [queryAlbum] /
 * [loadAlbumBitmap] / [hasImageAccess] ✓。
 *
 * ## 关键设计
 * 1. **不用 SAF 系统选图器** ✗ —— 这台机器是"vivo X Flip 副屏改造机"，**主屏没有面板**：
 *    SAF 的 `ACTION_OPEN_DOCUMENT` 会把选图 UI 投到主屏 ⇒ 用户根本看不到、等于卡死 ✗
 *    （同一类坑见项目里"系统弹窗落到主屏"的多次记录 ✓）。
 *    所以选图完全**在应用内自建**：查 MediaStore 相册 → 自绘网格 → 点一张进编辑 ✓。
 * 2. 结果存**应用私有** `filesDir/wallpaper.jpg` ✓（JPEG 88 ✓，按副屏比例裁到 ≤1080 宽 ✓）：
 *    · 私有文件不需要任何存储权限 ✓；
 *    · 不需要 `MediaScanner` ✓（不进相册、不被媒体库索引 ✓）；
 *    · 尺寸/内存可控（桌面直接 `load` 就能用 ✓，不必再缩 ✓）。
 * 3. **解码一律采样**（[MAX_DECODE_EDGE]）✓ —— 相册原图动辄 4000×3000（48MB ARGB ✗），
 *    直接整张解码必 OOM ✗（与"照片旋转"那次同样的处理 ✓）。
 */
object WallpaperStore {

    private const val TAG = "Wallpaper"

    /** 独立 prefs：不碰 `cover_settings`（那是 settings-console 的文件 ✓，避免跨模块写同一个键 ✗） */
    private const val PREFS = "wallpaper_store"
    private const val KEY_HAS_CUSTOM = "has_custom_wallpaper"

    private const val FILE_NAME = "wallpaper.jpg"

    /** JPEG 质量：85~90 之间取 88（肉眼无损、体积可控 ✓） */
    private const val JPEG_QUALITY = 88

    /**
     * 目标尺寸：宽 1080、高按**副屏真实比例**算（默认 668 ≈ 682×422 的比例 ✓）。
     * 1080 是"够清晰但不浪费内存"的折中：ARGB 约 2.9MB ✓；桌面再放大到 682 宽绰绰有余 ✓。
     */
    private const val TARGET_WIDTH = 1080
    private const val FALLBACK_HEIGHT = 668

    /** 解码上限（最长边）：2048 足够 1080 宽的成品用，又能把 OOM 风险压住 ✓ */
    const val MAX_DECODE_EDGE = 2048

    // ==================================================================
    // 给桌面用的三个 API（签名固定 ✓）
    // ==================================================================

    /** 壁纸文件（调试/日志用；外部不要直接读它 ✓） */
    fun wallpaperFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * 有没有自定义壁纸。（文件真的存在且非空才算 ✓ —— 只信 flag 会在"文件被清"后返回 true ✗）
     */
    fun hasCustomWallpaper(context: Context): Boolean = runCatching {
        prefs(context).getBoolean(KEY_HAS_CUSTOM, false) && wallpaperFile(context).length() > 0L
    }.getOrDefault(false)

    /**
     * 载入自定义壁纸；**null = 用默认壁纸**（桌面据此回退到当前风格 ✓）。
     *
     * 采样解码（[MAX_DECODE_EDGE]）防 OOM ✓；文件损坏/读失败一律返回 null ✓（桌面回默认 ✓，
     * 绝不会因此崩 ✓）。
     */
    fun load(context: Context): Bitmap? = runCatching {
        val f = wallpaperFile(context)
        if (!f.exists() || f.length() <= 0L) {
            null
        } else {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                null
            } else {
                val sample = computeInSampleSize(opts.outWidth, opts.outHeight, MAX_DECODE_EDGE)
                val real = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeFile(f.absolutePath, real)
            }
        }
    }.getOrNull()

    /** 恢复默认壁纸：删文件 + 清 flag ✓（幂等 ✓，文件不存在也不报错 ✓） */
    fun reset(context: Context) {
        runCatching { wallpaperFile(context).delete() }
        runCatching { prefs(context).edit().putBoolean(KEY_HAS_CUSTOM, false).apply() }
        CoverDebugLog.log(TAG, "已恢复默认壁纸（删除自定义文件 + 清 flag ✓）")
    }

    // ==================================================================
    // 给编辑器用
    // ==================================================================

    /**
     * 目标画布尺寸：宽 [TARGET_WIDTH]，高按副屏真实宽高比算（拿不到副屏信息时用 668 ✓）。
     *
     * 为什么要"按副屏比例"而不是写死 1080×668：这台机器的副屏逻辑尺寸由系统给
     * （实测 682×422，约 1.616），桌面铺满时**比例必须一致**，否则会被拉伸/留黑边 ✗。
     */
    fun targetSize(context: Context): Pair<Int, Int> {
        val cover = runCatching { CoverDisplay.coverSizeOrNull(context) }.getOrNull()
        val w = cover?.x ?: 0
        val h = cover?.y ?: 0
        val height = if (w > 0 && h > 0) {
            (TARGET_WIDTH.toLong() * h / w).toInt().coerceIn(200, 2400)
        } else {
            FALLBACK_HEIGHT
        }
        return TARGET_WIDTH to height
    }

    /**
     * 保存编辑结果（**已经是最终构图**，这里只做"对齐目标比例 + 缩放 + 压 JPEG"✓）。
     *
     * 步骤：
     *  1. 中心裁剪到目标比例（编辑器一般已同比例 ⇒ 这一步基本是无损的 ✓）；
     *  2. 缩放到目标尺寸（`createScaledBitmap` 双线性 ✓）；
     *  3. 先写临时文件再 rename ✓ —— 中途断电/被杀时不会留下**半个 JPEG** ✗
     *     （桌面 `load` 读到半个文件会解码失败 ✓，虽然会回默认，但白丢用户的壁纸 ✗）。
     *
     * @return true=已保存（flag 已置位 ✓）
     */
    fun save(context: Context, bitmap: Bitmap): Boolean = runCatching {
        val (tw, th) = targetSize(context)
        val prepared = fitTo(bitmap, tw, th)
        val target = wallpaperFile(context)
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { out ->
            if (!prepared.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                error("JPEG 压缩失败")
            }
            out.flush()
        }
        if (tmp.length() <= 0L) error("写入 0 字节")
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) error("rename 失败")
        if (prepared !== bitmap) prepared.recycle()
        prefs(context).edit().putBoolean(KEY_HAS_CUSTOM, true).apply()
        CoverDebugLog.log(
            TAG,
            "保存自定义壁纸：${target.absolutePath} ${target.length()}B（${tw}x$th，质量 $JPEG_QUALITY）"
        )
        true
    }.onFailure {
        Log.w(TAG, "保存壁纸失败", it)
        CoverDebugLog.log(TAG, "保存壁纸失败：${it::class.java.simpleName} ${it.message}")
    }.getOrDefault(false)

    // ==================================================================
    // 相册读取（应用内选图：不弹 SAF ✗）
    // ==================================================================

    /**
     * 有没有读相册的权限。
     *
     * 优先级：
     *  1. Android 11+ 的「**所有文件访问**」（`MANAGE_EXTERNAL_STORAGE`）—— 本项目推荐路径 ✓
     *     （控制台里有一键跳转，用户可以手动授予 ✓）；
     *  2. 传统 `READ_EXTERNAL_STORAGE`（targetSdk 29 在 Android 13/14 上仍然生效 ✓）；
     *  3. Android 13+ 的 `READ_MEDIA_IMAGES`（万一系统按新权限模型走 ✓ 兜底）。
     *
     * ⚠ 这里**只检查、不申请** ✗：`requestPermissions` 会弹系统对话框，而这台机器主屏没面板 ✓
     *   ⇒ 弹窗落到主屏 = 用户看不见 = 卡住 ✗（很多轮的踩坑结论 ✓）。授权入口放在设置页 ✓。
     */
    fun hasImageAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val allFiles = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
            if (allFiles) return true
        }
        val legacy = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (legacy == android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        if (Build.VERSION.SDK_INT >= 33) {
            val media = context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
            if (media == android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        }
        return false
    }

    /** 相册里的一张图（只带够用的字段 ✓，不查大字段免得慢 ✗） */
    data class AlbumImage(val uri: Uri, val name: String, val bytes: Long)

    /**
     * 查相册图片（按添加时间倒序 ✓，默认最多 [limit] 张 ✓）。
     *
     * 任何异常（没权限/Provider 挂了/被安全软件拦）都返回**空列表** ✓ —— 编辑器据此显示
     * "相册里没有图片/需要权限"的提示 ✓，绝不崩 ✗。
     */
    fun queryAlbum(context: Context, limit: Int = 240): List<AlbumImage> = runCatching {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE
        )
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val out = ArrayList<AlbumImage>(minOf(limit, 60))
        context.contentResolver.query(collection, projection, null, null, order)?.use { c ->
            val iId = c.getColumnIndex(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val iSize = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            if (iId < 0) return@use
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(iId)
                out += AlbumImage(
                    uri = ContentUris.withAppendedId(collection, id),
                    name = if (iName >= 0) (c.getString(iName) ?: "") else "",
                    bytes = if (iSize >= 0) c.getLong(iSize) else 0L
                )
            }
        }
        out
    }.getOrElse {
        CoverDebugLog.log(TAG, "查相册失败：${it::class.java.simpleName} ${it.message}")
        emptyList()
    }

    /**
     * 解码相册图片（**采样**到最长边 ≤ [maxEdge] ✓，防止 4000×3000 的原图直接 OOM ✗）。
     *
     * 两趟解码：先只读尺寸（`inJustDecodeBounds` ✓，不解像素 ⇒ 几乎不耗内存 ✓），
     * 再按 [computeInSampleSize] 采样解码 ✓。
     */
    fun loadAlbumBitmap(context: Context, uri: Uri, maxEdge: Int = MAX_DECODE_EDGE): Bitmap? =
        runCatching {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            val real = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, real) }
        }.getOrElse {
            CoverDebugLog.log(TAG, "解码相册图片失败：${it::class.java.simpleName} ${it.message}")
            null
        }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 采样倍数：保证"最长边 / sample ≤ maxEdge" ✓（2 的幂，解码器最快 ✓） */
    private fun computeInSampleSize(w: Int, h: Int, maxEdge: Int): Int {
        var sample = 1
        var longest = maxOf(w, h)
        while (longest / 2 >= maxEdge) {
            longest /= 2
            sample *= 2
        }
        // 兜底：极端比例下再补一刀（避免 sample 太小导致仍超限 ✗）
        while (maxOf(w / sample, h / sample) > maxEdge) sample *= 2
        return sample
    }

    /** 中心裁剪到目标比例 + 缩放到目标尺寸 ✓（返回**新的** bitmap，调用方负责回收 ✓） */
    private fun fitTo(src: Bitmap, tw: Int, th: Int): Bitmap {
        val sw = src.width
        val sh = src.height
        val targetRatio = tw.toFloat() / th.toFloat()
        val srcRatio = sw.toFloat() / sh.toFloat()
        val cropW: Int
        val cropH: Int
        if (srcRatio > targetRatio) {
            cropH = sh
            cropW = (sh * targetRatio).toInt().coerceAtMost(sw).coerceAtLeast(1)
        } else {
            cropW = sw
            cropH = (sw / targetRatio).toInt().coerceAtMost(sh).coerceAtLeast(1)
        }
        val x = (sw - cropW) / 2
        val y = (sh - cropH) / 2
        val cropped = if (cropW == sw && cropH == sh) src else Bitmap.createBitmap(src, x, y, cropW, cropH)
        val scaled = if (cropped.width == tw && cropped.height == th) cropped
        else Bitmap.createScaledBitmap(cropped, tw, th, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        return scaled
    }
}
