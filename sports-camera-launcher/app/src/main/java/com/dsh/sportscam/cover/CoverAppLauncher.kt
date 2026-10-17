package com.dsh.sportscam.cover

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 「把界面放到副屏」的核心。
 *
 * 两条路线（来自《超级小副屏》反编译结论）：
 *
 *  A. **直接**：`ActivityOptions.setLaunchDisplayId(副屏id)` + `FLAG_ACTIVITY_NEW_TASK`
 *     对 public+trusted 的物理副屏，普通 App 不需要任何特殊权限就能用
 *     （AOSP `isCallerAllowedToLaunchOnDisplay()` 走的是 `isUserVisible()` 分支）。
 *     ⚠ 失败时**静默退回主屏、不抛异常**，所以必须自己校验。
 *
 *  B. **间接（两步法）**：普通 App 无法把**别的** App 的 Activity 直接创建在副屏，
 *     但 shell 可以**迁移已存在的任务栈**：
 *        am start --display 0 -n <component>      # 先在主屏起
 *        …等 5 秒…
 *        dumpsys activity activities              # 抓 Task #N
 *        cmd activity display move-stack <N> 1    # 把整个任务栈搬到副屏
 */
object CoverAppLauncher {

    private const val TAG = "CoverAppLauncher"

    /**
     * ★ 本工程 applicationId ≠ namespace：
     *   applicationId = com.xingin.xhs      （安装后的包名 / getPackageName()）
     *   namespace     = com.dsh.sportscam   （类名展开基准）
     * 所以组件名必须写【完整类名】，写成 "包名/.MainActivity" 会解析失败。
     */
    const val OWN_ACTIVITY_CLASS = "com.dsh.sportscam.MainActivity"

    /**
     * 副屏界面 Activity —— 带独立 taskAffinity，投放时在副屏新建任务栈。
     * 类名同样要用**完整类名**（applicationId ≠ namespace）。
     */
    const val COVER_HOME_CLASS = "com.dsh.sportscam.CoverHomeActivity"

    /** 主屏（内屏）：先把目标 App 正常起来 */
    private const val SOURCE_DISPLAY = Display.DEFAULT_DISPLAY

    /** 等目标 App 起来再搬栈（旧的固定等待，保留常量兼容） */
    private const val MOVE_WAIT_MS = 5_000L

    /** 抓 Task 的轮询间隔与超时（抓到就立刻搬，不干等） */
    private const val TASK_POLL_INTERVAL_MS = 300L
    private const val TASK_POLL_TIMEOUT_MS = 4_500L

    /** 免 Shizuku 投放校验（使用记录 / 无障碍窗口）的轮询步长与超时：首轮给冷启动留足时间 */
    private const val VERIFY_A11Y_STEP_MS = 250L
    private const val VERIFY_A11Y_TIMEOUT_MS = 4_500L

    // ★ 第六轮第 1a 项：这里原来还有一个 `VERIFY_A11Y_TIMEOUT_RETRY_MS = 2_500L`
    //   （注释是"重试轮（已经重建过悬浮窗）不必再等那么久"）——
    //   它随"失败后自动重建悬浮窗 + 重试一次"整条路径**一起删除** ✓。
    //   现在只有一次尝试，统一用 [VERIFY_A11Y_TIMEOUT_MS]；
    //   应用锁场景由下面的 [VERIFY_APPLOCK_TIMEOUT_MS] 在 verifyLandedOnCover 内部动态延长 ✓。

    /**
     * 应用锁（指纹/密码弹窗）出现后的**延长窗口**（第四轮第 1 项）。
     *
     * 用户实测流程：点应用 → vivo 应用锁弹指纹 → 解锁后应用才启动。
     * 4.5s 的普通窗口会在"用户还在解锁"时误判失败 → 所以延长到 20s（总窗口 ≥15s ✓）。
     */
    private const val VERIFY_APPLOCK_TIMEOUT_MS = 20_000L

    /**
     * 应用锁界面的包名（vivo 把它做在系统设置里）。
     *
     * 依据：本项目开发前必读 §2④"部分应用被添加应用锁，需要我手动确认才能启动"，
     * 以及 `CoverForeground` 里记录的实测组件
     * `com.android.settings/com.vivo.settings.secret.PasswordActivity`。
     * 真机日志也抓到过：点拼多多后 180ms，副屏前台变成 `com.android.settings` ✓。
     */
    private val APP_LOCK_PACKAGES = setOf(
        "com.android.settings",
        "com.vivo.settings",
        "com.android.systemui.settings"
    )

    /**
     * 失败文案里"vivo 应用锁"的标记。
     *
     * 用途：[launchPackageToCover] 靠它识别"这次失败重试也没用"（需要用户手动确认），
     * 从而跳过"重建悬浮窗 + 重试"那条会白闪一下界面的路径。
     * ⚠ 改这句文案时必须同步这里。
     */
    private const val APP_LOCK_MARKER = "vivo 应用锁"

    /**
     * ============================================================================
     *  启动幂等（第三轮引入、第四轮改成"在途"语义）
     * ============================================================================
     * 背景：一个包从"点击"到"判定结果"可能很长（应用锁场景 15~30s），
     * 这段窗口里任何第二个触发源都会再跑一遍完整的启动流程 —— 用户看到"应用被反复尝试打开"。
     *
     * 第四轮的模型（见 [launchPackageToCover]）：
     *   · [inFlight]：包名 → 本次流程开始时间。**在途就不再发起**（唯一权威判据 ✓）；
     *   · [lastLaunchAt]：包名 → 上一次流程结束时间，配合 [LAUNCH_COOLDOWN_MS] 吸收
     *     "同一手势被连派两次、而第一次恰好已经结束"的极短窗口。
     */
    private const val LAUNCH_COOLDOWN_MS = 1_500L

