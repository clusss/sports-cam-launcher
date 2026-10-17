package com.dsh.sportscam.cover

import android.content.Context
import android.media.MediaScannerConnection
import android.os.SystemClock
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 *  副屏录屏与截屏的**设置存储**
 * ============================================================================
 *
 * 为什么单独一个 object，而不是塞进 [CoverSettings]：
 *  · `CoverSettings.kt` 属于另一路改动（控制台/密码），本轮刻意**不碰**它的文件，
 *    避免两个队友同时编辑同一个文件；
 *  · 但 prefs **文件名仍然是 `cover_settings`** —— 备份/还原、清数据的行为与
 *    `CoverSettings` 完全一致，用户看不出区别。
 *
 * ⚠ 这里刻意**没有**复制「通知栏截屏按钮」的开关：那个开关的语义（以及它依赖
 *   无障碍服务、服务断开时自动关闭）都归 [CoverSettings.isScreenshotEnabled]，
 *   本界面只新增"**保存位置**"这一项，两者语义保持一致。
 */
object RecordSettings {

    /** 与 [CoverSettings] 共用同一个 SharedPreferences 文件 */
    private const val PREFS = "cover_settings"

    private const val KEY_RECORD_ENABLED = "record_enabled"
    private const val KEY_RECORD_DIR = "record_out_dir"

    /** 旧的"帧率档位"键（30/60）—— 第四轮改成码率档，仅用于**兼容迁移**，见 [bitRateMbps] */
    private const val KEY_RECORD_FRAME_TIER_LEGACY = "record_frame_tier"

    /** 新的"码率档位"键（Mbps） */
    private const val KEY_RECORD_BITRATE_MBPS = "record_bitrate_mbps"

    /**
     * 「录制麦克风」开关（**第四轮新增，是真的**）。
     *
     * 与"应用内声音"是两回事：
     *  · 麦克风：`MediaRecorder` + `AudioSource.MIC` 录进一个临时 m4a，
     *    结束录屏后与视频**在机内合并**（MediaExtractor + MediaMuxer，见 [CoverScreenRecorder.muxMic]）✓；
     *  · 应用内声音：本机**做不到**（`screenrecord` 无任何音频参数；`MediaProjection` 的
     *    AudioPlaybackCapture 只能跟默认显示器绑定）✗ —— 界面上必须写清楚，不能让人误会。
     */
    private const val KEY_RECORD_MIC = "record_mic_enabled"

    private const val KEY_SHOT_DIR = "screenshot_out_dir"

    /** 正在录制的状态（进程被杀也能知道"有个录制没正常收尾"） */
    private const val KEY_ACTIVE_PATH = "record_active_path"
    private const val KEY_ACTIVE_PID_FILE = "record_active_pid_file"
    private const val KEY_ACTIVE_PHYS = "record_active_phys_id"
    private const val KEY_ACTIVE_STARTED_AT = "record_active_started_at"

    /** 默认录制目录：相册「影片」里能直接看到 */
    const val DEFAULT_RECORD_DIR = "/sdcard/Movies"

    /** 默认截屏目录：与系统截屏**同目录、同相簿**（原 CoverScreenshot 的语义，不变） */
    const val DEFAULT_SCREENSHOT_DIR = "/sdcard/Pictures/Screenshots"

    /** 录制目录候选（UI 里做成芯片，不做自由输入 —— 副屏上打字很难） */
    val RECORD_DIR_OPTIONS = listOf(
        DEFAULT_RECORD_DIR,
        "/sdcard/DCIM/Screenrecords",
        "/sdcard/DCIM/Camera"
    )

    /** 截屏目录候选 */
    val SCREENSHOT_DIR_OPTIONS = listOf(
        DEFAULT_SCREENSHOT_DIR,
        "/sdcard/DCIM/Screenshots",
        "/sdcard/Pictures"
    )

    /**
     * 码率档位（Mbps）—— 第四轮把"帧率档(30/60)"改成**码率档**。
     *
     * ⚠ 为什么不能给用户一个"帧率"选项：`screenrecord`（本机 v1.3）**没有**指定帧率或
     *   codec profile 的参数（`--help` 只有 size / bit-rate / time-limit / display-id /
     *   verbose / bugreport）→ 帧率由副屏刷新率（60Hz）与**画面有没有变化**决定，
     *   写成"30/60 帧档"就是在骗用户 ✗。码率是**真实可控**的那个旋钮 ✓。
     *
     * 真机实测（2026-10-11，同一段滑动动作各录 ~7s，产物用 mp4 box 解析）：
     *   `--bit-rate 4M`  → 524KB / 370 帧 / 52.5fps / **实际 0.60Mbps**
     *   `--bit-rate 8M`  → 691KB / 363 帧 / 53.9fps / **实际 0.82Mbps**
     *   `--bit-rate 16M` → 1284KB / 390 帧 / 54.6fps / **实际 1.44Mbps**
     *   `--bit-rate 32M` → 1204KB / 364 帧 / 54.0fps / **实际 1.43Mbps**
     * 结论：`--bit-rate` 是**上限**，简单画面用不满；8M→16M 确实让产物翻倍（画质更好 ✓），
     *   16M→32M 在这种内容上已经饱和（提升可忽略）。所以默认 8M，给 16M/32M 兜底高细节场景。
     *
     * 682×422@60fps = 17.3M 像素/秒 ⇒ 8Mbps ≈ **0.46 bit/像素**（偏低，动起来会有块状糊），
     * 16Mbps ≈ 0.92、32Mbps ≈ 1.85 → 这就是"8M 觉得糊"的量化解释 ✓。
     */
    val BITRATE_TIERS_MBPS = listOf(4, 8, 16, 32)

    /** 默认码率：8Mbps（实测对本机副屏内容够用；嫌糊就上 16M） */
    const val DEFAULT_BITRATE_MBPS = 8

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 录屏 ----------------

