package com.dsh.sportscam.cover

import android.content.Context

/**
 * 副屏相关开关（SharedPreferences）。
 *
 * 所有访问器都显式接收 Context，避免持有全局 Context 带来的初始化顺序问题。
 */
object CoverSettings {

    private const val PREFS = "cover_settings"
    private const val KEY_AUTO_PULL = "auto_pull_enabled"
    private const val KEY_TORCH_OFF = "torch_off_on_leave"
    private const val KEY_PREFER_SHIZUKU = "prefer_shizuku_apps"
    private const val KEY_KEEPER = "keeper_enabled"
    private const val KEY_TRIGGER_PACKAGES = "trigger_packages"
    private const val KEY_APP_ORIENTATION = "app_orientation"
    private const val KEY_APPLIED_PRESET = "applied_preset_index"

    /** 主界面静音（默认关） */
    private const val KEY_MUTE_CAROUSEL = "mute_on_carousel"

    /** 风格强调色（默认红） */
    private const val KEY_ACCENT = "accent_style"

    /** 通知栏「截屏」按钮开关（默认开；依赖无障碍服务，断开时自动关闭） */
    private const val KEY_SCREENSHOT = "screenshot_enabled"

    /** 主界面「拍摄」使用的相机包名（空 = 自动） */
    private const val KEY_CAMERA_PACKAGE = "camera_package"

    /**
     * 长按「拍摄」启动第二层应用（与短按"打开相机"是两件事，刻意分开）。
     *
     * 开关默认关；[KEY_CAMERA_LONGPRESS_PACKAGE] 是长按时要启动的包名（空 = 未选择）。
     */
    private const val KEY_CAMERA_LONGPRESS_ENABLED = "camera_longpress_enabled"
    private const val KEY_CAMERA_LONGPRESS_PACKAGE = "camera_longpress_package"
    private const val KEY_AUTO_PLACE = "auto_place_on_start"
    private const val KEY_ORIENTATION_LOCK = "orientation_lock"
    private const val KEY_ORIENTATION_NOTIF = "orientation_notif_enabled"

    /** 退出应用后是否回到主界面（默认开） */
    private const val KEY_RETURN_HOME = "return_home_on_reentry"

    /** 边缘手势锁定（防误退，默认关） */
    private const val KEY_EDGE_BLOCK = "edge_block_enabled"

    /** 常驻悬浮窗覆盖 vivo 副屏桌面（默认关） */
    private const val KEY_OVERLAY_MODE = "overlay_mode_enabled"

    /** 不主动弹出：悬浮窗只在启动器界面出现，纯防退出（默认关） */
    private const val KEY_OVERLAY_PASSIVE = "overlay_passive_mode"
    private const val SEP = ";"

    /** 副屏自动息屏时长（毫秒；0 = 未设置，不干预系统） */
    private const val KEY_SCREEN_TIMEOUT = "cover_screen_timeout_ms"

    /**
     * 「运动相机模式」总开关（默认**开**）。
     *
     * 关掉它 = 本应用退回"普通启动器"：进入即桌面，且所有防查能力（悬浮窗防退出 /
     * 回到桌面自动拉起 / 不主动弹出 / 主界面静音）**整体不生效**。
     *
     * ★★ 重要（第四轮第 2 项）：这里**只让功能不生效，绝不改写用户的意愿**。
     *   所以本类里的防查开关都拆成两份：
     *     · `...Preferred(context)` —— SharedPreferences 里存的**用户意愿**（setter 只写这份）；
     *     · `isXxx(context)`        —— **当前是否生效** = `意愿 && 运动相机模式开着`。
     *   这样用户关掉运动相机模式、再打开时，之前勾的那些选项会**自动恢复**，
     *   不需要一个个手动重开（旧实现是关模式时直接写 false，被用户实测吐槽 ✗）。
     *
     *   为什么要在这里统一收敛：所有消费方（保活服务 / 无障碍 / BootReceiver /
     *   CoverAppLauncher / 界面）读的都是 `isXxx`，于是"关模式 = 整体下线"天然成立，
     *   不需要每个调用点各自记得判一次。
     */
    private const val KEY_CAMERA_MODE = "camera_mode_enabled"

    /**
     * ⚠ **第九轮已废弃的 prefs 键**：「非运动相机界面不使用悬浮窗」这个设置项已按用户要求删除
     * （原因见 [isOverlayOnlyInCameraMode]）。键与读写函数保留，只为兼容
     * [com.dsh.sportscam.cover.OrientationNotification] 的既有读取；不再由任何界面写入。
     */
    private const val KEY_OVERLAY_ONLY_IN_CAMERA = "overlay_only_in_camera_mode"

    /** 「定时开关运动相机模式」的多条计划（容错序列化，见 [cameraModeSchedules]） */
    private const val KEY_CAMERA_MODE_SCHEDULES = "camera_mode_schedules"

    /** 「私密应用」功能总开关（默认**关**） */
    private const val KEY_PRIVATE_APPS_ENABLED = "private_apps_enabled"

    /** 私密应用的包名集合（存 `Set<String>`；读的时候容错，见 [privateAppsPreferred]） */
    private const val KEY_PRIVATE_APPS = "private_apps"

    /** 启动器密码开关（默认**不启用**） */
    private const val KEY_PASSWORD_ENABLED = "password_enabled"

