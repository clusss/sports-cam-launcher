package com.dsh.sportscam.cover

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import java.io.File
import java.io.FileOutputStream

/**
 * 副屏截屏。
 *
 * **两条路，优先不需要 Shizuku 的那条：**
 *
 *  ① **无障碍截屏**（首选）：`AccessibilityService.takeScreenshot(displayId, …)`
 *     自 Android 11 (API 30) 起支持指定显示器，正好抓副屏；前提是本应用的
 *     无障碍服务已开启、且配置里有 `android:canTakeScreenshot="true"`。
 *     ⚠ 注意：改了该配置后，需要把无障碍服务**关掉再打开一次**，新能力才会生效。
 *
 *  ② **Shizuku `screencap`**（兜底）：无障碍不可用时才用。
 *     坑：`screencap -p -d <**逻辑** displayId>` 在这台机器上会产出 **0 字节** ✗，
 *     必须用 **SurfaceFlinger 的 display id**（`dumpsys SurfaceFlinger --display-id`
 *     里的大整数）。而且无法保证哪个 id 是副屏 → 逐 id 试拍 + 读 PNG 头部尺寸自校验
 *     （副屏 422×682 / 682×422，内屏 1080×2400）。
 *
 * 两条路都把结果存到标准相册目录并主动扫描，第三方相册可见。
 * 应用有「所有文件访问」权限，所以第 ① 条可以自己写文件，**完全不需要 shell**。
 */
object CoverScreenshot {

    private const val TAG = "Screenshot"

    /**
     * 截图保存目录 —— **默认**是系统截屏目录。
     *
     * 已在本机确认（2026-10）：这台 vivo 的系统截图就落在
     * `/sdcard/Pictures/Screenshots/`，命名形如 `Screenshot_20260924_224029.jpg`。
     * 本功能与系统截图**同目录、同命名规范**，相册里会归到同一个「截屏」相簿。
     *
     * ⚠ 第二轮起改为**可配置**：目录由「② 辅助与测试功能 → 截屏与录屏」那个界面
     *   （`ui/ScreenshotRecordScreen.kt`）读写，落在 [RecordSettings.screenshotDir]。
     *   两条通道（无障碍 / Shizuku 兜底）都必须用它，否则"改了保存位置却不生效"。
     */
    private fun outDir(context: Context): String = RecordSettings.screenshotDir(context)

    /** 与系统截图一致的命名前缀 */
    private const val NAME_PREFIX = "Screenshot_"

    /** 内屏尺寸，用于在 Shizuku 路径里排除 */
    private const val MAIN_W = 1080
    private const val MAIN_H = 2400

    sealed interface Result {
        /** @param via 走的是哪条通道（a11y / shizuku），便于排查 */
        data class Success(val path: String, val via: String) : Result
        data class Failure(val reason: String) : Result
    }