    /** 是否在通知栏显示录屏按钮（默认**关**：用户不主动开就不该出现） */
    fun isRecordEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RECORD_ENABLED, false)

    fun setRecordEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_RECORD_ENABLED, value).apply()
    }

    /** 录屏保存目录 */
    fun recordDir(context: Context): String =
        prefs(context).getString(KEY_RECORD_DIR, DEFAULT_RECORD_DIR)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_RECORD_DIR

    fun setRecordDir(context: Context, value: String) {
        prefs(context).edit().putString(KEY_RECORD_DIR, value).apply()
    }

    /**
     * 码率档位（Mbps）—— 带**旧值兼容**。
     *
     * 迁移规则（第四轮）：
     *   · 新键 `record_bitrate_mbps` 有值且在档位表里 → 用它；
     *   · 否则读旧键 `record_frame_tier`（30/60）：30→4Mbps、60→8Mbps（保持用户原来的观感），
     *     并**顺手写回新键**（只做一次）；
     *   · 都没有 → 默认 8Mbps。
     */
    fun bitRateMbps(context: Context): Int {
        val p = prefs(context)
        val v = p.getInt(KEY_RECORD_BITRATE_MBPS, -1)
        if (BITRATE_TIERS_MBPS.contains(v)) return v

        val legacy = p.getInt(KEY_RECORD_FRAME_TIER_LEGACY, -1)
        val migrated = when (legacy) {
            30 -> 4
            60 -> 8
            else -> DEFAULT_BITRATE_MBPS
        }
        p.edit().putInt(KEY_RECORD_BITRATE_MBPS, migrated).apply()
        CoverDebugLog.log(
            "Rec",
            "码率档位迁移：旧 frame_tier=$legacy → ${migrated}Mbps（写回新键）"
        )
        return migrated
    }

    fun setBitRateMbps(context: Context, value: Int) {
        val v = if (BITRATE_TIERS_MBPS.contains(value)) value else DEFAULT_BITRATE_MBPS
        prefs(context).edit().putInt(KEY_RECORD_BITRATE_MBPS, v).apply()
    }

    /** 档位 → `--bit-rate`（bps） */
    fun bitRate(context: Context): Int = bitRateMbps(context) * 1_000_000

    /**
     * ⚠ "应用内声音"仍然**不支持**（第四轮第 9 项复核结论）：
     *   · `screenrecord --help` 里**一条音频参数都没有**，`--audio` / `--audio-source` /
     *     `--mic` 实测全部 `unrecognized option`；
     *   · `MediaProjection` 的 AudioPlaybackCapture 也不行 —— 它必须由 MediaProjection 构造，
     *     而 MediaProjection 只能捕获**默认显示器**（副屏不是默认屏）✗。
     * 界面上写明"录不到应用内声音"，绝不摆假开关。
     *
     * 麦克风则是**真的能录**（见 [KEY_RECORD_MIC]）：MediaRecorder 与显示器无关 ✓。
     */
    fun isMicEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RECORD_MIC, false)

    fun setMicEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_RECORD_MIC, value).apply()
    }

    // ---------------- 截屏 ----------------

    /** 截屏保存目录（[CoverScreenshot] 从这里读；默认与系统截屏同目录） */
    fun screenshotDir(context: Context): String =
        prefs(context).getString(KEY_SHOT_DIR, DEFAULT_SCREENSHOT_DIR)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_SCREENSHOT_DIR

    fun setScreenshotDir(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SHOT_DIR, value).apply()
    }

    // ---------------- 正在录制的状态 ----------------

    fun activePath(context: Context): String =
        prefs(context).getString(KEY_ACTIVE_PATH, "") ?: ""

    fun activePidFile(context: Context): String =
        prefs(context).getString(KEY_ACTIVE_PID_FILE, "") ?: ""

    fun activePhysId(context: Context): Long =
        prefs(context).getLong(KEY_ACTIVE_PHYS, 0L)

    fun activeStartedAt(context: Context): Long =
        prefs(context).getLong(KEY_ACTIVE_STARTED_AT, 0L)

    fun markRecording(context: Context, path: String, pidFile: String, physId: Long) {
        prefs(context).edit()
            .putString(KEY_ACTIVE_PATH, path)
            .putString(KEY_ACTIVE_PID_FILE, pidFile)
            .putLong(KEY_ACTIVE_PHYS, physId)
            .putLong(KEY_ACTIVE_STARTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearRecording(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACTIVE_PATH)
            .remove(KEY_ACTIVE_PID_FILE)
            .remove(KEY_ACTIVE_PHYS)
            .remove(KEY_ACTIVE_STARTED_AT)
            .apply()
    }
}

/**
 * ============================================================================
 *  副屏录屏（Shizuku `screenrecord --display-id <物理 id>`）
 * ============================================================================
 *
 * ## 为什么必须走 Shizuku
 * 副屏（displayId=1）**不是默认显示器**，`MediaProjection` 只录默认屏 ✗；
 * 唯一可行的通道是 shell 的 `screenrecord --display-id`（普通应用没有 shell 权限）。
 * 所以：**没有 Shizuku 就没有录屏** —— 通知栏按钮直接隐藏，界面写明原因，绝不静默失败。
 *
 * ## 真机实测结论（vivo V2256A / 2026-10，全部可复现）
 *
 * 1. **`--display-id` 要的是 SurfaceFlinger 的物理 display id，不是逻辑 displayId** ✗
 *    `screenrecord --display-id 1 …` → `Invalid physical display ID`。
 *    本机映射（不能硬编码，运行时算，见 [coverPhysicalDisplayId]）：
 *      · `dumpsys display` 的 mViewports 行：`displayId=1 … physicalPort=131`
 *      · `dumpsys SurfaceFlinger --display-id`：`Display 4630946993847360387 (HWC display 3): port=131`
 *      → 用 **port** 对上：副屏物理 id = 4630946993847360387，录出来 **682x422**
 *      （正好是副屏物理分辨率 ✓）；主屏 = 4630947227182689922 → 1080x2400。
 *    ⚠ 副屏的 `uniqueId` 是 `local:secondary`（**不是** local:<物理 id>），
 *      所以"从逻辑 id 推物理 id"这条捷径在这台机器上不成立，必须走 port。
 *
 * 2. **结束必须发 SIGINT，绝不能 kill（SIGTERM）** ★★
 *    实测：`kill -TERM` 之后文件 **3232 字节、没有 moov/avc1，播放器打不开** ✗；
 *    同一场 `kill -INT` 之后 **23300 字节、含 moov+mdat+avc1，正常播放** ✓。
 *    `screenrecord` 只在收到 **SIGINT** 时走正常收尾（写 moov）。所以：
 *      · 结束 = `kill -INT <pid>`（pid 在开始时由 shell 写进 pid 文件）；
 *      · **不要**用 `Process.destroy()` / `pkill`（默认 SIGTERM）来收尾。
 *    （`ShizukuShell.exec` 也不能用来起 screenrecord：它会 readText 到进程结束，
 *      等于把整个录制过程阻塞住 ✗ —— 所以这里自己反射 newProcess，且不读它的输出。）
 *
 * 3. **录制中途文件不会长**：`screenrecord` 先把 mp4 头写出来（约 3KB），
 *    帧数据在收尾时统一落盘 —— 所以"开始后 1.2s 文件存在且 >0"只用来判断
 *    进程是否正常起来，**不能**用它判断录制是否有效。
 *
 * 4. **`--audio` 不存在**（`unrecognized option`）→ 麦克风置灰，见 [RecordSettings]。
 *
 * 5. 录制期间 **不能锁屏/切走太多**：`screenrecord` 受系统限制，
 *    息屏或切到别的显示器后录到的可能是黑屏 —— 界面文案里已提醒用户。
 *
 * ## 进程模型（为什么要有"崩溃后收尾"）
 * `Shizuku.newProcess` 起出来的 sh/screenrecord 挂在 **Shizuku 服务进程**下，
 * 本应用进程被杀 **不会**杀掉它 → 会留下一个永远不停、越来越大、没有 moov 的文件。
 * 所以开始录制时把 path/pidFile 落盘，[recoverStaleRecording] 在保活服务启动时
 * 收尾（SIGINT + 等 1.5s + 扫描）。
 */