    /** 正在启动流程中的包名（包名 → 开始时间） */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 上一次启动流程**结束**的时间（key = 包名），用于极短的完成冷却 */
    private val lastLaunchAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 「把自己（副屏界面）投放过去」的去重窗口（第三轮）。
     *
     * ⚠ 刻意**很短**（400ms）：有害的"连投两次"只相隔 29~40ms（见 launchCoverHomeToCover 注释）；
     *   而 `MainActivity.placeNow` 在"等 ~500ms 还没接管"时会**有意补投一次** ——
     *   窗口必须小到不会把那次合法补投也吃掉。
     */
    private const val SELF_PLACE_DEDUP_MS = 400L

    /**
     * 调用来源（用于把"谁触发了这次启动"写进日志）。
     *
     * 上一轮的 `[Launch]` 只有包名，无法回答"这是点击、保活拉起、重试还是被重放" ——
     * 第三轮排查"第二个触发源"时这是最缺的一条信息。这里跳过本对象内部的栈帧，
     * 取第一个外部帧（通常是 `MainActivityKt$…` / `AppListScreenKt$…` / `CoverAppLaunchBridge`）。
     */
    private fun callerHint(): String = runCatching {
        Throwable().stackTrace.firstOrNull { frame ->
            !frame.className.startsWith("com.dsh.sportscam.cover.CoverAppLauncher")
        }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "-"
    }.getOrDefault("-")

    private val TASK_PATTERN = Regex("""Task\{[0-9a-fA-F]+\s+#(\d+)([^\n}]*)""")
    private val DISPLAY_ID_PATTERN = Regex("""displayId=(\d+)""")
    private val ACTIVITY_DISPLAY_PATTERN = Regex("""ActivityRecord\{[^}]*\s+t\d+\s+d(\d+)""")
    private val PACKAGE_IN_RECORD = Regex("""ActivityRecord\{[^}]*?\su0\s([^/\s}]+)""")
    private val DISPLAY_TAIL_PATTERN = Regex("""\st\d+\sd(\d+)[\s}]""")

    /** 启动结果 */
    sealed interface Result {
        /** @param verified 是否已确认任务真的落在目标屏（未授权 Shizuku 时无法确认） */
        data class Success(val via: String, val verified: Boolean) : Result
        data class Failure(val reason: String) : Result
    }

    /** 自己的组件名，可直接喂给 `am start -n` */
    fun ownComponent(context: Context): String =
        "${context.packageName}/$OWN_ACTIVITY_CLASS"

    /** 副屏界面 Activity 的组件名 */
    fun coverHomeComponent(context: Context): String =
        "${context.packageName}/$COVER_HOME_CLASS"

    // ------------------------------------------------------------------
    // A. 原生直接启动
    // ------------------------------------------------------------------

    fun launchNative(context: Context, displayId: Int, intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (displayId != Display.DEFAULT_DISPLAY) {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
            context.startActivity(intent, options)
        } else {
            context.startActivity(intent)
        }
        Log.d(TAG, "原生启动成功 displayId=$displayId")
        true
    }.getOrElse {
        Log.w(TAG, "原生启动失败 displayId=$displayId", it)
        false
    }

    // ------------------------------------------------------------------
    // Shizuku 通道
    // ------------------------------------------------------------------

    /** 用 `am start --display <id>` 直接经 shell 启动 */
    suspend fun launchViaShell(displayId: Int, component: String): Boolean {
        val cmd = "am start -f 0x10200000 --display $displayId -n $component"
        val out = ShizukuShell.exec(cmd) ?: return false
        Log.d(TAG, "$cmd -> ${out.trim()}")
        return !out.contains("Error") && !out.contains("Exception") && !out.contains("Permission")
    }

    /** 把已存在的任务栈搬到指定显示器 */
    suspend fun moveTaskToDisplay(taskId: String, displayId: Int): Boolean {        val cmd = "cmd activity display move-stack $taskId $displayId"
        val out = ShizukuShell.exec(cmd) ?: return false
        Log.d(TAG, "$cmd -> ${out.trim()}")
        return !out.contains("Error") && !out.contains("Exception")
    }

    // ------------------------------------------------------------------
    // ★ vivo 副屏应用白名单（折叠态下能否启动第三方应用的**真正开关**）
    // ------------------------------------------------------------------

    /** vivo 用来决定"哪些应用允许在折叠态副屏上运行"的 secure 设置 */
    private const val COVER_WHITELIST_KEY = "fold_adaptive_screen_app_list"

    /**
     * 确保目标包在 vivo 的副屏应用白名单里。
     *
     * ★ 为什么必须有这一步（真机实测结论，2026-10）：
     *   OriginOS 用 secure 设置 [COVER_WHITELIST_KEY] 决定哪些应用允许在**折叠状态的
     *   副屏**上运行。不在名单里的应用，一启动就被系统拦下并弹「**翻开手机以继续**」——
     *   这与用哪种投屏方式**无关**：
     *     · `am start --display 0`（主屏起）→ 弹
     *     · `am start -f 0x10200000 --display 1`（副屏起）→ 也弹
     *   而且**任务根本不会被创建**（`dumpsys activity activities` 里找不到 taskId），
     *   所以"先在主屏起、再 move-stack 搬过去"这条兜底路也救不回来（无栈可搬）。
     *
     *   实测修复效果：把 `mark.via` 加进该列表后，同一条
     *   `am start -f 0x10200000 --display 1` 立即成功，校验为
     *   `Task{…#5023 mark.via} displayId=1 visible=true topDisplayFocusedRootTask` ✓
     *
     * ★★ 第二轮（2026-10）新增**第二条写入通道**，因为原通道有个致命前提：
     *   `settings put secure` 需要 shell（Shizuku）。用户实测"重挂悬浮窗后启动仍被拦"
     *   那一次，**Shizuku 根本没在运行** → 整个白名单更新被跳过（见下面日志）✗。
     *   而 `WRITE_SECURE_SETTINGS` 的 protectionLevel 是
     *   `signature|privileged|development|installer|role`（本机 dumpsys 实测），
     *   带 `development` ⇒ **装机时 `adb shell pm grant` 一次就能永久授予**
     *   （重装才会掉，装完由 Lead 统一 grant）。
     *   于是：有 Shizuku 用 Shizuku，没有就用应用自己的
     *   `Settings.Secure.putString` —— 不再把"能不能防住 vivo 拦截"绑在 adb 上。
     *
     * 两条通道都不可用时**必须留下明确日志**（绝不静默）。
     *
     * @return true = 目标包已在名单中（原本就在，或本次加入成功）
     */
    suspend fun ensureCoverWhitelisted(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false

        val shizuku = ShizukuShell.isUsable
        val direct = canWriteSecureSettings(context)
        if (!shizuku && !direct) {
            CoverDebugLog.log(
                "Launch",
                "白名单写入跳过：Shizuku 不可用，且本应用未授予 WRITE_SECURE_SETTINGS" +
                    "（$packageName → vivo 可能弹「翻开手机以继续」）"
            )
            return false
        }

        val current = readWhitelist(context, shizuku)
        if (current.isEmpty() || current == "null") {
            CoverDebugLog.log("Launch", "读不到副屏白名单，跳过（pkg=$packageName）")
            return false
        }
        if (current.split(',').any { it.trim() == packageName }) {
            CoverDebugLog.log(
                "Launch",
                "白名单命中：$packageName 已在 vivo 副屏白名单里（共 ${current.split(',').size} 个）"
            )
            return true
        }

        val next = "$current,$packageName"
        val written = writeWhitelist(context, shizuku, next)
        val after = readWhitelist(context, shizuku)
        val ok = after.split(',').any { it.trim() == packageName }
        CoverDebugLog.log(
            "Launch",
            if (ok) "已加入 vivo 副屏白名单: $packageName（现共 ${after.split(',').size} 个，" +
                "通道=${if (shizuku) "Shizuku" else "WRITE_SECURE_SETTINGS 直写"}）"
            else "加入 vivo 副屏白名单失败: $packageName（写入执行=$written）"
        )
        return ok
    }