    /** 启动器密码的哈希（sha256(明文 + 固定盐)；**绝不存明文**） */
    private const val KEY_PASSWORD_HASH = "password_hash"

    /**
     * 密码**保护范围**：三个互不干扰的独立开关（默认**都关**）。
     *
     * 语义（第二轮 B1）：
     *  · 三者各管一个入口：控制台 / 总应用列表 / 桌面编辑模式；
     *  · 总开关 [KEY_PASSWORD_ENABLED] 关掉时三者**全部不拦**
     *    （各自的勾选值仍保留，下次重新启用密码时恢复原样）；
     *  · 没设置密码（哈希为空）时三者无意义 → 控制台里置灰。
     */
    private const val KEY_PW_GUARD_CONSOLE = "password_guard_console"
    private const val KEY_PW_GUARD_APPLIST = "password_guard_app_list"
    private const val KEY_PW_GUARD_DESKTOP_EDIT = "password_guard_desktop_edit"

    /** 第十轮新增：保护范围第 4 项「保护最近删除」（默认关） */
    private const val KEY_PW_GUARD_TRASH = "password_guard_trash"

    /**
     * 主界面（轮播）左上角的 **24 小时制时钟** 是否显示（默认 **开**）。
     *
     * ⚠ 渲染方是 `ui/CarouselScreen.kt`（desktop-ui 的文件）—— 它读
     * **[isShowClockOnHome]**；本类只负责存取，不做任何渲染。
     */
    private const val KEY_SHOW_CLOCK_ON_HOME = "show_clock_on_home"

    /**
     * 密码哈希用的**固定盐**。
     *
     * 为什么必须加盐：密码是 4 位数字，只有 10000 种可能，不加盐时 sha256 可以被
     * 彩虹表/穷举瞬间反查出明文。加盐后至少不会与别处同值的哈希相互印证。
     */
    private const val PASSWORD_SALT = "sportscam-launcher-pw-v1"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 防查类开关的统一收敛：**运动相机模式关掉时一律不生效**。
     *
     * 注意只影响"生效"，不影响 SharedPreferences 里存的用户意愿（见 [isCameraModeEnabled]）。
     */
    private fun guardEffective(preferred: Boolean, cameraMode: Boolean): Boolean =
        preferred && cameraMode