object CoverScreenRecorder {

    private const val TAG = "Rec"

    /** 记录 screenrecord 的 pid（`$!`）；/data/local/tmp 对 shell 可读写（实测） */
    private const val PID_FILE = "/data/local/tmp/sportscam_screenrecord.pid"

    /** screenrecord 的 stderr（启动失败时用来说明原因，比 /dev/null 有用） */
    private const val ERR_FILE = "/data/local/tmp/sportscam_screenrecord.err"

    /** 收尾后等文件写完 moov 的时间（任务书要求 1~2s；实测 1.5s 足够） */
    private const val FINALIZE_WAIT_MS = 1_500L

    /** 等 screenrecord 进程**真正消失**的上限（实测 0.85~1.23s） */
    private const val PROCESS_GONE_TIMEOUT_MS = 6_000L

    /** 等文件收尾完成（moov 出现 + 大小稳定）的上限 */
    private const val FINALIZE_TIMEOUT_MS = 8_000L

    /** 收尾轮询间隔 */
    private const val FINALIZE_POLL_MS = 250L

    /** 扫描后补扫一次的间隔（MediaScannerConnection.scanFile 是异步的） */
    private const val RESCAN_DELAY_MS = 800L

    /** 判断 moov 时从文件尾部往回读的字节数（实测 screenrecord 的 moov 在文件末尾） */
    private const val MOOV_SCAN_BYTES = 512L * 1024L

    /** 起进程后校验"文件是否出现"的等待 */
    private const val START_VERIFY_WAIT_MS = 1_200L

    // ---------------- 麦克风（第四轮新增：单独录一路，结束再合并） ----------------

    /** 麦克风临时文件（放**应用内部缓存目录**：不污染相册、失败也不丢用户视频） */
    private const val MIC_TEMP_NAME = "sportscam_mic.m4a"

    /** 合并产物临时文件 */
    private const val MERGED_TEMP_NAME = "sportscam_merged.mp4"

    /** 合并时读 sample 的缓冲区上限（一般几 KB；给足 4MB 防大 sample 抛异常） */
    private const val MUX_SAMPLE_BUF = 4 * 1024 * 1024

    /** 麦克风录音参数（语音/环境声够用，体积小） */
    private const val MIC_SAMPLE_RATE = 44_100
    private const val MIC_BITRATE = 96_000

    /** 麦克风录制器（null = 本次没在录麦克风） */
    @Volatile
    private var micRecorder: android.media.MediaRecorder? = null

    /** 麦克风临时文件（录制中） */
    @Volatile
    private var micFile: File? = null

    /** 麦克风启动时刻 / 视频启动时刻（elapsedRealtime，用于音视频对齐） */
    @Volatile
    private var micStartedElapsed = 0L

    @Volatile
    private var videoStartedElapsed = 0L

    /** 是否正在"合并音视频"（通知栏据此显示处理中，不假装已完成） */
    @Volatile
    var isMuxing: Boolean = false
        private set

    /** 当前正在录制的文件（内存态，供通知栏立刻切 ▶/■） */
    @Volatile
    var activePath: String? = null
        private set

    /** 最近一次失败原因（界面/日志用） */
    @Volatile
    var lastError: String? = null
        private set

    /** 开始时拿到的 shell 进程句柄（收尾后 destroy 掉，不留僵尸 sh） */
    private var activeProcess: Process? = null

    fun isRecording(): Boolean = activePath != null

    /** 供通知栏等无状态场景判断（进程重启后内存态没了，回落到落盘状态） */
    fun isRecording(context: Context): Boolean =
        activePath != null || RecordSettings.activePath(context).isNotEmpty()

    sealed interface StartResult {
        data class Ok(val path: String, val physId: Long, val bitRate: Int) : StartResult
        data class Failure(val reason: String) : StartResult
    }

    sealed interface StopResult {
        /**
         * @param note 给用户看的补充说明（例如"已含麦克风声音（音视频已合并）"/
         *             "自动合并失败，已分别保存：xxx.m4a"）
         */
        data class Ok(val path: String, val sizeBytes: Long, val note: String = "") : StopResult
        data class Failure(val reason: String) : StopResult
    }

    // ------------------------------------------------------------------
    // 物理 display id 映射
    // ------------------------------------------------------------------

    @Volatile
    private var cachedPhysId = 0L

    @Volatile
    private var cachedPhysAt = 0L

    private val viewportPattern =
        Regex("""displayId=(\d+),\s*uniqueId='[^']*',\s*physicalPort=(\d+)""")

    private val surfaceFlingerPattern =
        Regex("""Display\s+(\d+)\s+\(HWC display (\d+)\):\s*port=(\d+)""")

    /**
     * 副屏的 SurfaceFlinger **物理** display id（拿不到返回 null）。
     *
     * 两个 dumpsys 对照取 `port`：
     *  ① `dumpsys display` 的 mViewports 行 → 逻辑 displayId → physicalPort；
     *  ② `dumpsys SurfaceFlinger --display-id` → 物理 id ↔ port。
     * 找不到就**不猜**（返回 null，由调用方给出可读的失败原因）。
     */
    suspend fun coverPhysicalDisplayId(context: Context, forceRefresh: Boolean = false): Long? {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedPhysId > 0 && now - cachedPhysAt < 60_000L) return cachedPhysId

        val coverLogical = CoverDisplay.coverDisplayId(context)
        if (coverLogical == Display.DEFAULT_DISPLAY) return null

        val viewports = ShizukuShell.exec("dumpsys display | grep -m1 mViewports") ?: return null
        val ports = viewportPattern.findAll(viewports)
            .mapNotNull { m ->
                val id = m.groupValues[1].toIntOrNull()
                val port = m.groupValues[2].toIntOrNull()
                if (id == null || port == null) null else id to port
            }.toList()

        val coverPort = ports.firstOrNull { it.first == coverLogical }?.second
        val defaultPort = ports.firstOrNull { it.first == Display.DEFAULT_DISPLAY }?.second

        val sf = ShizukuShell.exec("dumpsys SurfaceFlinger --display-id") ?: return null
        val displays = surfaceFlingerPattern.findAll(sf)
            .mapNotNull { m ->
                val phys = m.groupValues[1].toLongOrNull()
                val hwc = m.groupValues[2].toIntOrNull()
                val port = m.groupValues[3].toIntOrNull()
                if (phys == null) null else Triple(phys, hwc ?: -1, port ?: -1)
            }.toList()
        if (displays.isEmpty()) return null