    /** 本应用是否被授予了 WRITE_SECURE_SETTINGS（装机时 pm grant 一次，重装才掉） */
    private fun canWriteSecureSettings(context: Context): Boolean = runCatching {
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 读白名单：优先走 Shizuku（shell 一定读得到），否则用应用自己的 ContentResolver */
    private suspend fun readWhitelist(context: Context, shizuku: Boolean): String {
        if (shizuku) {
            val viaShell = ShizukuShell.exec("settings get secure $COVER_WHITELIST_KEY")
            if (viaShell != null) return viaShell.trim()
        }
        return runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver, COVER_WHITELIST_KEY
            ) ?: ""
        }.getOrDefault("")
    }

    /** 写白名单：优先 Shizuku，其次应用直写（两种通道的成败都返回，供日志取证） */
    private suspend fun writeWhitelist(
        context: Context,
        shizuku: Boolean,
        value: String
    ): Boolean {
        if (shizuku) {
            ShizukuShell.exec("settings put secure $COVER_WHITELIST_KEY '$value'")
            return true
        }
        return runCatching {
            android.provider.Settings.Secure.putString(
                context.contentResolver, COVER_WHITELIST_KEY, value
            )
        }.getOrDefault(false)
    }

    /** 查某包当前的 (taskId, displayId)；需要 Shizuku */
    suspend fun findTask(packageName: String): Pair<String, Int>? {
        val dump = ShizukuShell.exec("dumpsys activity activities") ?: return null
        return parseTask(dump, packageName)
    }

    /**
     * 当前全局 topResumedActivity → (包名, displayId)。
     *
     * 反编译结论：原版《超级小副屏》每 1500ms 轮询 `dumpsys activity activities`，
     * 用正则解析副屏上的前台包名来判断"该不该把启动器拉回来"。
     * 这里取的是 dumpsys 头部那行 topResumedActivity / mResumedActivity，最稳。
     */
    fun topResumedActivity(dump: String): Pair<String, Int>? {
        val line = dump.lineSequence().firstOrNull {
            it.contains("topResumedActivity") || it.contains("mResumedActivity")
        } ?: return null
        val pkg = PACKAGE_IN_RECORD.find(line)?.groupValues?.get(1) ?: return null
        val display = DISPLAY_ID_PATTERN.find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: DISPLAY_TAIL_PATTERN.find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: -1
        return pkg to display
    }

    /** 经 Shizuku 查当前 topResumedActivity */
    suspend fun queryTopResumed(): Pair<String, Int>? {
        val dump = ShizukuShell.exec("dumpsys activity activities") ?: return null
        return topResumedActivity(dump)
    }

    /** 本机所有「桌面」应用包名（用于判断副屏是不是回到了桌面） */
    fun homePackages(context: Context): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * 从 `dumpsys activity activities` 文本里解析目标包的 (taskId, displayId)。
     * Task 行形如：
     *   * Task{8f4a1c3 #123 type=standard A=10xxx:com.tencent.mm} U=0 displayId=1 ...
     */
    fun parseTask(dump: String, packageName: String): Pair<String, Int>? {
        var taskId: String? = null
        var seenPackage = false
        var seenDisplay = -1

        for (line in dump.lineSequence()) {
            val m = TASK_PATTERN.find(line)
            if (m != null) {
                // 新的 Task 块：先结算上一块
                if (taskId != null && seenPackage && seenDisplay >= 0) {
                    return taskId to seenDisplay
                }
                taskId = m.groupValues[1]
                seenPackage = m.groupValues[2].contains(packageName)
                seenDisplay = -1
            }
            if (taskId == null) continue

            if (!seenPackage && line.contains(packageName)) seenPackage = true
            if (seenDisplay < 0) {
                DISPLAY_ID_PATTERN.find(line)?.let { seenDisplay = it.groupValues[1].toInt() }
                    ?: ACTIVITY_DISPLAY_PATTERN.find(line)
                        ?.let { seenDisplay = it.groupValues[1].toInt() }
            }
        }
        return if (taskId != null && seenPackage && seenDisplay >= 0) taskId to seenDisplay else null
    }

    // ------------------------------------------------------------------
    // B. 两步法：主屏起 → 抓 Task → move-stack
    // ------------------------------------------------------------------

    suspend fun launchViaMoveStack(
        context: Context,
        packageName: String,
        targetDisplayId: Int
    ): Result = withContext(Dispatchers.IO) {
        if (!ShizukuShell.isUsable) return@withContext Result.Failure("Shizuku 未连接或未授权")

        val launchIntent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull()

        val component = launchIntent?.component?.flattenToShortString()
        if (component.isNullOrBlank()) {
            return@withContext Result.Failure("找不到启动 Activity")
        }

        // ① 先在**主屏（大屏）**把它正常启动。
        //
        //   ★★ 必须这样做（用户指出的关键机制）：**直接在副屏启动会被 vivo 拦截**
        //      （弹「翻开手机以继续」）。正确流程与《超级小副屏》一致：
        //      大屏启动 → 再把这个任务投屏（move-stack）到小屏。
        //      我此前把这条路从主路径拿掉、改成"副屏直启"，反而撞上了拦截机制。
        val startCmd = "am start --display $SOURCE_DISPLAY -n $component"
        val startOut = ShizukuShell.exec(startCmd)
        Log.d(TAG, "$startCmd -> ${startOut?.trim()}")
        if (startOut == null ||
            (startOut.contains("Starting:") && !startOut.contains("Status: ok"))
        ) {
            return@withContext Result.Failure("主屏启动失败（应用可能不支持跨屏启动）")
        }

        // ② 轮询抓 Task（原来是固定等 5 秒）：抓到就立刻搬，
        //    尽量缩短"任务还留在主屏"的窗口 —— 免得多一步系统干预。
        var task: Pair<String, Int>? = null
        val deadline = System.currentTimeMillis() + TASK_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(TASK_POLL_INTERVAL_MS)
            task = findTask(packageName)
            if (task != null) break
        }
        if (task == null) return@withContext Result.Failure("未抓到 TaskId（应用可能未真正启动）")
        Log.d(TAG, "找到 Task #${task.first}（当前在 display ${task.second}）")

        // ③ 已经在目标屏就不用搬
        if (task.second == targetDisplayId) {
            return@withContext Result.Success("已在副屏", verified = true)
        }

        // ④ 搬栈
        if (!moveTaskToDisplay(task.first, targetDisplayId)) {
            return@withContext Result.Failure("move-stack 失败")
        }

        // ⑤ 复核
        delay(700)
        val after = findTask(packageName)
        val ok = after?.second == targetDisplayId
        if (ok) Result.Success("move-stack", verified = true)
        else Result.Failure("搬栈后复核失败（仍在 display ${after?.second ?: -1}）")
    }

    // ------------------------------------------------------------------
    // 免 Shizuku 的投放校验（第二轮 D3）
    // ------------------------------------------------------------------

    /** 最近一次"活动被切到前台"的快照（包名 + 事件时间戳）；读不到 / 没数据返回 null */
    private data class ResumedSnapshot(val pkg: String?, val at: Long)

    /** 免 Shizuku 校验的结论 */
    private sealed interface LandedVerdict {
        /** 目标确实成了新的前台活动 */
        data object Landed : LandedVerdict

        /**
         * 判定"没上去"。
         * @param observed 这次启动之后**新的前台活动**是谁（null = 压根没产生新的前台活动）。
         *   实测很有用：`com.android.settings` 往往就是 **vivo 的应用锁/密码界面**
         *   （见开发者约定"部分应用被添加应用锁，需要我手动确认"），
         *   这种情况应用侧无解，必须让用户知道"要手动确认"而不是"再点点看"。
         */
        data class NotLanded(val observed: String?) : LandedVerdict

        /** 两个校验信号都读不到 → 不判失败 */
        data object Unknown : LandedVerdict
    }

    /**
     * 读"最近一次被切到前台的 Activity"。
     *
     * ★ 为什么用**使用记录**（UsageStats）而不是无障碍窗口列表：
     *   `CoverTriggerAccessibilityService.refreshForegroundFromWindows()` 依赖
     *   `AccessibilityService.getWindows()`，而**本 ROM 实测只返回默认显示器的窗口**
     *   （上一轮的记录见 `CoverForeground` 第 100 行的注释：全部=4，disp 全是 0，
     *    所以它根本看不到副屏 → 拿不到"副屏前台是谁"）。
     *   使用记录不同：本机主屏没有面板，最近恢复的活动实际就是副屏上正在用的应用，
     *   而且这条通道**已经在用、已经被证明可用**（`CoverForeground.refresh` 每 350ms
     *   就在读它，日志里 `[Fg] 副屏前台(使用记录): …` 就是它）。
     */
    private fun latestResumedSnapshot(context: Context): ResumedSnapshot? = runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 60_000L, now) ?: return null
        val e = android.app.usage.UsageEvents.Event()
        var pkg: String? = null
        var at = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED &&
                e.timeStamp >= at
            ) {
                at = e.timeStamp
                pkg = e.packageName ?: pkg
            }
        }
        // at==0 = 一条事件都没有（多半没授予「使用情况访问」）→ 不能当证据用
        if (at <= 0L) null else ResumedSnapshot(pkg, at)
    }.getOrNull()

    /**
     * 判断"目标包是不是真的到了前台"（**不需要 Shizuku**）。
     *
     * 为什么需要它：vivo 拦截副屏直启是**静默**的 —— `startActivity` 不抛异常、
     * 任务不创建、副屏上弹「翻开手机以继续」。没有 Shizuku 时我们查不到 taskId，
     * 于是"被拦"会被误判成"成功"（这正是用户第 10 项的根因：日志里 `[Launch]` 一条都没有）。
     *
     * 两个信号，按可用性取舍：
     *  ① **使用记录**（本机可用，权威）：启动前的"最近前台活动"记为基线，
     *     启动后若出现**时间戳更新**的前台活动，就说明前台真的换人了；
     *     目标是它 → 成功；换了别人 / 一直没换人 → 判定没上去。
     *  ② **无障碍窗口列表**（本 ROM 看不到副屏 → 经常返回 null）：
     *     只在信号 ① 不可用时当兜底；读到过值才敢判失败。
     *
     * ★★ 第四轮第 1 项：**应用锁场景要"继续等"，不能判失败**。
     *   用户实测的流程：点应用 → vivo 弹**指纹解锁**弹窗（前台变成 `com.android.settings` 的
     *   PasswordActivity）→ 用户解锁 → 应用才真正启动。原来 4.5s 的窗口会在"用户还在解锁"
     *   时就判"没上去" ✗ → 触发重建悬浮窗的重试 ✗，用户还会以为没反应而再点一次 ✗。
     *   现在：一旦发现新的前台活动是**应用锁包**（[APP_LOCK_PACKAGES]），就把窗口延长到
     *   [VERIFY_APPLOCK_TIMEOUT_MS]（总窗口 ≥15s），并且**只要还是应用锁界面就继续等**；
     *   期间一旦目标成为前台活动 → 立刻判成功 ✓；只有延长窗口到期才有结论。
     *
     * @return [LandedVerdict]：`Landed` / `NotLanded(观测到的新前台活动)` / `Unknown`。
     *         **Unknown 时按"未校验"处理，绝不误判成失败** —— 误判会触发重建悬浮窗的
     *         重试路径，用户会看到界面闪一下（第一版就是这个毛病，见下）。
     */
    private suspend fun verifyLandedOnCover(
        context: Context,
        packageName: String,
        baseline: ResumedSnapshot?,
        timeoutMs: Long
    ): LandedVerdict {
        val a11yRunning = CoverTriggerAccessibilityService.running
        if (baseline == null && !a11yRunning) return LandedVerdict.Unknown

        var a11yReadings = 0
        var a11yLast: String? = null
        var sawNewResume = false
        var usageLast: String? = null

        // 应用锁：出现后把窗口延长到 APP_LOCK 时长（并重新 suppress，别让我们的悬浮窗
        // 盖住指纹弹窗）。deadline 用 var，因为延长是**动态**的。
        var appLockSeen = false
        var deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            delay(VERIFY_A11Y_STEP_MS)

            if (a11yRunning) {
                val top = runCatching {
                    CoverTriggerAccessibilityService.refreshForegroundFromWindows()
                }.getOrNull()
                if (top != null) {
                    a11yReadings++
                    a11yLast = top
                    if (top == packageName) return LandedVerdict.Landed
                }
            }

            if (baseline != null) {
                val s = latestResumedSnapshot(context)
                if (s != null && s.at > baseline.at) {
                    sawNewResume = true
                    usageLast = s.pkg
                    if (s.pkg == packageName) return LandedVerdict.Landed
                    if (!appLockSeen && s.pkg != null && s.pkg in APP_LOCK_PACKAGES) {
                        appLockSeen = true
                        deadline = System.currentTimeMillis() + VERIFY_APPLOCK_TIMEOUT_MS
                        // 应用锁弹窗期间：让悬浮窗让位久一点，别盖住指纹解锁界面
                        CoverOverlayState.suppress(120_000L, "应用锁等待解锁 $packageName")
                        CoverDebugLog.log(
                            "Launch",
                            "检测到 vivo 应用锁界面（${s.pkg}）→ 延长校验窗口到 " +
                                "${VERIFY_APPLOCK_TIMEOUT_MS}ms，等用户解锁（不判失败、不重试）"
                        )
                    }
                }
            }
        }

        if (baseline != null) {
            CoverDebugLog.log(
                "Launch",
                "免 Shizuku 校验（使用记录）：目标 $packageName 没成为新的前台活动；" +
                    "新的前台活动=" +
                    (if (sawNewResume) usageLast else "（没有：启动过程没产生任何 ACTIVITY_RESUMED）") +
                    "；基线=${baseline.pkg}@${baseline.at}" +
                    "；应用锁=${appLockSeen}" +
                    if (a11yReadings > 0) "；无障碍窗口=$a11yLast" else ""
            )
            return LandedVerdict.NotLanded(if (sawNewResume) usageLast else null)
        }

        if (a11yReadings > 0) {
            CoverDebugLog.log(
                "Launch",
                "免 Shizuku 校验（无障碍窗口）：${timeoutMs}ms 内副屏最上层一直是 $a11yLast，" +
                    "不是 $packageName"
            )
            return LandedVerdict.NotLanded(a11yLast)
        }

        // ★★ 实测（2026-10-10 23:40~23:51 `[FgPull]` 日志）：
        //    拉取: cover=1 全部=2 副屏=0 应用=0 选中=null | disp=0 …
        //    也就是本 ROM 的 `AccessibilityService.getWindows()` **完全看不到副屏**，
        //    永远读不到任何值 —— 这种情况**绝不能**判"被拦"（第一版就是这么写的，
        //    结果每一次启动都被误判成失败，还会无谓地重建悬浮窗重试一次）。
        CoverDebugLog.log(
            "Launch",
            "免 Shizuku 校验不可用（使用记录没数据、无障碍窗口列表也读不到）→ 不判失败，" +
                "按未校验处理"
        )
        return LandedVerdict.Unknown
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 把某个包送到副屏（**对外入口**）。
     *
     * 策略（与《超级小副屏》一致的分层）：
     *   preferShizuku = true  → 直接走两步法（适用于"已经在主屏跑着"的 App）
     *   preferShizuku = false → 先原生，能校验就用 Shizuku 校验；
     *                           校验不通过在自动升级到两步法
     *
     * ★★ 第四轮第 1 项：**在途去重**（替代第三轮那个固定 5s 窗口）。
     *   用户实测的关键信息：**设了应用锁的应用**，点一下会先弹指纹解锁弹窗
     *   （前台变成 `com.android.settings` 的 PasswordActivity），**用户解锁后应用才真正启动**。
     *   于是：
     *     · 整个流程（启动 → 等指纹 → 等应用起来）可能持续 15~30s，**远超 5s** ✗；
     *     · 用户在这段时间里"感觉没反应"→ 再点一下（或被重放的手势再派发一次）→
     *       固定 5s 窗口根本挡不住 → 又跑一遍完整启动流程 = 用户看到的"反复尝试打开" ✗。
     *   现在改成"**同一包名在途（尚未判定结束）就绝不允许再次发起**"：
     *     在途表在 `try/finally` 里维护，成功/最终失败都会清掉 ✓；
     *   另留一个很短的"完成后冷却"（[LAUNCH_COOLDOWN_MS]）吸收"同一手势被连派两次、
     *     而第一次恰好已经结束"的情况 —— 它**不会**挡住应用锁场景（那种场景第一次还没结束）。
     */
    suspend fun launchPackageToCover(
        context: Context,
        packageName: String,
        coverDisplayId: Int,
        preferShizuku: Boolean
    ): Result {
        if (coverDisplayId == Display.DEFAULT_DISPLAY) {
            return Result.Failure("未发现副屏")
        }

        val nowAt = System.currentTimeMillis()
        val inFlightAt = inFlight[packageName]
        if (inFlightAt != null) {
            CoverDebugLog.log(
                "Launch",
                "在途跳过 pkg=$packageName（自 ${nowAt - inFlightAt}ms 前那次尚未判定结束，" +
                    "可能是应用锁等待解锁中；来源=${callerHint()}）"
            )
            return Result.Success("在途跳过（同一应用正在启动/等待解锁）", verified = false)
        }
        val lastDone = lastLaunchAt[packageName]
        if (lastDone != null && nowAt - lastDone < LAUNCH_COOLDOWN_MS) {
            CoverDebugLog.log(
                "Launch",
                "去重丢弃 pkg=$packageName（上一次刚在 ${nowAt - lastDone}ms 前结束 ＜ " +
                    "${LAUNCH_COOLDOWN_MS}ms；来源=${callerHint()}）"
            )
            return Result.Success("去重丢弃（刚启动过）", verified = false)
        }
        inFlight[packageName] = nowAt
        CoverDebugLog.log(
            "Launch",
            "在途登记 pkg=$packageName（在途=${inFlight.size} 个；来源=${callerHint()}）"
        )
        return try {
            launchPackageToCoverImpl(context, packageName, coverDisplayId, preferShizuku)
        } finally {
            inFlight.remove(packageName)
            lastLaunchAt[packageName] = System.currentTimeMillis()
            if (lastLaunchAt.size > 64) {
                val cut = System.currentTimeMillis() - LAUNCH_COOLDOWN_MS * 4
                runCatching { lastLaunchAt.entries.removeAll { it.value < cut } }
            }
            CoverDebugLog.log("Launch", "在途结束 pkg=$packageName（在途剩 ${inFlight.size} 个）")
        }
    }

    /** [launchPackageToCover] 的实际实现（在途登记之后调用；见上面的注释） */
    private suspend fun launchPackageToCoverImpl(
        context: Context,
        packageName: String,
        coverDisplayId: Int,
        preferShizuku: Boolean
    ): Result {

        // ⚠️⚠️ 悬浮窗模式（overlay mode）已知问题较多，改动前请先读这段 ⚠️⚠️
        //
        //   历史踩坑（都在悬浮窗模式下才复现，非悬浮窗模式一直正常）：
        //     1) 点应用没反应 / 弹「翻开手机以继续」：vivo 拦截在副屏**直接**启动应用
        //        （shell 的 `am start --display 副屏` 与应用内 setLaunchDisplayId 都会被拦，
        //          任务根本不创建）→ 必须"先在大屏启动，再 move-stack 投屏到小屏"；
        //     2) 「拔数据线就失败」：走的那条路依赖 Shizuku，而 Shizuku 随 adb 断开而停止
        //        → 修复必须**不依赖 Shizuku**；
        //     3) 「开一次能行、过一会又不行」：vivo 的拦截与**悬浮窗窗口的"新鲜度"**有关，
        //        窗口存活久了它发起的启动就被拦 → 需要时可重建窗口（见下面重试路径）；
        //     4) 「点应用后闪一下回到应用列表 / 回到主界面」：**启动前无条件重建悬浮窗**
        //        会把窗口连同界面一起销毁重建（导航状态也在那一刻被重置）→ 所以重建只能
        //        放在**失败后的重试路径**里，绝不能放在主路径上（本函数现在就是这么做的）。
        //
        //   因此：默认**关闭**该模式（CoverSettings.isOverlayModeEnabled 默认 false），
        //   控制台文案里也标注了"已知问题较多，非必要不建议开启"。
        //   新增逻辑时请务必在**关闭悬浮窗模式**的情况下也测一遍。

        // 常驻悬浮窗（覆盖 vivo 桌面）会盖住一切：启动任何应用之前先让它让位，
        // 否则用户在副屏上打开的应用会被我们自己的悬浮窗挡着点不到。
        // ★ 注意：这里只"让位"（hide），**不重建** —— 重建会闪屏并重置导航状态。
        // ★ 第二轮：reason 里带上**包名** —— 上一轮的日志是 `suppress(15000ms) reason=-`，
        //   排查"到底哪个应用被拦"时完全看不出来（D3 复盘时踩到）。
        CoverOverlayState.suppress(15_000L, "启动 $packageName")

        // ★★ 无条件落盘（第二轮 D3 的核心修复之一）。
        //   上一轮的日志里 `[Launch]` 一条都没有：因为所有 Launch 日志都写在
        //   `if (ShizukuShell.isUsable)` 的分支里，而那次复现时 Shizuku 没在运行 →
        //   走的是"launchNative 不抛异常就返回 Success（未校验）"这条**唯一不写日志**的路，
        //   于是"被 vivo 拦了"表现为"什么都没发生、日志里也查不到"。现在入口先记账。
        val shizukuUsable = ShizukuShell.isUsable
        CoverDebugLog.log(
            "Launch",
            "开始：pkg=$packageName cover=$coverDisplayId preferShizuku=$preferShizuku " +
                "shizuku=$shizukuUsable overlay=${CoverSettings.isOverlayModeEnabled(context)} " +
                "可直写secure=${canWriteSecureSettings(context)} " +
                // ★ 调用来源（第三轮新增）：排查"同一动作被两处触发"时必须一眼看出是谁调的
                "from=${callerHint()}"
        )

        // 启动前把目标包补进 vivo 的副屏白名单（只增不删）。
        // Shizuku 通道与"应用自己持有 WRITE_SECURE_SETTINGS"通道二选一，
        // 两条都没有时会落一条明确的跳过日志（见 ensureCoverWhitelisted）。
        val whitelisted = ensureCoverWhitelisted(context, packageName)
        CoverDebugLog.log("Launch", "白名单:$packageName inList=$whitelisted（shizuku=$shizukuUsable）")

        // ★★ 第六轮第 1a 项：**删掉了"失败后自动重建悬浮窗 + 把自身界面顶到前台再重试"整条路径** ✗。
        //   （原代码在 aggressive=true 时做：`CoverKeeperService.recreateOverlay()` → delay(450) →
        //    把副屏界面重新投过去 → 再等 1.5s 确认 presence —— 见 git 历史。现在只剩"启动尝试"本体。）
        //
        //   为什么必须删（用户实测 + 任务书第 1a 项）：
        //     · 启动失败时拆掉并重挂悬浮窗 = 副屏界面**闪一下 + 桌面整棵 Compose 树重建**
        //       （用户反馈的"图标/名称反复消失又出现"就是这种观感的来源之一）✗；
        //     · vivo 的拦截（应用锁 / 静默拦截）**不会因为窗口变新就放行** ✓，
        //       所以那次"重建后重试"基本是白闪一次、还把用户看到的失败时间拉长 ✗。
        //   现在的规则：**只有用户点右上角刷新键**（`CoverKeeperService.recreateOverlay`）
        //   才会重挂悬浮窗 ✓；启动失败就如实返回原因，并提示"点右上角刷新后重试" ✓。
        //
        // 一轮尝试：应用内原生投放 → 两步法搬栈（不再有"重建悬浮窗"这一档）。
        val attempt: suspend () -> Result = attempt@{
            val intent = runCatching {
                context.packageManager.getLaunchIntentForPackage(packageName)
            }.getOrNull() ?: return@attempt Result.Failure("没有可启动的入口")

            // ① 应用内原生投放（与非悬浮窗模式同一机制；不依赖 Shizuku）
            // ★ 免 Shizuku 校验的**基线**必须在 startActivity **之前**取：
            //   启动后只要出现"时间戳更新"的前台活动，就说明前台真的换人了。
            val resumeBaseline = if (ShizukuShell.isUsable) null else latestResumedSnapshot(context)
            if (launchNative(context, coverDisplayId, intent)) {
                if (!ShizukuShell.isUsable) {
                    // ★★ 第二轮 D3 的关键修复：这里以前**直接**返回
                    //   `Result.Success("原生（未校验）")` —— 而 vivo 的拦截是**静默**的
                    //   （startActivity 不抛异常、任务不创建、副屏上弹「翻开手机以继续」），
                    //   于是"被拦"被判成"成功"：不重试、不兜底、不提示、日志里连一行都没有 ✗
                    //   （用户第 10 项的复现日志里 `[Launch]` 零条就是这条路的后果）。
                    //
                    //   现在补上真相判断（细节见 verifyLandedOnCover）：
                    //   使用记录里"前台活动有没有换人/换成谁"是免 Shizuku 也能拿到的权威信号
                    //   （无障碍的窗口列表在这台机器上看不到副屏，只能当兜底）。
                    // ★ 第六轮第 1a 项：不再有"重试档"，统一用这个窗口
                    //   （应用锁场景由 verifyLandedOnCover 内部动态延长到 ≥20s ✓）
                    val verdict = verifyLandedOnCover(
                        context, packageName, resumeBaseline, VERIFY_A11Y_TIMEOUT_MS
                    )
                    return@attempt when (verdict) {
                        LandedVerdict.Landed -> {
                            CoverDebugLog.log(
                                "Launch",
                                "$packageName → 原生投放成功（免 Shizuku 校验：前台活动已变成它）"
                            )
                            Result.Success("原生+免Shizuku校验", verified = true)
                        }
                        is LandedVerdict.NotLanded -> {
                            val obs = verdict.observed
                            val hint = when {
                                obs == null ->
                                    "${VERIFY_A11Y_TIMEOUT_MS}ms 内没有出现任何新的前台活动（启动没产生 Activity）"
                                obs == "com.android.settings" ->
                                    "前台变成了 com.android.settings —— 这通常是 vivo 应用锁/密码界面" +
                                        "（需要手动确认，应用侧无法绕过）"
                                else -> "前台变成了 $obs"
                            }
                            CoverDebugLog.log(
                                "Launch",
                                "$packageName → 原生投放没上去：$hint" +
                                    "（白名单命中=$whitelisted shizuku=${ShizukuShell.isUsable}）"
                            )
                            Result.Failure("副屏直启没上去：$hint")
                        }
                        LandedVerdict.Unknown -> {
                            CoverDebugLog.log(
                                "Launch",
                                "$packageName → 原生（未校验：使用记录与无障碍都读不到，" +
                                    "无法确认是否被拦）白名单命中=$whitelisted " +
                                    "shizuku=${ShizukuShell.isUsable}"
                            )
                            Result.Success("原生（未校验·无校验信号可用）", verified = false)
                        }
                    }
                }
                delay(1_100)
                val t = findTask(packageName)
                if (t != null && t.second == coverDisplayId) {
                    CoverDebugLog.log("Launch", "$packageName → 原生投放成功")
                    return@attempt Result.Success("原生", verified = true)
                }
                CoverDebugLog.log(
                    "Launch",
                    "$packageName → 原生投放未被 Shizuku 校验通过（task=${t?.first ?: "-"} " +
                        "display=${t?.second ?: -1}）→ 转两步法"
                )
            }

            // ② 大屏启动 + 搬栈（原版机制；需要 Shizuku）
            if (ShizukuShell.isUsable) {
                val r = launchViaMoveStack(context, packageName, coverDisplayId)
                CoverDebugLog.log("Launch", "$packageName → 两步法: $r")
                if (r is Result.Success) return@attempt r
            }

            Result.Failure("这一轮未成功")
        }

        val first = attempt()
        if (first is Result.Success) return first

        // ★★ 第六轮第 1a 项：**不再自动重试、不再重建悬浮窗** ✓。
        //   旧行为：第一轮失败 → `recreateOverlay` + delay(450) + 重投自身界面 → 再试一次 ✗。
        //   它带来的只有"副屏界面闪一下 + 桌面 Compose 树重建"，对 vivo 的拦截（应用锁/静默拦截）
        //   没有任何帮助 ✓（详见上面 attempt 处的长注释）。
        //   现在：如实返回失败原因 + 明确的人工动作提示 —— 悬浮窗**只**由用户点右上角刷新键重挂 ✓。
        val reason = (first as? Result.Failure)?.reason ?: first.toString()
        val isAppLock = reason.contains(APP_LOCK_MARKER)
        CoverDebugLog.log(
            "Launch",
            "$packageName → 启动失败（$reason）" +
                if (isAppLock) {
                    "；这是 vivo 应用锁（$APP_LOCK_MARKER），需要用户在副屏上手动确认一次，" +
                        "应用侧无法绕过；确认后若仍没上去，请点右上角「刷新」重挂悬浮窗再试" +
                        "（不再自动重建悬浮窗、不再自动重试）"
                } else {
                    "；请点右上角「刷新」重挂悬浮窗后重试（不再自动重建悬浮窗、不再自动重试）"
                }
        )
        return Result.Failure(
            "$reason；可点右上角「刷新」后重试" +
                if (isAppLock) "（vivo 应用锁需先在副屏手动确认一次）" else ""
        )
    }

    /** 上一次"把自己（副屏界面）投放过去"的时间（自身投放去抖，见 launchCoverHomeToCover） */
    @Volatile
    private var lastSelfPlaceAt = 0L

    /**
     * 把**副屏界面**（[com.dsh.sportscam.CoverHomeActivity]）送到副屏。
     *
     * 它与入口 Activity 的区别是 manifest 里带了**独立 taskAffinity**
     * （`cn.pashiguoke.subscreen.cover`，对齐原版 SubActivity）。
     * 于是这条 `NEW_TASK + setLaunchDisplayId` 的投放会在副屏上
     * **新建一个任务栈**，而不是复用主屏上那个已经存在、处于后台的任务 ——
     * 后者正是"被唤起却停在后台、要手动上拉后台管理"的根因。
     *
     * ★★ 第三轮加了 [SELF_PLACE_DEDUP_MS] 去抖，原因（真机日志）：
     *   同一时刻我们对 **singleTask** 的副屏界面连续投两次，第二次不会新建界面，
     *   而是走 `onNewIntent` → 触发"重新进入 → 通知界面回到主界面" →
     *   **导航栈被重置、桌面整棵 Compose 树重新加载**（用户看到"图标/名称反复消失又出现"）。
     *   而日志里这种"连投两次"是常态：
     *     · `MainActivity` 在 onCreate 里投一次（第 180 行）+ `LaunchedEffect(Unit)` 再投一次（214 行）
     *       → `00:04:23.316 place: 进入` / `00:04:23.345 place: 进入`（相隔 29ms）；
     *     · 保活服务的自动拉起与无障碍服务的拉起也可能在几百毫秒内各投一次。
     *   所以这里做**自身投放去重**：1.5s 内重复请求直接丢弃（落盘一行便于取证）。
     *   这**不影响**"副屏被别的应用顶掉后要拉回来"——那种场景两次投放相隔远大于 1.5s。
     */
    suspend fun launchCoverHomeToCover(context: Context, coverDisplayId: Int): Result {
        // ★ 时间去重（第三轮）：同一动作在极短时间内被两处触发时（例如
        //   MainActivity 在 onCreate 里投一次（:180）+ LaunchedEffect 再投一次（:214），
        //   相隔只有 29ms — 真机日志 `place: 进入` 00:04:23.316 / 00:04:23.345），
        //   第二次直接丢弃：它不会新建界面，只会走 singleTask 的 onNewIntent
        //   → "重新进入 → 通知界面回到主界面" → 导航栈重置、桌面整棵树重建。
        //
        // ⚠ 窗口刻意很短（400ms），并且**不做**"presence 已在前台就跳过"那种状态判断 ——
        //   因为 `MainActivity.placeNow` 是靠 `CoverPresence.token()` 变化来确认"副屏界面
        //   真的接管了"再退场的（:383-397）；如果在这里按 presence 提前 return，
        //   一旦令牌恰好没变化，跳板就会"超时未被接管、保持自身界面"，反而出问题。
        //   400ms 既能挡住 29ms 的连投，又不会吃掉 placeNow 在 ~500ms 的那次合法补投
        //   （:389-392），也不影响保活(3s)/无障碍(4s)各自的拉起去抖。
        val now = System.currentTimeMillis()
        val since = now - lastSelfPlaceAt
        if (lastSelfPlaceAt > 0L && since < SELF_PLACE_DEDUP_MS) {
            CoverDebugLog.log(
                "Launch",
                "自身界面投放去重丢弃（距上次 ${since}ms ＜ ${SELF_PLACE_DEDUP_MS}ms，" +
                    "来源=${callerHint()}）—— 避免 singleTask 的 onNewIntent 重置导航栈"
            )
            return Result.Success("去重丢弃（自身界面 ${SELF_PLACE_DEDUP_MS}ms 内已投放）", verified = false)
        }
        lastSelfPlaceAt = now

        val intent = Intent().setClassName(context.packageName, COVER_HOME_CLASS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (launchNative(context, coverDisplayId, intent)) {
            return Result.Success("原生·独立任务栈", verified = false)
        }
        if (ShizukuShell.isUsable) {
            val ok = launchViaShell(coverDisplayId, coverHomeComponent(context))
            return if (ok) Result.Success("shell am start", verified = true)
            else Result.Failure("am start 失败")
        }
        return Result.Failure("原生启动抛异常，且未授权 Shizuku")
    }

    /** 把自己（启动器副屏界面）送到副屏 —— 保活/无障碍自动拉起都走这里 */
    suspend fun launchSelfToCover(context: Context, coverDisplayId: Int): Result =
        launchCoverHomeToCover(context, coverDisplayId)
}