    /**
     * 运动相机模式是否开着（**默认关**；第十六轮按用户要求从 true 改成 false）。
     * 内部用，避免在 getter 里反复走公开方法。
     *
     * ⚠ 只改"没存过时的默认值"：已经存过该键的用户**不受影响** ✓（取值不变）。
     */
    private fun cameraModeOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CAMERA_MODE, false)

    /**
     * 副屏上出现「桌面/触发应用」时，自动把启动器拉回副屏。
     * 默认关闭 —— 需要用户先在系统设置里授予无障碍权限才有意义。
     *
     * ⚠ 这是**生效值**：运动相机模式关掉时恒为 false（意愿仍留在 [autoPullPreferred] 里）。
     * ★ [overlayAllowedOnCurrentScreen] 第九轮起恒 true（那个"非运动相机界面不使用悬浮窗"
     *   的设置已按用户要求删除），保留那一层只是为了让所有拉起路径的调用点零改动。
     *   ⚠ 界面显示请用 [isAutoPullArmed]，别用带屏幕闸的生效值（否则站在控制台里会显示成"关"）。
     */
    fun isAutoPullEnabled(context: Context): Boolean =
        isAutoPullArmed(context) && overlayAllowedOnCurrentScreen(context)

    /**
     * 「回到桌面自动拉起」在**不考虑"仅相机界面"屏幕闸**时的状态。
     *
     * 给**界面**用（③ 页那个 ToggleRow 的 checked）：用户站在控制台里时自己就不在
     * 运动相机主界面上，若直接拿 [isAutoPullEnabled] 显示，开关会显示成关 ✗。
     */
    fun isAutoPullArmed(context: Context): Boolean =
        guardEffective(autoPullPreferred(context), cameraModeOn(context))

    /** 「回到桌面自动拉起」的**用户意愿**（与运动相机模式无关） */
    fun autoPullPreferred(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_PULL, false)

    fun setAutoPullEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_PULL, value).apply()
    }

    /** 离开启动器时自动关手电筒（折叠导致 onStop 时不希望灭灯，可关掉此项） */
    fun isTorchOffOnLeave(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TORCH_OFF, true)

    fun setTorchOffOnLeave(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_TORCH_OFF, value).apply()
    }

    /**
     * 常驻前台服务（保活）。
     *
     * 默认 **开**。原因（实测）：息屏状态下如果进程是冷的，系统会把这次 Activity 启动
     * **推迟**（`am start` 有 38 秒的 WaitTime，任务甚至不创建），屏幕也不会被唤醒；
     * 而进程温热时同样一次启动 `WaitTime` 只有 164ms，`turnScreenOn` 立刻点亮屏幕。
     * 《超级小副屏》的 SubScreenKeeper 就是靠常驻前台服务把进程保热的 —— 这是它能
     * 在息屏快捷启动时"秒进应用"的根本原因。
     */
    fun isKeeperEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEPER, true)

    fun setKeeperEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEPER, value).apply()
    }

    /** 始终用 Shizuku 两步法搬运的包名集合 */
    fun preferShizukuApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PREFER_SHIZUKU, emptySet()) ?: emptySet()

    fun setPreferShizuku(context: Context, packageName: String, prefer: Boolean) {
        val current = preferShizukuApps(context).toMutableSet()
        if (prefer) current.add(packageName) else current.remove(packageName)
        prefs(context).edit().putStringSet(KEY_PREFER_SHIZUKU, current).apply()
    }

    fun isPreferShizuku(context: Context, packageName: String): Boolean =
        preferShizukuApps(context).contains(packageName)

    /** 自定义触发包名（副屏上出现这些包时把启动器拉回来） */
    fun triggerPackages(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_TRIGGER_PACKAGES, null) ?: return emptySet()
        return raw.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun setTriggerPackages(context: Context, packages: Set<String>) {
        prefs(context).edit()
            .putString(KEY_TRIGGER_PACKAGES, packages.joinToString(SEP))
            .apply()
    }

    /** 记住用户选的 App 方向（ActivityInfo.SCREEN_ORIENTATION_* 值） */
    fun appOrientation(context: Context): Int =
        prefs(context).getInt(KEY_APP_ORIENTATION, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

    fun setAppOrientation(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_APP_ORIENTATION, value).apply()
    }

    /** 记住上次套用的分辨率预设（-1 表示未设置） */
    fun appliedPresetIndex(context: Context): Int =
        prefs(context).getInt(KEY_APPLIED_PRESET, -1)

    fun setAppliedPresetIndex(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_APPLIED_PRESET, value).apply()
    }

    /**
     * 风格（强调色）：red / yellow / green / blue。
     * 影响删除类文本与按钮、多选框、控制台小控件、警告文本等"有颜色"的元素；
     * **不影响**主界面（轮播）的白色图标与手电筒黄色。
     */
    fun accentKey(context: Context): String =
        prefs(context).getString(KEY_ACCENT, "red") ?: "red"

    fun setAccentKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ACCENT, value).apply()
    }

    /**
     * 主界面静音：停在运动相机主界面时把音量压到 0 并吃掉音量键（系统音量条不弹出）；
     * 切到其它界面（相册 / 桌面 / 控制台 / 别的应用）恢复正常。
     *
     * 默认**关闭** —— 会让设备静音，属于需要用户主动开启的强行为。
     * ⚠ 生效值：运动相机模式关掉时恒为 false（意愿仍在 [muteOnCarouselPreferred]）。
     */
    fun isMuteOnCarousel(context: Context): Boolean =
        guardEffective(muteOnCarouselPreferred(context), cameraModeOn(context))

    /** 「主界面静音」的**用户意愿**（与运动相机模式无关） */
    fun muteOnCarouselPreferred(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MUTE_CAROUSEL, false)

    fun setMuteOnCarousel(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_MUTE_CAROUSEL, value).apply()
    }

    /**
     * 主界面「拍摄」要打开哪个相机应用（包名）。
     * 空串 = 自动（沿用原有逻辑：优先 OpenCamera，失败回退系统相机）。
     */
    fun cameraPackage(context: Context): String =
        prefs(context).getString(KEY_CAMERA_PACKAGE, "") ?: ""

    fun setCameraPackage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CAMERA_PACKAGE, value).apply()
    }

    /**
     * 「拍摄长按启动第二层应用」开关。
     *
     * 与 [cameraPackage]（短按打开哪个相机）是**两件事**，不要合并：
     * 短按仍是拍/开相机，长按才是这颗"第二层"入口，且由本开关独立控制启停。
     * 默认**关闭**。
     */
    fun isCameraLongPressEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CAMERA_LONGPRESS_ENABLED, false)

    fun setCameraLongPressEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAMERA_LONGPRESS_ENABLED, value).apply()
    }

    /** 长按「拍摄」时要启动的应用包名（空 = 未选择，长按无动作） */
    fun cameraLongPressPackage(context: Context): String =
        prefs(context).getString(KEY_CAMERA_LONGPRESS_PACKAGE, "") ?: ""

    fun setCameraLongPressPackage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CAMERA_LONGPRESS_PACKAGE, value).apply()
    }

    /**
     * 在主屏被拉起时，自动把「自己」投到副屏。
     *
     * 这正是《超级小副屏》的做法：它的 MainActivity 一起来就
     * `CoverLauncher.launch()` 把 SubActivity 放到副屏 ——
     * **绕开 ROM 的第三方应用副屏白名单**（不经过系统的"副屏打开应用"流程）。
     */
    fun isAutoPlaceOnStart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_PLACE, true)

    fun setAutoPlaceOnStart(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_PLACE, value).apply()
    }

    /**
     * 当前「显示器方向锁」的值（ActivityInfo.SCREEN_ORIENTATION_*）。
     * 由通知栏方向开关写入，服务重启后据此重新套用。
     */
    fun orientationLock(context: Context): Int =
        prefs(context).getInt(
            KEY_ORIENTATION_LOCK,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        )

    fun setOrientationLock(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_ORIENTATION_LOCK, value).apply()
    }

    /** 是否在通知栏常驻「方向开关」 */
    fun isOrientationNotifEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ORIENTATION_NOTIF, true)

    fun setOrientationNotifEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ORIENTATION_NOTIF, value).apply()
    }

    /**
     * 退出应用后是否回到主界面（运动相机主页）。
     *
     * 默认 **开**：无论后台有没有被清掉，再次进入都回到运动相机主界面。
     * 关闭后则保留上一次停留的界面（副屏界面是 singleTask，实例复用，
     * 导航栈本来就会保留）。
     */
    fun isReturnToHomeOnReentry(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RETURN_HOME, true)

    fun setReturnToHomeOnReentry(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_RETURN_HOME, value).apply()
    }

    /**
     * 「通知栏截屏按钮」开关（控制台 → ① 界面与个性化）。
     *
     * 默认 **关**（用户明确要求：不主动打开就不该在通知栏出现截屏按钮）。
     * 依赖**无障碍服务**（截屏走 `AccessibilityService.takeScreenshot`），所以服务没在跑时：
     *  · 控制台里这一项置灰不可开；
     *  · 通知栏那一行「截屏副屏画面」按钮隐藏；
     *  · 服务断开时会**自动关闭本开关**（见 CoverTriggerAccessibilityService.autoDisableScreenshot）。
     */
    fun isScreenshotEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCREENSHOT, false)

    fun setScreenshotEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCREENSHOT, value).apply()
    }

    /**
     * 副屏**自动息屏时长**（毫秒）。0 = 未设置（不干预系统默认）。
     *
     * 为什么需要：悬浮窗模式下副屏一直盖着我们的全屏悬浮窗，系统不判"空闲"，
     * 屏幕不会自动熄灭 → 长时间亮屏很耗电（用户实测反馈）。
     * 可选档位见 [CoverScreenTimeout.PRESETS]。
     */
    fun screenTimeoutMs(context: Context): Long =
        prefs(context).getLong(KEY_SCREEN_TIMEOUT, 0L)

    fun setScreenTimeoutMs(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_SCREEN_TIMEOUT, value).apply()
    }

    /**
     * 边缘手势锁定（防误退）。
     *
     * 开启后，在启动器**处于前台**时于副屏的左/右/下三边各挂一条透明悬浮窗，
     * 把这些区域的系统手势（返回 / 上滑回桌面）吃掉，避免误触退出启动器。
     *
     * ⚠ 默认**关闭**：开启后边缘手势会被拦掉，属于"可能把自己关在里面"的能力，
     *   不适合默认打开。（顶边刻意不拦 —— 否则下拉通知栏就废了，
     *   而通知栏正是方向开关的入口。）
     * ⚠ 该功能已从控制台下线，但历史值可能残留；这里同样按"运动相机模式关掉就不生效"收敛。
     */
    fun isEdgeBlockEnabled(context: Context): Boolean =
        guardEffective(edgeBlockPreferred(context), cameraModeOn(context))

    /** 「边缘手势锁定」的**用户意愿**（与运动相机模式无关） */
    fun edgeBlockPreferred(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EDGE_BLOCK, false)

    fun setEdgeBlockEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_EDGE_BLOCK, value).apply()
    }

    /**
     * 常驻悬浮窗模式：把启动器界面画进 `TYPE_APPLICATION_OVERLAY`，
     * **永久覆盖在 vivo 副屏桌面之上**（不需要禁用系统桌面，那条路要 root）。
     *
     * ⚠ 只在**解锁状态**下挂出：
     *   · 悬浮窗层级在 vivo 锁屏之上，如果锁屏时也盖着，用户就没法输入密码解锁了；
     *   · 锁屏时收起，正好让"快捷启动 → 指纹验证 → 秒进应用"那条链路保持原样。
     *
     * 默认关闭。
     * ⚠ 生效值：运动相机模式关掉时恒为 false（意愿仍在 [overlayModePreferred]）——
     *   所以"关模式 → 再打开"时用户原来的悬浮窗选择会**自动恢复**，不需要重设。
     */
    fun isOverlayModeEnabled(context: Context): Boolean =
        guardEffective(overlayModePreferred(context), cameraModeOn(context))

    /** 「悬浮窗防退出模式」的**用户意愿**（与运动相机模式无关） */
    fun overlayModePreferred(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_MODE, false)

    fun setOverlayModeEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_MODE, value).apply()
    }

    /**
     * 「不主动弹出」模式。
     *
     * 开启后：
     *  · **不再**检测副屏前台、也不再主动挂出悬浮窗去覆盖 vivo 桌面；
     *  · 悬浮窗只在**启动器自己处于副屏前台**时出现（`CoverPresence`），
     *    也就是纯粹当"防退出"的常驻层用；
     *  · 由此彻底避开"判断副屏前台是谁"这件事 —— 那正是
     *    "用别的应用时被悬浮窗拦"的根源。
     *
     * 默认关闭（关闭时保持旧行为：副屏回到桌面就挂出覆盖）。
     * ⚠ 生效值：运动相机模式关掉时恒为 false（意愿仍在 [overlayPassivePreferred]）。
     */
    fun isOverlayPassive(context: Context): Boolean =
        guardEffective(overlayPassivePreferred(context), cameraModeOn(context))

    /** 「不主动弹出」的**用户意愿**（与运动相机模式无关） */
    fun overlayPassivePreferred(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_PASSIVE, false)

    fun setOverlayPassive(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_PASSIVE, value).apply()
    }

    // ==================================================================
    // 运动相机模式（总开关）
    // ==================================================================

    /**
     * 「运动相机模式」总开关，**默认关**（第十六轮按用户要求从"默认开"改成"默认关"）。
     *
     *  · 开 = 本应用就是一台"运动相机"：进入启动器直接进**运动相机主界面**，
     *    防查功能（悬浮窗防退出 / 自动拉起 / 不主动弹出 / 主界面静音）可正常开关；
     *  · 关 = 退回**普通启动器**：进入启动器直接进**桌面**，上述防查功能
     *    一律强制关闭并在控制台置灰锁定。
     *
     * ⚠ 用户预期（第十六轮真机反馈）：全新安装第一次进来应当是**桌面**（普通启动器），
     *   再由用户自己去控制台 ③ 页手动开启防查模式 ✓ —— 所以默认值必须是 false。
     * ⚠ **只改"没存过时的默认值"** ✓：已存过该键的老用户取值不变（升级后仍是原来的开/关）。
     * ⚠ 关掉时**不能只依赖控制台界面**：控制台会把子项写成 false（见 ConsoleScreen），
     *   服务侧 [CoverKeeperService.syncOverlayWindow] 还会再兜一道 ——
     *   即使历史设置残留（旧版本写过 overlay_mode_enabled=true），也绝不挂悬浮窗。
     */
    fun isCameraModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CAMERA_MODE, false)

    fun setCameraModeEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAMERA_MODE, value).apply()
    }

    // ==================================================================
    // 启动器密码（四位数字；默认不启用）
    // ==================================================================

    /** 是否启用启动器密码（默认**不启用**）。 */
    fun isPasswordEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PASSWORD_ENABLED, false)

    fun setPasswordEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PASSWORD_ENABLED, value).apply()
    }

    /** 密码哈希（sha256(明文 + 固定盐) 的小写十六进制）；空串 = 未设置密码。 */
    fun passwordHash(context: Context): String =
        prefs(context).getString(KEY_PASSWORD_HASH, "") ?: ""

    /** 是否已经设置过密码（开关是另一回事，见 [isPasswordEnabled]）。 */
    fun hasPassword(context: Context): Boolean = passwordHash(context).isNotEmpty()

    /**
     * 设置 / 修改密码：**只落盘哈希**，明文绝不进 SharedPreferences。
     *
     * 顺带把「启用」开关打开 —— 用户刚设完密码就是要用它，省一步点击。
     * ⚠ 忘记密码**只能重装应用**：这是刻意设计的，没有找回通道也就没法被绕过。
     */
    fun setPassword(context: Context, plain: String) {
        prefs(context).edit()
            .putString(KEY_PASSWORD_HASH, passwordHashOf(plain))
            .putBoolean(KEY_PASSWORD_ENABLED, true)
            .apply()
    }

    /** 关闭密码：清掉哈希并关掉开关。 */
    fun clearPassword(context: Context) {
        prefs(context).edit()
            .remove(KEY_PASSWORD_HASH)
            .putBoolean(KEY_PASSWORD_ENABLED, false)
            .apply()
    }

    // ---------------- 密码保护范围（四个独立开关，默认都关） ----------------
    //
    // ⚠ 判定"某入口要不要输密码"请统一走
    //   `PasswordGate.needsPassword(context, PasswordGate.Purpose.X)`（它会把总开关和"是否设过密码"
    //   一起算进去）。这里只负责存/取四个布尔值本身。
    //   第十轮新增第 4 个：「保护最近删除」。

    /** 保护「控制台」入口（默认关） */
    fun isPasswordGuardConsole(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PW_GUARD_CONSOLE, false)

    fun setPasswordGuardConsole(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PW_GUARD_CONSOLE, value).apply()
    }

    /** 保护「总应用列表」入口（默认关） */
    fun isPasswordGuardAppList(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PW_GUARD_APPLIST, false)

    fun setPasswordGuardAppList(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PW_GUARD_APPLIST, value).apply()
    }

    /**
     * 保护「桌面编辑模式」入口（默认关）。
     *
     * ⚠ 这个入口的拦截点在 `ui/SquareHomeScreen.kt`（进入编辑模式那一刻），
     *   由那边调用 `PasswordGate.needsPassword(context, PasswordPurpose.DesktopEdit)`。
     */
    fun isPasswordGuardDesktopEdit(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PW_GUARD_DESKTOP_EDIT, false)

    fun setPasswordGuardDesktopEdit(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PW_GUARD_DESKTOP_EDIT, value).apply()
    }

    /**
     * 保护「最近删除」入口（默认关）—— 第十轮新增的第 4 个保护范围。
     *
     * ⚠ 拦截点在 `MainActivity` 里**所有** push `Screen.Trash` 的地方（目前只有一个：
     *   控制台 ④ 页「最近删除」入口），统一走
     *   `guardedPush(Screen.Trash, PasswordGate.Purpose.Trash, "输入密码进入最近删除")`。
     */
    fun isPasswordGuardTrash(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PW_GUARD_TRASH, false)

    fun setPasswordGuardTrash(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PW_GUARD_TRASH, value).apply()
    }

    /**
     * 校验密码。
     *
     * ⚠ **未设置密码时一律返回 true**（调用方应先看 [hasPassword] / [isPasswordEnabled]）——
     *   这样 [com.dsh.sportscam.ui.common.PasswordGateState] 的拦截判断可以写成一行。
     */
    fun verifyPassword(context: Context, plain: String): Boolean {
        val stored = passwordHash(context)
        if (stored.isEmpty()) return true
        return stored == passwordHashOf(plain)
    }

    /** sha256(明文 + 固定盐) 的小写十六进制 */
    private fun passwordHashOf(plain: String): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((plain + PASSWORD_SALT).toByteArray(Charsets.UTF_8))
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }.getOrDefault("")

    // ==================================================================
    // 「非运动相机界面不使用悬浮窗」（第六轮第 1b 项）
    // ==================================================================

    /**
     * ⚠⚠ **第九轮：本设置项已按用户要求删除（控制台 ① 页不再有它），函数仅为兼容而保留。**
     *
     * 为什么废弃（真机结论）：用户想"进了非运动相机界面（应用列表 / 控制台…）就把悬浮窗收起来"，
     * 但**悬浮窗本身就是这些界面的宿主** ⇒ 收起必然先闪出 vivo 桌面、随后被"桌面在前台"判定
     * 重新挂出（日志实证 10 次 `hide → onResume → 挂出 → show` 循环，用户被弹回运动相机主界面）。
     * 现有架构做不到这条需求 ⇒ 删设置项。
     *
     * 本函数**不再由任何界面写入**，只可能读到历史值（默认 false）；
     * 第九轮之后**全仓已无任何读取方**（原读取方 OrientationNotification 已改读 `isOverlayModeEnabled`）
     * ⇒ **下一轮可以安全删除**本函数 / [setOverlayOnlyInCameraMode] / [KEY_OVERLAY_ONLY_IN_CAMERA]。
     */
    fun isOverlayOnlyInCameraMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_ONLY_IN_CAMERA, false)

    fun setOverlayOnlyInCameraMode(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_ONLY_IN_CAMERA, value).apply()
    }

    /**
     * 当前这次"该不该挂悬浮窗/自动拉起"的共享判断 —— **第九轮起恒为 true**。
     *
     * 原因见 [isOverlayOnlyInCameraMode]：那个"非运动相机界面收起悬浮窗"的设置已按用户要求删除，
     * 但 [com.dsh.sportscam.cover.CoverKeeperService]（`want` / `hardNotWant`）与
     * [isAutoPullEnabled] 还在读本函数 —— **保留它**、让它恒允许，调用方零改动就恢复原行为 ✓。
     * 新代码不要再依赖本函数（它只是一层兼容壳）。
     */
    fun overlayAllowedOnCurrentScreen(context: Context): Boolean = true

    // ==================================================================
    // 「定时开关运动相机模式」（第六轮第 3 项）
    // ==================================================================

    /**
     * 读取全部定时计划（**容错**：任何一条坏数据都跳过，永不抛异常、永不为 null）。
     *
     * 序列化格式（见 [CameraModeSchedule.encode]）：
     * ```
     * <id>:<enabled 0/1>:<turnOn 0/1>:<hour>:<minute>:<weekdays>
     * ```
     * 多条用 `;` 连接；`weekdays` 是 ISO 星期数字拼成的串（1=周一 … 7=周日），空串 = 每天。
     * 解析时 hour/minute 会被夹到合法范围、非法条目整条丢弃、id 重复只保留第一条、
     * 最多保留 [MAX_SCHEDULES] 条 —— 手改 prefs 或旧版本数据都不会让界面/服务崩。
     */
    fun cameraModeSchedules(context: Context): List<CameraModeSchedule> {
        val raw = prefs(context).getString(KEY_CAMERA_MODE_SCHEDULES, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val seen = HashSet<Long>()
        return raw.split(SEP)
            .asSequence()
            .mapNotNull { CameraModeSchedule.decode(it) }
            .filter { seen.add(it.id) }
            .take(MAX_SCHEDULES)
            .toList()
    }

    /** 覆盖写入全部定时计划（内部同样会做范围收敛） */
    fun setCameraModeSchedules(context: Context, plans: List<CameraModeSchedule>) {
        val text = plans.take(MAX_SCHEDULES).joinToString(SEP) { it.encode() }
        prefs(context).edit().putString(KEY_CAMERA_MODE_SCHEDULES, text).apply()
    }

    /**
     * 是否存在**启用中**的定时计划。
     *
     * 给执行方用：例如开机广播要决定"要不要把保活服务拉起来，好让计划能跑"时看它。
     * （⚠ 注意 [isAutoPullEnabled] 之类是"生效值"，运动相机模式关掉时会返回 false，
     *   **不能**用它来决定调度是否需要活着 —— 计划本身正是要把模式打开。）
     */
    fun hasEnabledCameraModeSchedules(context: Context): Boolean =
        cameraModeSchedules(context).any { it.enabled }

    /** 计划条数上限（防止坏数据/误操作写进成千上万条把循环拖死） */
    const val MAX_SCHEDULES = 32

    // ==================================================================
    // 「私密应用」（第七轮）—— 包名集合的存储
    // ==================================================================

    /**
     * 「私密应用」功能总开关（默认**关**）。
     *
     * 开启需要输入启动器密码（控制台侧负责门禁），开启后 [privateApps] 才生效。
     */
    fun isPrivateAppsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PRIVATE_APPS_ENABLED, false)

    fun setPrivateAppsEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRIVATE_APPS_ENABLED, value).apply()
    }

    /**
     * **生效的**私密包名集合 —— 桌面隐藏 / 应用列表过滤请用这个。
     *
     * 功能总开关关闭时返回**空集**：这样一关开关，被隐藏的应用立刻回到桌面/列表，
     * 不会出现"关不掉、也进不去"的死局（与第六轮"意愿 / 生效"分离同一套思路）。
     * 用户选过的包名仍然保留在 [privateAppsPreferred] 里，重新开启即恢复。
     */
    fun privateApps(context: Context): Set<String> =
        if (isPrivateAppsEnabled(context)) privateAppsPreferred(context) else emptySet()

    /**
     * ★ **desktop-ui 对接用的名字**（Lead 指定）：与 [privateApps] **完全等价**
     * （生效值：功能总开关关闭时返回空集）。
     *
     * 桌面侧用它来隐藏图标（`SquareHomeScreen.desktopHiddenPackages`），
     * **不要再另存一份集合** —— 这里就是单一真相，私密界面/列表/桌面都读同一份。
     */
    fun privateAppPackages(context: Context): Set<String> = privateApps(context)

    /**
     * **原始**私密包名集合（不看总开关）—— 管理界面（勾选列表）请用这个。
     *
     * 容错：存储被写坏（类型不对/含空串）也不抛异常，最多只取 [MAX_PRIVATE_APPS] 条。
     */
    fun privateAppsPreferred(context: Context): Set<String> = runCatching {
        val raw = prefs(context).getStringSet(KEY_PRIVATE_APPS, emptySet()) ?: emptySet()
        raw.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_PRIVATE_APPS).toSet()
    }.getOrElse { emptySet<String>() }

    /** 覆盖写入私密包名集合（内部会 trim / 去空 / 截断） */
    fun setPrivateApps(context: Context, packages: Set<String>) {
        val clean = packages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_PRIVATE_APPS)
            .toSet()
        // 传进去的是新构造的不可变集合（SharedPreferences 要求 put 之后不要再改这个集合）
        prefs(context).edit().putStringSet(KEY_PRIVATE_APPS, clean).apply()
    }

    /** 标记一个应用为私密（已存在则无副作用） */
    fun addPrivateApp(context: Context, packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        setPrivateApps(context, privateAppsPreferred(context) + pkg)
    }

    /** 取消一个应用的私密标记 */
    fun removePrivateApp(context: Context, packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        setPrivateApps(context, privateAppsPreferred(context) - pkg)
    }

    /**
     * 这个包名现在是不是"私密应用"（**生效值**：功能没启用时恒为 false）。
     *
     * 桌面隐藏图标 / 应用列表过滤都用它，一个调用就够，不需要各自再判总开关。
     */
    fun isPrivateApp(context: Context, packageName: String): Boolean =
        packageName.isNotBlank() && privateApps(context).contains(packageName)

    /**
     * 私密应用里**仍然安装着**的那些（按包名排序）。
     *
     * 给"私密界面"列列表用：卸载过的包名直接忽略（不漏、不崩、不显示幽灵条目）。
     */
    fun privateAppsInstalled(context: Context): List<String> =
        privateApps(context).filter { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0); true }
                .getOrDefault(false)
        }.sorted()

    /** 私密应用条数上限 */
    const val MAX_PRIVATE_APPS = 200

    // ==================================================================
    // 主界面（轮播）显示项 —— 第十轮第 2 项
    // ==================================================================

    /**
     * 主界面（`Screen.Carousel`）左上角是否显示 **24 小时制时钟**（默认 **开**）。
     *
     * ⚠ **渲染不在这里做** —— 由 `ui/CarouselScreen.kt`（desktop-ui）自己读这个 getter 画；
     *   本类只负责存取 + 给控制台 ① 页那个开关用。名字按 Lead 要求固定为
     *   [isShowClockOnHome]，desktop-ui 已按此名对接 ✓。
     */
    fun isShowClockOnHome(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_CLOCK_ON_HOME, true)

    fun setShowClockOnHome(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_CLOCK_ON_HOME, value).apply()
    }
}