        // 1) 首选：port 精确对上
        var phys = coverPort?.let { p -> displays.firstOrNull { it.third == p }?.first }
        var via = "port=$coverPort"

        // 2) 兜底：拿不到副屏 port 时，用"不是主屏那个 port"的那块
        if (phys == null && defaultPort != null) {
            val others = displays.filter { it.third != defaultPort }
            if (others.size == 1) {
                phys = others.first().first
                via = "非主屏 port=$defaultPort"
            }
        }

        // 3) 再兜底：HWC display index 非 0 的那块（HWC 0 通常是主屏）
        if (phys == null) {
            val others = displays.filter { it.second != 0 }
            if (others.size == 1) {
                phys = others.first().first
                via = "HWC index!=0"
            }
        }

        if (phys == null) {
            CoverDebugLog.log(
                TAG,
                "物理 display id 映射失败：逻辑副屏=$coverLogical " +
                    "viewportPorts=$ports SF=$displays"
            )
            return null
        }

        cachedPhysId = phys
        cachedPhysAt = now
        CoverDebugLog.log(
            TAG,
            "副屏物理 display id=$phys（逻辑 id=$coverLogical，映射途径=$via）"
        )
        return phys
    }

    // ------------------------------------------------------------------
    // 开始
    // ------------------------------------------------------------------

    suspend fun start(context: Context): StartResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext

        if (isRecording(app)) {
            lastError = "已经在录屏了"
            return@withContext StartResult.Failure("已经在录屏了")
        }
        if (!ShizukuShell.isUsable) {
            val why = "Shizuku 未连接/未授权：screenrecord 需要 shell 权限，普通应用拿不到"
            lastError = why
            CoverDebugLog.log(TAG, "开始失败：$why")
            return@withContext StartResult.Failure(why)
        }

        val coverId = CoverDisplay.coverDisplayId(app)
        if (coverId == Display.DEFAULT_DISPLAY) {
            lastError = "找不到副屏"
            return@withContext StartResult.Failure("找不到副屏")
        }

        // 副屏必须亮着：熄屏时 screenrecord 录出来是黑屏
        val dm = app.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
        val on = runCatching { dm?.getDisplay(coverId)?.state == Display.STATE_ON }.getOrDefault(false)
        if (!on) {
            lastError = "副屏当前未亮屏（熄屏时录出来是黑屏）"
            return@withContext StartResult.Failure("副屏当前未亮屏，请先点亮再录")
        }

        val phys = coverPhysicalDisplayId(app)
        if (phys == null) {
            val why = "拿不到副屏的物理 display id（screenrecord 只认 SurfaceFlinger 的物理 id；" +
                "逻辑 id=$coverId 传进去会报 Invalid physical display ID）"
            lastError = why
            CoverDebugLog.log(TAG, "开始失败：$why")
            return@withContext StartResult.Failure(why)
        }

        val dir = RecordSettings.recordDir(app)
        val path = "$dir/Screenrecord_${stamp()}.mp4"
        val rate = RecordSettings.bitRate(app)

        // 目录先建好（shell 有 sdcard 写权限；应用自己有 MANAGE_EXTERNAL_STORAGE 但那是另一条路）
        ShizukuShell.exec("mkdir -p '$dir'")
        ShizukuShell.exec("rm -f '$PID_FILE' '$ERR_FILE'")

        // 收起通知栏：点通知栏按钮时面板是打开的，不收起来会把面板录进开头几秒
        // （截屏那条路是用透明 Activity 自动收起的，这里用 shell 的 statusbar collapse）
        runCatching { ShizukuShell.exec("cmd statusbar collapse") }

        // ---------------------------------------------------------------
        // ★ 第四轮新增：**先启麦克风，再启录屏**（顺序不能反 —— 见 muxMic 的对齐说明）
        // ---------------------------------------------------------------
        val micWanted = RecordSettings.isMicEnabled(app)
        val micGranted = isMicAvailable(app)
        if (micWanted && !micGranted) {
            // ⚠ 绝不在这里弹系统权限框：这台机器主屏没有面板，弹窗用户根本看不见 ✗。
            //   权限由装机时 `adb shell pm grant … RECORD_AUDIO` 授予；没给就只录画面。
            CoverDebugLog.log(
                TAG,
                "未授予 RECORD_AUDIO → 本次仅录画面（不弹系统权限框；" +
                    "需要麦克风请在装机时 pm grant cn.pashiguoke.subscreen RECORD_AUDIO）"
            )
        }
        val micOk = micWanted && micGranted
        var micTimestampLogged = false
        if (micOk) {
            val ok = runCatching { startMic(app) }.getOrDefault(false)
            if (!ok) {
                CoverDebugLog.log(TAG, "麦克风启动失败 → 本次仅录画面（原因见上一条）")
            } else {
                micTimestampLogged = true
                CoverDebugLog.log(
                    TAG,
                    "麦克风已启动（MIC/AAC ${MIC_BITRATE / 1000}kbps）→ ${micFile?.absolutePath}"
                )
            }
        }

        val cmd = "screenrecord --display-id $phys --time-limit 0 --bit-rate $rate '$path' " +
            "> /dev/null 2> '$ERR_FILE' & echo \$! > '$PID_FILE'; wait"
        val process = newProcessDetached(cmd)
        if (process == null) {
            // 录屏都没起来：把麦克风收掉，别留个孤儿录音
            stopMic(saveIfFailed = false)
            val why = "Shizuku 起进程失败（newProcess 返回空）"
            lastError = why
            CoverDebugLog.log(TAG, "开始失败：$why")
            return@withContext StartResult.Failure(why)
        }
        // 视频启动时刻：进程已经 spawn（这是最接近"第一帧"的可用时刻）
        videoStartedElapsed = SystemClock.elapsedRealtime()
        if (micTimestampLogged) {
            CoverDebugLog.log(
                TAG,
                "音视频启动偏移：麦克风比录屏早 ${videoStartedElapsed - micStartedElapsed}ms" +
                    "（合并时按这个偏移对齐；几十~两百毫秒都算正常）"
            )
        }

        // 校验：文件出现（约 3KB 的 mp4 头）才认为进程真的起来了
        delay(START_VERIFY_WAIT_MS)
        val file = File(path)
        val size = if (file.exists()) file.length() else 0L
        if (size <= 0L) {
            val err = ShizukuShell.exec("cat '$ERR_FILE'")?.trim().orEmpty()
            // 收尾要发 SIGINT（不是 destroy：SIGTERM 会留下没有 moov 的坏文件）
            sigintByPidFile()
            if (runningScreenrecordPids().isNotEmpty()) {
                ShizukuShell.exec("pkill -INT -x screenrecord")
            }
            runCatching { process.destroy() }
            stopMic(saveIfFailed = false)
            val why = "screenrecord 没有产出文件" +
                if (err.isEmpty()) "（无 stderr 输出）" else "：${err.take(160)}"
            lastError = why
            CoverDebugLog.log(TAG, "开始失败：$why")
            return@withContext StartResult.Failure(why)
        }

        activeProcess = process
        activePath = path
        lastError = null
        RecordSettings.markRecording(app, path, PID_FILE, phys)
        CoverDebugLog.log(
            TAG,
            "开始录屏：phys=$phys bitRate=$rate（${RecordSettings.bitRateMbps(app)}Mbps）→ $path" +
                "（麦克风=${if (micRecorder != null) "开" else "关"}，" +
                "pidFile=$PID_FILE，结束时发 SIGINT）"
        )
        // 通知栏立刻切成 ■
        withContext(Dispatchers.Main) { OrientationNotification.showIfEnabled(app, "录屏状态变化") }
        StartResult.Ok(path, phys, rate)
    }

    // ------------------------------------------------------------------
    // 结束
    // ------------------------------------------------------------------

    suspend fun stop(context: Context): StopResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val remembered = RecordSettings.activePath(app)
        val path = activePath ?: remembered.ifEmpty { null }
            ?: return@withContext StopResult.Failure("当前没有在录屏")

        if (!ShizukuShell.isUsable) {
            val why = "Shizuku 未连接/未授权：无法给 screenrecord 发结束信号（SIGINT）"
            lastError = why
            CoverDebugLog.log(TAG, "结束失败：$why")
            return@withContext StopResult.Failure(why)
        }

        // ★★ 收尾必须是 SIGINT（见类注释第 2 条实测证据）。
        var signalled = sigintByPidFile()
        var left = runningScreenrecordPids()
        if (left.isNotEmpty()) {
            // pid 文件丢了/对不上：按进程名兜底（-x 精确匹配，避免匹配到 pkill/sh 自己）
            ShizukuShell.exec("pkill -INT -x screenrecord")
            delay(400)
            left = runningScreenrecordPids()
        }
        CoverDebugLog.log(
            TAG,
            "发 SIGINT：pidFile 命中=$signalled 剩余 screenrecord 进程=[$left]"
        )
        if (left.isNotEmpty()) {
            // 最后手段：SIGINT 不奏效时宁可留下一个不完整的文件，也不能永远录下去
            //（这种情况极罕见，出现说明 ROM 改了信号处理，日志里留证据）
            CoverDebugLog.log(TAG, "SIGINT 未生效（剩余=$left）→ 退化为 SIGTERM 强杀（文件可能缺 moov）")
            ShizukuShell.exec("pkill -x screenrecord")
            delay(300)
        }

        // ★ 第四轮第 5 项：**等收尾真正完成**再扫描，而不是死等 1.5s 就报"已入库"。
        //   真机实测（2026-10-11，3s/5s/10s 三档 + 含滑动）：
        //     · SIGINT 后进程在 **0.85~1.23s** 内退出，退出时 moov 已写完；
        //     · 文件在收尾过程中大小不变（`screenrecord` 是"先写 ftyp+mdat、收尾补 moov"），
        //       所以"大小稳定"必须以 **moov 出现**为准，不能只看大小 ✗。
        //   于是这里：等进程消失 → 等 moov（+ 大小稳定）→ 扫描 → 再补扫一次。
        val waitedGone = waitProcessGone(PROCESS_GONE_TIMEOUT_MS)
        // 收尾后把 shell 包装进程收掉（它只是 `wait`，不再影响已经退出/收尾中的 screenrecord）
        activeProcess?.let { runCatching { it.destroy() } }
        activeProcess = null

        val ready = waitFileFinalized(path, FINALIZE_TIMEOUT_MS)
        val file = File(path)
        val size = if (file.exists()) file.length() else 0L

        // ---------------------------------------------------------------
        // ★ 第四轮新增：**先收视频（已收完）→ 再停麦克风 → 机内合并**
        // ---------------------------------------------------------------
        // 顺序说明：麦克风要比视频晚停一点点（避免视频尾部丢声音），
        // 而且"视频侧已确认 moov 就绪"之后再停麦克风最稳。
        val micOutcome = if (micRecorder != null) {
            val micPath = stopMic(saveIfFailed = false)   // 保留临时 m4a，交给她下面去合并/兜底
            if (micPath == null) {
                MicOutcome(result = "麦克风没有产出有效音频（停止失败或文件为空）", merged = false, micKept = null)
            } else {
                micOutcome(app, path, micPath, size)
            }
        } else {
            MicOutcome(result = "", merged = false, micKept = null)
        }

        if (size > 0L) {
            runCatching {
                MediaScannerConnection.scanFile(app, arrayOf(path), null, null)
            }
            // 兜底再扫一次：MediaScannerConnection.scanFile 是**异步**的，
            // 第一次可能在文件刚落地时被跳过；隔一会儿补一次，失败也无副作用 ✓
            delay(RESCAN_DELAY_MS)
            runCatching {
                MediaScannerConnection.scanFile(app, arrayOf(path), null, null)
            }
        }

        activePath = null
        RecordSettings.clearRecording(app)
        // 清掉临时文件（shell 侧的 pid/err）
        runCatching { ShizukuShell.exec("rm -f '$PID_FILE' '$ERR_FILE'") }
        // 通知栏切回 ▶
        withContext(Dispatchers.Main) { OrientationNotification.showIfEnabled(app, "录屏状态变化") }

        if (size <= 0L) {
            val why = "结束信号已发，但文件为空/不存在：$path"
            lastError = why
            CoverDebugLog.log(TAG, why)
            StopResult.Failure(why)
        } else {
            val finalSize = runCatching { File(path).length() }.getOrDefault(size)
            CoverDebugLog.log(
                TAG,
                "结束录屏：$path（${finalSize / 1024}KB，等待进程退出 ${waitedGone}ms、" +
                    "${if (ready.moov) "moov 已就绪" else "moov 未确认"} 用时 ${ready.elapsedMs}ms，" +
                    "已扫描入库并补扫一次）${micOutcome.result}"
            )
            StopResult.Ok(path, finalSize, micOutcome.result)
        }
    }

    /**
     * 保活服务启动时收尾"上一次没正常结束的录制"。
     *
     * 场景：本应用进程被杀（或崩溃）时 screenrecord 还挂在 Shizuku 服务进程下 →
     * 文件会一直长、而且没有 moov。通知栏也没有了，用户没法自己停，
     * 所以这里主动收尾（SIGINT → 等 1.5s → 扫描），并在日志里说明。
     */
    suspend fun recoverStaleRecording(context: Context) {
        val app = context.applicationContext
        val path = RecordSettings.activePath(app)
        if (path.isEmpty()) {
            activePath = null
            return
        }
        // ★ 同一个进程里还在录的情况要**排除**：保活服务被系统重启（而进程还活着）时
        //   也会走到这里，若不排除就会把用户正在录的那一段提前掐掉。
        if (activePath == path) {
            CoverDebugLog.log(TAG, "落盘状态与本进程一致（$path）→ 正常录制中，不做收尾")
            return
        }
        CoverDebugLog.log(TAG, "检测到未正常结束的录制：$path → 主动收尾（SIGINT）")

        // 崩溃时可能留下一个没被合并的麦克风临时文件（在应用缓存目录里）：
        // 这里顺手清掉（micRecorder==null 说明本进程没在录麦克风，删了不会影响正在录的）
        if (micRecorder == null) {
            runCatching {
                val tmp = micTempFile(app)
                if (tmp.exists()) {
                    CoverDebugLog.log(TAG, "清理上次遗留的麦克风临时文件：${tmp.name}（${tmp.length()}B）")
                    tmp.delete()
                }
            }
        }

        // Shizuku 可能刚 init 还没就绪（binder 回调是异步的）：先等一会儿再判
        var waited = 0
        while (!ShizukuShell.isUsable && waited < 6) {
            delay(500)
            waited++
        }

        if (!ShizukuShell.isUsable) {
            // ★ 这里**保留**落盘状态而不是清掉：孤儿 screenrecord 还在录，
            //   状态必须如实反映现实（通知栏继续显示 ■，用户手动点 ■ 还能收尾）。
            activePath = path
            CoverDebugLog.log(
                TAG,
                "Shizuku 不可用，暂时无法收尾 → 保留状态（文件=$path，" +
                    "pid 文件=$PID_FILE）；等 Shizuku 可用后用户点通知栏 ■ 即可收尾"
            )
            withContext(Dispatchers.Main) { OrientationNotification.showIfEnabled(app, "录屏状态变化") }
            return
        }

        activePath = path
        sigintByPidFile()
        if (runningScreenrecordPids().isNotEmpty()) {
            ShizukuShell.exec("pkill -INT -x screenrecord")
        }
        // 与 stop() 一样：等进程消失 + 等 moov 就绪，再扫描（第四轮第 5 项）
        val waitedGone = waitProcessGone(PROCESS_GONE_TIMEOUT_MS)
        val ready = waitFileFinalized(path, FINALIZE_TIMEOUT_MS)
        val size = runCatching { File(path).length() }.getOrDefault(0L)
        if (size > 0L) {
            runCatching { MediaScannerConnection.scanFile(app, arrayOf(path), null, null) }
            delay(RESCAN_DELAY_MS)
            runCatching { MediaScannerConnection.scanFile(app, arrayOf(path), null, null) }
        }
        CoverDebugLog.log(
            TAG,
            "收尾完成：$path（${size / 1024}KB，进程退出等待 ${waitedGone}ms、" +
                "moov=${ready.moov} 用时 ${ready.elapsedMs}ms）"
        )
        runCatching { ShizukuShell.exec("rm -f '$PID_FILE' '$ERR_FILE'") }
        activeProcess?.let { runCatching { it.destroy() } }
        activeProcess = null
        activePath = null
        RecordSettings.clearRecording(app)
        withContext(Dispatchers.Main) { OrientationNotification.showIfEnabled(app, "录屏状态变化") }
    }

    // ------------------------------------------------------------------
    // 麦克风录制 + 机内合并（第四轮新增）
    // ------------------------------------------------------------------

    /**
     * 录音权限是否已授予（**只查，不申请**）。
     *
     * ⚠ 绝不在这里调 `requestPermissions`：这台机器**主屏没有面板**，
     *   系统权限弹窗会出现在主屏上（用户看不见 ✗，而且会把流程卡住）。
     *   权限由装机时 `adb shell pm grant cn.pashiguoke.subscreen android.permission.RECORD_AUDIO`
     *   授予；没授予就**只录画面**并在界面/日志写明原因（绝不静默）。
     */
    fun isMicAvailable(context: Context): Boolean = runCatching {
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 麦克风临时文件（应用内部缓存目录：不进相册、不受媒体库干扰） */
    private fun micTempFile(context: Context): File = File(context.cacheDir, MIC_TEMP_NAME)

    /** 启动麦克风录制（成功 true） */
    private fun startMic(context: Context): Boolean {
        stopMic(saveIfFailed = false)   // 清掉残留状态
        val out = micTempFile(context)
        runCatching { out.delete() }
        val rec = android.media.MediaRecorder()
        return try {
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(MIC_SAMPLE_RATE)
            rec.setAudioEncodingBitRate(MIC_BITRATE)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
            micRecorder = rec
            micFile = out
            // ★ 时间戳在 start() **成功之后**记：start() 之前有 ~100-300ms 的准备时间，
            //   按调用前记会把这段算进音频"头部富余"，合并时就会切掉开头真实的声音 ✗。
            micStartedElapsed = SystemClock.elapsedRealtime()
            true
        } catch (t: Throwable) {
            CoverDebugLog.log(TAG, "麦克风启动异常：${t::class.java.simpleName} ${t.message}")
            runCatching { rec.release() }
            micRecorder = null
            micFile = null
            false
        }
    }

    /**
     * 停止麦克风录制。
     *
     * @return 有效音频文件；失败/空文件返回 null。
     *   ⚠ `MediaRecorder.stop()` 在"太短/没有数据"时会抛 RuntimeException（已知坑），
     *     所以整个停止流程都包在 runCatching 里，并把空文件删掉。
     * @param saveIfFailed 启动录屏失败时要丢弃（false），正常停止时保留给合并用（false 亦可，
     *        因为合并/兜底逻辑会自己决定保留还是删除）
     */
    private fun stopMic(saveIfFailed: Boolean): File? {
        val rec = micRecorder
        val out = micFile
        micRecorder = null
        micFile = null
        var ok = false
        if (rec != null) {
            ok = runCatching { rec.stop() }.onFailure {
                CoverDebugLog.log(TAG, "麦克风 stop 异常（可能录制太短）：${it.message}")
            }.isSuccess
            runCatching { rec.release() }
        }
        val exists = out?.exists() == true && (out.length() > 0L)
        if (!ok || !exists) {
            if (!saveIfFailed) runCatching { out?.delete() }
            CoverDebugLog.log(
                TAG,
                "麦克风收尾：stop成功=$ok 文件存在=${out?.exists()} " +
                    "大小=${out?.length() ?: 0}（${if (saveIfFailed) "保留" else "丢弃"}）"
            )
            return null
        }
        return out
    }

    /** 麦克风处理结果（用于日志与用户提示） */
    private data class MicOutcome(val result: String, val merged: Boolean, val micKept: String?)

    /**
     * 停完麦克风之后：与视频合并（失败则把 m4a 复制到用户目录并如实说明）。
     */
    private suspend fun micOutcome(
        context: Context,
        videoPath: String,
        micPath: File,
        videoSize: Long
    ): MicOutcome {
        val dir = RecordSettings.recordDir(context)
        // 视频总时长（用于裁掉越界的音频尾部）
        val videoDurMs = durationMsOf(videoPath)
        val micDurMs = durationMsOf(micPath.absolutePath)
        val leadUs = if (micStartedElapsed > 0L && videoStartedElapsed > 0L) {
            (videoStartedElapsed - micStartedElapsed) * 1000L
        } else 0L
        CoverDebugLog.log(
            TAG,
            "准备合并：视频=${videoDurMs}ms（${videoSize / 1024}KB） 麦克风=${micDurMs}ms " +
                "启动偏移=${leadUs / 1000}ms"
        )

        isMuxing = true
        // 通知栏显示"处理中"（不假装已完成）
        withContext(Dispatchers.Main) { OrientationNotification.showIfEnabled(context, "录屏状态变化") }
        val merged = File(context.cacheDir, MERGED_TEMP_NAME)
        runCatching { merged.delete() }
        val muxErr = runCatching {
            muxMic(videoPath, micPath.absolutePath, merged.absolutePath, leadUs, videoDurMs * 1000L)
        }.getOrElse { t ->
            CoverDebugLog.log(TAG, "合并异常：${t::class.java.simpleName} ${t.message}")
            false
        }
        isMuxing = false

        val mergedOk = muxErr && merged.exists() && merged.length() > 0L
        if (mergedOk) {
            // 合并成功：覆盖回视频路径（文件名不变，用户只看到一条片子）→ 删掉临时件
            val replaced = runCatching {
                merged.copyTo(File(videoPath), overwrite = true)
                File(videoPath).length() > 0L
            }.getOrDefault(false)
            runCatching { merged.delete() }
            if (replaced) {
                runCatching { micPath.delete() }
                val outDur = durationMsOf(videoPath)
                runCatching {
                    MediaScannerConnection.scanFile(context, arrayOf(videoPath), null, null)
                }
                CoverDebugLog.log(
                    TAG,
                    "合并成功（已含麦克风声音）：$videoPath 时长=${outDur}ms" +
                        "（视频 $videoDurMs ms / 麦克风 $micDurMs ms / 偏移 ${leadUs / 1000}ms）"
                )
                withContext(Dispatchers.Main) { OrientationNotification.showIfEnabled(context, "录屏状态变化") }
                return MicOutcome("已含麦克风声音（音视频已合并）", true, null)
            }
            CoverDebugLog.log(TAG, "合并产物无法覆盖回 $videoPath → 走「分别保存」兜底")
        }

        // 失败兜底：**绝不丢用户的录制** —— 视频留在原路径，把 m4a 复制到同一个目录
        val micOut = File(dir, File(videoPath).nameWithoutExtension + ".m4a")
        val copied = runCatching { micPath.copyTo(micOut, overwrite = true); true }.getOrDefault(false)
        if (copied) {
            runCatching { MediaScannerConnection.scanFile(context, arrayOf(micOut.absolutePath), null, null) }
            runCatching { micPath.delete() }
        }
        CoverDebugLog.log(
            TAG,
            "合并失败（原因见上）→ 已分别保存：视频=$videoPath 麦克风=" +
                (if (copied) micOut.absolutePath else "（复制失败，仍在 ${micPath.absolutePath}）")
        )
        withContext(Dispatchers.Main) { OrientationNotification.showIfEnabled(context, "录屏状态变化") }
        return MicOutcome(
            if (copied) "自动合并失败，已分别保存：${micOut.name}" else "自动合并失败，音频仍在临时目录",
            false,
            if (copied) micOut.absolutePath else micPath.absolutePath
        )
    }

    /**
     * **机内合并**：视频 MP4 + 麦克风 M4A → 一条 MP4（H.264 + AAC）。
     *
     * 为什么不用 ffmpeg：`MediaExtractor`（读 sample）+ `MediaMuxer`（写 sample）就是标准做法 ✓，
     * 全程 Android API，无外部依赖（本机也没有 ffmpeg）。
     *
     * 对齐规则（**以视频时间轴为基准**，见 [start] 里两次 elapsedRealtime 的偏移）：
     *   · 视频：`outPts = pts - 视频首帧 pts`（归零，保证单调 ✓）；
     *   · 音频：`outPts = (pts - 音频首 sample pts) - leadUs`；
     *     `leadUs` = "麦克风比录屏早启动"的微秒数（音频头部的富余部分）；
     *   · `outPts < 0` → 丢弃（对齐头部）；`outPts > 视频总时长` → 丢弃（对齐尾部）；
     *   · 音频比视频短就让它短（播放器能处理），**不做静音补齐**（避免复杂化）。
     *
     * @return 是否成功写出可用的 MP4
     */
    private fun muxMic(
        videoPath: String,
        audioPath: String,
        outPath: String,
        leadUs: Long,
        videoDurationUs: Long
    ): Boolean {
        if (!File(videoPath).exists() || !File(audioPath).exists()) {
            CoverDebugLog.log(TAG, "合并前置检查失败：视频/音频文件不存在")
            return false
        }
        var muxer: android.media.MediaMuxer? = null
        var vExtractor: android.media.MediaExtractor? = null
        var aExtractor: android.media.MediaExtractor? = null
        return try {
            val vEx = android.media.MediaExtractor().apply { setDataSource(videoPath) }
            val aEx = android.media.MediaExtractor().apply { setDataSource(audioPath) }
            vExtractor = vEx
            aExtractor = aEx

            val vTrack = (0 until vEx.trackCount).firstOrNull {
                vEx.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)
                    .orEmpty().startsWith("video/")
            } ?: run { CoverDebugLog.log(TAG, "视频里没有 video 轨"); return false }
            val aTrack = (0 until aEx.trackCount).firstOrNull {
                aEx.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)
                    .orEmpty().startsWith("audio/")
            } ?: run { CoverDebugLog.log(TAG, "音频文件里没有 audio 轨"); return false }

            vEx.selectTrack(vTrack)
            aEx.selectTrack(aTrack)
            val mx = android.media.MediaMuxer(
                outPath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            muxer = mx
            val vOut = mx.addTrack(vEx.getTrackFormat(vTrack))
            val aOut = mx.addTrack(aEx.getTrackFormat(aTrack))
            mx.start()

            val buf = java.nio.ByteBuffer.allocate(MUX_SAMPLE_BUF)
            val info = android.media.MediaCodec.BufferInfo()

            // ---- 视频：以首帧为 0 基准（并保证单调）----
            var vBase = -1L
            var vCount = 0
            var vLastPts = -1L
            while (true) {
                buf.clear()
                val sz = vEx.readSampleData(buf, 0)
                if (sz < 0) break
                val pts = vEx.sampleTime
                if (vBase < 0) vBase = pts
                val outPts = pts - vBase
                if (outPts <= vLastPts) { vEx.advance(); continue }
                vLastPts = outPts
                info.set(0, sz, outPts, vEx.sampleFlags)
                buf.position(0); buf.limit(sz)
                mx.writeSampleData(vOut, buf, info)
                vCount++
                vEx.advance()
            }

            // ---- 音频：按 lead 对齐，越界的 sample 丢弃 ----
            var aBase = -1L
            var aCount = 0
            var aDroppedHead = 0
            var aDroppedTail = 0
            var aLastPts = -1L
            while (true) {
                buf.clear()
                val sz = aEx.readSampleData(buf, 0)
                if (sz < 0) break
                val pts = aEx.sampleTime
                if (aBase < 0) aBase = pts
                val outPts = (pts - aBase) - leadUs
                if (outPts < 0) { aDroppedHead++; aEx.advance(); continue }
                if (videoDurationUs > 0 && outPts > videoDurationUs) { aDroppedTail++; break }
                if (outPts <= aLastPts) { aEx.advance(); continue }
                aLastPts = outPts
                info.set(0, sz, outPts, aEx.sampleFlags)
                buf.position(0); buf.limit(sz)
                mx.writeSampleData(aOut, buf, info)
                aCount++
                aEx.advance()
            }

            mx.stop()
            CoverDebugLog.log(
                TAG,
                "合并写入完成：视频 sample=$vCount 音频 sample=$aCount" +
                    "（音频丢弃 头=$aDroppedHead 尾=$aDroppedTail；lead=${leadUs / 1000}ms）" +
                    " → $outPath"
            )
            vCount > 0 && aCount > 0
        } catch (t: Throwable) {
            CoverDebugLog.log(TAG, "合并失败：${t::class.java.simpleName} ${t.message}")
            false
        } finally {
            runCatching { muxer?.release() }
            runCatching { vExtractor?.release() }
            runCatching { aExtractor?.release() }
        }
    }

    /** 读媒体文件时长（毫秒）；读不到返回 0 */
    private fun durationMsOf(path: String): Long = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            runCatching { r.release() }
        }
    }.getOrDefault(0L)

    // ------------------------------------------------------------------
    // shell 细节
    // ------------------------------------------------------------------

    /** 给"开始时记下的 pid"发 SIGINT；返回是否拿到 pid */
    private suspend fun sigintByPidFile(): Boolean {
        val raw = ShizukuShell.exec("cat '$PID_FILE'")?.trim().orEmpty()
        val pid = raw.filter { it.isDigit() }
        if (pid.isEmpty()) return false
        ShizukuShell.exec("kill -INT $pid")
        return true
    }

    /** 还在跑的 screenrecord 进程（`pgrep -x` 精确匹配进程名） */
    private suspend fun runningScreenrecordPids(): List<String> {
        val out = ShizukuShell.exec("pgrep -x screenrecord")?.trim().orEmpty()
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 等 screenrecord 进程真正消失（返回实际等待毫秒数；超时也返回，由调用方看日志） */
    private suspend fun waitProcessGone(timeoutMs: Long): Long {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (runningScreenrecordPids().isEmpty()) break
            delay(FINALIZE_POLL_MS)
        }
        return System.currentTimeMillis() - t0
    }

    /** 文件收尾结果 */
    private data class FinalizeState(val moov: Boolean, val elapsedMs: Long, val stable: Boolean)

    /**
     * 等文件**真正收尾完成**：出现 `moov` 盒子 + 大小不再变化。
     *
     * ⚠⚠ **不要**再为了"短视频在相册里不显示"去改保存目录或加长延时（2026-10-11 已结案）：
     *   用户确认那 6 条录制是他**自己在相册里删的**（vivo 相册"最近删除"是移动文件，
     *   目录与 MediaStore 记录会同时消失，看起来就像"短录制没出现"✗）。
     *   实测 3s/5s/10s 三档全部正常：有 moov，而且 MediaStore 在我**手动扫描之前**
     *   就已经有记录了（`screenrecord` 收尾时自己会广播一次 MEDIA_SCANNER_SCAN_FILE）。
     *   继续用 `/sdcard/Movies` ✓。
     *
     * 为什么必须看 `moov`（这条是本轮唯一真实风险点，必须保留）：`screenrecord` 的中途文件
     * 是有 `ftyp`/`mdat` 但**没有 moov** 的，而且**收尾期间文件大小不变** ✗ ——
     * 只判"大小稳定"会在写完 moov 之前就误判"可以扫描了"。
     * 做法：从文件尾部往回读最多 [MOOV_SCAN_BYTES] 字节找 ASCII `moov`（实测 moov 在文件末尾）。
     */
    private suspend fun waitFileFinalized(path: String, timeoutMs: Long): FinalizeState {
        val t0 = System.currentTimeMillis()
        var lastSize = -1L
        var stableOnce = false
        var moovSeen = false
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val f = File(path)
            val size = if (f.exists()) f.length() else 0L
            val moov = moovSeen || (size > 0L && fileHasMoov(f))
            if (moov) moovSeen = true
            val stable = size == lastSize && size > 0L
            if (moov && stable) {
                return FinalizeState(true, System.currentTimeMillis() - t0, true)
            }
            if (stable) stableOnce = true
            lastSize = size
            delay(FINALIZE_POLL_MS)
        }
        CoverDebugLog.log(
            TAG,
            "收尾等待超时：moov=$moovSeen 大小曾稳定过=$stableOnce path=$path"
        )
        return FinalizeState(moovSeen, System.currentTimeMillis() - t0, stableOnce)
    }

    /** 文件尾部是否有 `moov` 盒子（读最后 [MOOV_SCAN_BYTES] 字节，避开大文件全读） */
    private fun fileHasMoov(file: File): Boolean = runCatching {
        val size = file.length()
        if (size <= 0L) return false
        val scan = minOf(size, MOOV_SCAN_BYTES).toInt()
        val buf = ByteArray(scan)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(size - scan)
            raf.readFully(buf)
        }
        // 朴素 ASCII 搜索 "moov"
        for (i in 0..(scan - 4)) {
            if (buf[i] == 'm'.code.toByte() && buf[i + 1] == 'o'.code.toByte() &&
                buf[i + 2] == 'o'.code.toByte() && buf[i + 3] == 'v'.code.toByte()
            ) {
                return true
            }
        }
        false
    }.getOrDefault(false)

    /**
     * 起一个**不等待、不读输出**的 shell 进程。
     *
     * 为什么不用 [ShizukuShell.exec]：它 `readText()` 到流结束才返回 ——
     * 对 screenrecord 这种"一直录到被叫停"的进程等于把调用方永久阻塞 ✗。
     * 这里自己反射同一个隐藏 API，拿到 Process 就返回；输出已经被
     * `> /dev/null 2> <err>` 重定向，所以**不存在管道写满**的问题（不需要读）。
     */
    private fun newProcessDetached(command: String): Process? = runCatching {
        val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
    }.onFailure { CoverDebugLog.log(TAG, "newProcess 反射失败：${it.message}") }.getOrNull()

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