    private fun stamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())

    /**
     * 截取副屏当前画面。会阻塞（shell 往返 / 位图编码），调用方放到 IO 线程。
     */
    suspend fun capture(context: Context): Result {
        // 与系统截图**同目录、同命名规范**（目录可由用户在「截屏与录屏」里改）：
        //   /sdcard/Pictures/Screenshots/Screenshot_yyyyMMdd_HHmmss.png
        // 这样相册会把它归到和系统截图同一个「截屏」相簿里。
        val path = "${outDir(context)}/$NAME_PREFIX${stamp()}.png"

        // ① 无障碍截屏（不需要 Shizuku）
        captureViaAccessibility(context, path)?.let { return it }

        // ② Shizuku 兜底
        return captureViaShizuku(context, path)
    }

    // ------------------------------------------------------------------
    // ① 无障碍
    // ------------------------------------------------------------------

    private suspend fun captureViaAccessibility(context: Context, path: String): Result? {
        if (!CoverTriggerAccessibilityService.running) {
            CoverDebugLog.log(TAG, "无障碍服务未开启 → 改用 Shizuku 兜底")
            return null
        }
        val displayId = CoverDisplay.coverDisplayId(context)
        if (displayId == android.view.Display.DEFAULT_DISPLAY) return null

        val hw = CoverTriggerAccessibilityService.captureDisplay(displayId)
        if (hw == null) {
            CoverDebugLog.log(TAG, "无障碍截屏返回空 → 改用 Shizuku 兜底")
            return null
        }

        // 无障碍给的是 HARDWARE 位图，不能直接 compress，先转软件位图
        val soft = runCatching { hw.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        runCatching { hw.recycle() }
        if (soft == null) return Result.Failure("截屏位图转换失败")

        val ok = runCatching {
            File(path).parentFile?.mkdirs()
            FileOutputStream(File(path)).use {
                soft.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }.isSuccess
        runCatching { soft.recycle() }

        return if (ok) {
            scan(context, path)
            CoverDebugLog.log(TAG, "无障碍截屏成功 display=$displayId → $path")
            Result.Success(path, "a11y")
        } else {
            Result.Failure("无法写入 $path（是否缺少「所有文件访问」权限？）")
        }
    }

    // ------------------------------------------------------------------
    // ② Shizuku 兜底
    // ------------------------------------------------------------------

    private suspend fun captureViaShizuku(context: Context, path: String): Result {
        if (!ShizukuShell.isUsable) {
            return Result.Failure("无障碍与 Shizuku 都不可用（可开启无障碍服务后再试）")
        }

        val ids = surfaceFlingerDisplayIds()
        if (ids.isEmpty()) return Result.Failure("读不到 SurfaceFlinger 显示器列表")

        // 目录也用可配置的那个（否则用户改了保存位置，这条通道会写到老地方）
        val dir = outDir(context)
        File(dir).mkdirs()
        ShizukuShell.exec("mkdir -p '$dir'")

        for (id in ids) {
            val probe = "$dir/.probe_$id.png"
            ShizukuShell.exec("rm -f $probe")
            val out = ShizukuShell.exec("screencap -p -d $id $probe")
            val file = File(probe)

            if (!file.exists() || file.length() < 100) {
                file.delete()
                CoverDebugLog.log(TAG, "display $id 无有效输出（${out?.trim()?.take(60)}）")
                continue
            }
            val size = readPngSize(file) ?: run { file.delete(); null }
            if (size == null) {
                CoverDebugLog.log(TAG, "display $id 输出不是有效 PNG")
                continue
            }
            val (w, h) = size
            if ((w == MAIN_W && h == MAIN_H) || (h == MAIN_W && w == MAIN_H)) {
                file.delete()
                CoverDebugLog.log(TAG, "display $id = 内屏 ${w}x$h，跳过")
                continue
            }

            val target = File(path)
            target.parentFile?.mkdirs()
            val moved = file.renameTo(target) || runCatching { file.copyTo(target, true) }.isSuccess
            file.delete()
            if (moved) {
                scan(context, path)
                CoverDebugLog.log(TAG, "Shizuku 截屏成功 display=$id ${w}x$h → $path")
                return Result.Success(path, "shizuku")
            }
            return Result.Failure("无法写入 $path")
        }

        return Result.Failure("没找到副屏画面（可能副屏已息屏）")
    }

    private suspend fun surfaceFlingerDisplayIds(): List<String> {
        val dump = ShizukuShell.exec("dumpsys SurfaceFlinger --display-id") ?: return emptyList()
        return Regex("""Display\s+(\d+)""").findAll(dump).map { it.groupValues[1] }.toList()
    }

    /** 只读 PNG 头 24 字节拿宽高（IHDR，大端） */
    private fun readPngSize(file: File): Pair<Int, Int>? = runCatching {
        val head = ByteArray(24)
        file.inputStream().use { if (it.read(head) < 24) return null }
        val sig = intArrayOf(0x89, 0x50, 0x4E, 0x47)
        for (i in sig.indices) if ((head[i].toInt() and 0xFF) != sig[i]) return null
        fun be32(off: Int): Int =
            ((head[off].toInt() and 0xFF) shl 24) or
                ((head[off + 1].toInt() and 0xFF) shl 16) or
                ((head[off + 2].toInt() and 0xFF) shl 8) or
                (head[off + 3].toInt() and 0xFF)
        be32(16) to be32(20)
    }.getOrNull()

    private fun scan(context: Context, path: String) {
        runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(path), null, null)
        }
    }
}