/**
 * 一条「定时开关运动相机模式」计划（第六轮第 3 项）。
 *
 * 语义由 `CoverSettings.cameraModeSchedules(context)` 读出、由**执行方**（保活服务/通知栏）
 * 到点套用；本类只描述"什么时候、做什么"，不含任何调度副作用。
 *
 * @param id          稳定标识（编辑/删除用；由界面新增时生成，递增即可）
 * @param enabled     该条是否启用（关闭的计划保留但不执行）
 * @param turnOn      到点时是**开启**运动相机模式（true）还是**关闭**（false）
 * @param hour        0..23（本地时间）
 * @param minute      0..59（界面按 5 分钟步进，但这里允许任意值）
 * @param weekdays    ISO 星期集合：1=周一 … 7=周日；**空集 = 每天**
 */
data class CameraModeSchedule(
    val id: Long,
    val enabled: Boolean,
    val turnOn: Boolean,
    val hour: Int,
    val minute: Int,
    val weekdays: Set<Int>
) {

    /** 形如 `22:30`（日志/界面用） */
    val timeText: String get() = "%02d:%02d".format(hour, minute)

    /** 形如 `每天` / `工作日` / `周末` / `周一,周三` （日志/界面用） */
    val weekdayText: String
        get() = when {
            weekdays.isEmpty() -> "每天"
            weekdays == setOf(1, 2, 3, 4, 5) -> "工作日"
            weekdays == setOf(6, 7) -> "周末"
            else -> weekdays.sorted().joinToString(",") { WEEKDAY_LABELS.getOrElse(it - 1) { "?" } }
        }

    /**
     * 这条计划在当前时刻是否命中（**给执行方用，统一 ISO 星期换算**）。
     *
     * 为什么不交给执行方各自算：`Calendar.DAY_OF_WEEK` 是 1=周日 … 7=周六，
     * 与本类约定的 ISO(1=周一 … 7=周日) 差一位，各写一份很容易错。
     */
    fun matches(calendar: java.util.Calendar): Boolean {
        if (calendar.get(java.util.Calendar.HOUR_OF_DAY) != hour) return false
        if (calendar.get(java.util.Calendar.MINUTE) != minute) return false
        if (weekdays.isEmpty()) return true
        val dow = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        val iso = if (dow == java.util.Calendar.SUNDAY) 7 else dow - 1
        return weekdays.contains(iso)
    }

    /** 序列化：`id:enabled:turnOn:hour:minute:weekdays` */
    internal fun encode(): String = buildString {
        append(id)
        append(':')
        append(if (enabled) 1 else 0)
        append(':')
        append(if (turnOn) 1 else 0)
        append(':')
        append(hour)
        append(':')
        append(minute)
        append(':')
        weekdays.filter { it in 1..7 }.sorted().forEach { append(it) }
    }

    companion object {
        /** 界面/日志用的星期短标签（下标 0 = 周一） */
        val WEEKDAY_LABELS: List<String> = listOf("一", "二", "三", "四", "五", "六", "日")

        /** 常用快速选择：每天 / 工作日 / 周末 */
        val WEEKDAY_PRESETS: List<Pair<String, Set<Int>>> = listOf(
            "每天" to emptySet(),
            "工作日" to setOf(1, 2, 3, 4, 5),
            "周末" to setOf(6, 7)
        )

        /** 容错解析一条；坏数据返回 null（绝不抛） */
        internal fun decode(raw: String): CameraModeSchedule? {
            val parts = raw.trim().split(':')
            if (parts.size < 6) return null
            val id = parts[0].trim().toLongOrNull() ?: return null
            val enabled = parts[1].trim() == "1"
            val turnOn = parts[2].trim() == "1"
            val hour = parts[3].trim().toIntOrNull()?.coerceIn(0, 23) ?: return null
            val minute = parts[4].trim().toIntOrNull()?.coerceIn(0, 59) ?: return null
            val weekdays = parts[5].trim().mapNotNull { c ->
                c.toString().toIntOrNull()?.takeIf { it in 1..7 }
            }.toSet()
            return CameraModeSchedule(id, enabled, turnOn, hour, minute, weekdays)
        }
    }
}

/**
 * 「当前前台是不是运动相机界面」的**共享状态**（第六轮第 1b 项）。
 *
 * ⚠ **第九轮起已废弃、对行为没有影响**：那个"非运动相机界面不使用悬浮窗"的设置项已按用户要求
 *   删除，[CoverSettings.overlayAllowedOnCurrentScreen] 现在**恒 true**，不再读这一位。
 *   保留本对象只是为了不动 `MainActivity` 里那段白名单写入（Lead 要求"保持现状、别再 churn"）。
 *   下面这段历史语义说明保留作踩坑记录。
 *
 * ⚠⚠ **语义在第七轮被修正过（务必按新语义理解，名字保留是为了不动 recorder-statusbar 的代码）**：
 *   它现在表示 **"我们启动器自己的界面整体是不是在前台"**，**不是**"当前页是不是轮播主界面"。
 *   · **白名单**：Carousel / Desktop / AppList / Console / Gallery / Videos / Viewer / Trash /
 *     DisplaySettings / ScreenshotRecord —— 我们导航栈里的**任何一页**都算 **true** ✓。
 *   · 为什么必须这样（真机事故）：悬浮窗模式下，应用列表 / 控制台 / 相簿**本身就画在 2038
 *     悬浮窗里**（悬浮窗是它们的宿主）。旧判据"非 Carousel → false"会把用户正在看的界面
 *     **自己**收掉，再被"回到桌面自动拉起"拉回来 ⇒ 收起/拉起死循环 ⇒ **用户被锁死** ✗✗。
 *   · 只有"我们的界面整体不在前台"才可以是 false（由 Activity 的 onResume/onPause 维护，
 *     且 **悬浮窗还在显示我们的界面时不置 false**）；
 *     "别的应用在副屏前台"由保活服务自己的前台判定负责，不需要这一位承担。
 *
 * 谁写：`SportsCamApp`（每次组合，白名单判断）+ `BaseLauncherActivity.onResume/onPause`。
 * 谁读：`CoverKeeperService` / `CoverOverlayWindow`（recorder-statusbar 维护）。
 *
 * 放在 `cover/` 包而不是 `ui/`：避免 cover → ui 的反向依赖（ui 本来就依赖 cover ✓）。
 */
object CoverScreenGate {

    /**
     * true = **我们启动器自己的界面整体在前台**（白名单：我们导航栈里的任意一页都算 true）。
     *
     * 默认 **true**：悬浮窗刚挂出、Compose 还没来得及写之前，宁可先按"允许"处理，
     * 免得出现"该显示时被自己的开关挡掉"的空窗。
     * `@Volatile`：写入在 Compose / Activity 主线程，读取可能在保活服务/快循环线程。
     */
    @Volatile
    var currentScreenIsCarousel: Boolean = true
}
