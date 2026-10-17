package com.dsh.sportscam.cover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.util.Log
import android.widget.RemoteViews
import com.dsh.sportscam.R

/**
 * 通知栏「方向开关」（**独立一条**，与保活通知分开）。
 *
 * 对齐《超级小副屏》的 OrientationNotificationHelper：
 * 自定义 RemoteViews，一行 5 个按钮（横屏 / 反向横屏 / 竖屏 / 反向竖屏 / 跟随系统）。
 *
 * ⚠ 坑：用了 `setCustomContentView()` 之后，`setContentIntent()` **不再生效**，
 *   点通知主体没反应。所以本布局里自带一行标题，并把「进入应用」的 PendingIntent
 *   挂到标题控件上（见 build()）。保活通知则用标准内容视图，主体点击天然可用。
 */
object OrientationNotification {

    private const val TAG = "OrientationNotif"

    const val CHANNEL_ID = "orientation_control"

    /** 方向通知独立 id（与保活通知 1001 分开） */
    const val NOTIFICATION_ID = 1002

    /**
     * ★★ 第五轮第 1 项根因（**方向通知故意不 setGroup**，靠保活通知那边分组来避开自动分组）
     *
     * 用户实测："开启截屏后通知栏只剩保活通知，方向/截屏/录屏按钮全都不见" ✗。
     *
     * 真机证据（2026-10-11，`adb shell dumpsys notification --noredact` + 副屏截图）：
     *   · id **不冲突**：保活=1001、方向=1002，两条 NotificationRecord **都在**（所以不是覆盖 ✗）；
     *   · 两条通知的 `groupKey` 都是 `…|g:ranker_group` ✓ → **NMS 把它们自动合并成一个组**；
     *   · 副屏截图肉眼可见：通知栏里只有**一行折叠的分组摘要**（右上角分组角标 "2"），
     *     显示的是一行系统拼出来的标准标题/正文；那个摘要的第二行正好是保活通知的文案
     *     ⇒ 用户看到的"只剩保活通知" ✓。自定义视图（方向 5 键 + 截屏 + 录屏）在折叠态**根本不渲染** ✗。
     *
     * 机制：`NotificationManagerService` 只对 **`getGroup() == null`** 的通知做自动分组，
     * 而且要有 **≥2 条**未分组通知才会造出那条 ranker_group 摘要。
     * 所以这里的策略是：
     *   · **保活通知显式 setGroup(KEEPER_GROUP_KEY)** → 它不再参与自动分组 ✓；
     *   · **方向通知保持 group=null** → 全应用"未分组通知"只剩它一条 ⇒ 没有任何东西可分 ✗✓，
     *     它必定作为**独立一行**渲染自定义视图（不依赖"单子分组怎么显示"这种不确定行为）✓。
     *
     * 这就是本文件**故意不调用 setGroup()** 的原因 —— 改动前请先读上面的证据链。
     */

    const val ACTION_ORIENTATION = "com.dsh.sportscam.ACTION_ORIENTATION"
    const val EXTRA_ORIENTATION = "orientation"

    /** 通知栏「截屏副屏画面」按钮 */
    const val ACTION_SCREENSHOT = "com.dsh.sportscam.ACTION_SCREENSHOT"

    /** 录屏按钮的**字形**（第六轮定稿：布局是 TextView，走 `setTextViewText` 这条路） */
    private const val TEXT_RECORD_START = "\u25B6"   // ▶
    private const val TEXT_RECORD_STOP = "\u25A0"    // ■
    private const val TEXT_RECORD_BUSY = "\u22EF"    // ⋯
    private const val TEXT_SHOT_ICON = "\u2702"      // ✂

    /**
     * 按钮图标（**仅当布局是 ImageView 时才用**；第六轮已把布局定稿为 TextView，
     * 这三个 `ICON_RECORD_*` 只是"万一有人改回 ImageView"的备用路径 ✓）。
     *
     * ⚠⚠ 这台 ROM 上**三条**"图标路线"都被实测否掉，别再回退到它们 ✗：
     *   ① **vector drawable**：布局里只要出现 vector，整条自定义视图**渲染失败 ⇒
     *      通知栏那一行直接消失** ✗（通知还在 NMS 里，`dumpsys` 仍能查到 id=1002 ——
     *      极易误判成"没发出来"）；第四轮换成 vector 剪刀后就是这个症状 ✓；
     *   ② **PNG 位图 ImageView**：行能显示，但 SystemUI 会把位图**着色/反色**
     *      ⇒ 近黑图标渲染成**白色** ✗（Lead 像素测量：换图前后图标区近白像素都是 352 ✓）；
     *   ③ **字形设 #111111 黑**：**仍被渲染成白** ✗（近白 352→358 没降 ✗）。
     * 最终定稿（用户选的 A 方案）= **与方向键同款深色药丸 + 白色字形** ✓，
     * 见 `res/layout/notification_orientation.xml`（两个按钮都 `style="@style/NotifButton"`）。
     */
    // ⚠ 不能用 const：R.drawable.* 在 AGP 8 下不是编译期常量（构建会报
    //   "const 'val' initializer should be a constant value"），用 private val 即可。
    private val ICON_RECORD_START = R.drawable.ic_notif_play
    private val ICON_RECORD_STOP = R.drawable.ic_notif_stop
    private val ICON_RECORD_BUSY = R.drawable.ic_notif_busy

    /**
     * 按钮颜色（**第六轮定稿 A 方案：白字形 + 深色药丸底**）。
     *
     * 为什么是**白**：本机 SystemUI 会**强制着色**自定义视图里的图标内容 ✗ ——
     * vector（整行消失 ✗）、PNG 位图（反色成白 ✗）、字形设 #111111 黑（仍是白 ✗，像素 352→358 没降 ✓）
     * 三条路都实测失败，所以"浅灰底 + 黑图标"在本机做不到 ✗。
     * 用户明确选 A：**与 5 个方向键完全同款** —— 深色药丸（`@drawable/bg_notif_button`，
     * solid #FF23262B + 圆角 8dp + 1dp #33FFFFFF 描边）+ **白色字形** ✓。
     * 布局那两个按钮已是 `style="@style/NotifButton"`（白 #FFFFFFFF / 12sp / padding 9dp / weight=1 ✓），
     * 这里只是按三态切换颜色（`setTextViewText` + `setTextColor`，均在白名单 ✓）。
     *
     * ⚠ 绝不用 `setAlpha`/`setFloat`（本项目硬约束：宿主 apply 会抛 ActionException ✗）。
     */
    private const val COLOR_RECORD_START = 0xFFFFFFFF.toInt()   // 白 ▶（同方向键字色）
    private const val COLOR_RECORD_STOP = 0xFFFF3B30.toInt()    // 红 ■（用户明确要红）
    private const val COLOR_RECORD_BUSY = 0xFFFFFFFF.toInt()    // 白 ⋯
    private const val COLOR_SHOT_ICON = 0xFFFFFFFF.toInt()      // 白 ✂（同方向键字色）

    /** 布局里是否有录屏按钮（防御：万一以后有人把 notif_record 从 XML 删了，不至于崩） */
    @Volatile
    private var layoutRecordChecked = false

    @Volatile
    private var layoutHasRecord = true

    /** 控件类型探测结果 */
    private enum class ViewKind { IMAGE, TEXT, UNKNOWN }

    private val kindCache = java.util.concurrent.ConcurrentHashMap<Int, ViewKind>()

    /**
     * 探测某个 id 在布局里到底是什么控件（**只用于决定用哪套白名单方法**）。
     *
     * ★★ 为什么要这么小心（第五轮补充，Lead 指出的坑 + 《开发前必读》第 3 节 ①）：
     *   RemoteViews 的白名单方法**必须与控件类型匹配**：
     *     · `setImageViewResource` 只能用于 ImageView ✗ 用在 TextView 上宿主 apply 直接抛
     *       `ActionException`（"View is not an ImageView" 之类）；
     *     · `setTextViewText` / `setTextColor` 只能用于 TextView ✗ 用在 ImageView 上同样抛。
     *   一旦 apply 抛异常，**整条自定义视图渲染失败** ⇒ 用户看到"按钮全没了"（甚至以为通知消失了）✗。
     *   所以：类型探测失败 / 未知时，**任何方法都不调**（宁可不更新那颗按钮，也绝不猜着调 ✗）。
     *
     * 说明：探测就是在本进程 inflate 一次布局后 `findViewById(id)` 看实际类型
     * （inflate 失败 → UNKNOWN）。结果按 id 缓存，最多算 3 次。
     */
    private fun viewKind(context: Context, id: Int): ViewKind = kindCache.getOrPut(id) {
        runCatching {
            val view = android.view.LayoutInflater.from(context)
                .inflate(R.layout.notification_orientation, null)
            when (view.findViewById<android.view.View>(id)) {
                is android.widget.ImageView -> ViewKind.IMAGE
                is android.widget.TextView -> ViewKind.TEXT
                else -> ViewKind.UNKNOWN
            }
        }.getOrElse {
            CoverDebugLog.log(
                "Notif",
                "控件类型探测失败（id=$id，inflate 异常 ${it::class.java.simpleName}）→ " +
                    "本次不更新该按钮（绝不猜着调白名单方法）"
            )
            ViewKind.UNKNOWN
        }
    }

    private fun manager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel(context: Context) {
        val nm = manager(context)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "方向控制",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "在通知栏直接切换副屏方向"
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buttonIntent(context: Context, orientation: Int): PendingIntent {
        val intent = Intent(context, OrientationActionReceiver::class.java).apply {
            action = ACTION_ORIENTATION
            putExtra(EXTRA_ORIENTATION, orientation)
        }
        // requestCode 带上方向值，避免 5 个按钮共用一个 PendingIntent
        return PendingIntent.getBroadcast(
            context,
            1000 + orientation,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 截屏按钮 → 启动**透明 Activity**（[CoverShotActivity]），而不是广播。
     *
     * 为什么必须是 Activity：**启动 Activity 会被系统自动收起通知栏** ✓；
     * 广播不会 ✗ —— 上一版就是广播，结果通知栏一直盖在副屏上，截图里全是通知栏。
     *
     * ⚠ 还必须投到**副屏**：通知栏长在副屏上，Activity 也得在副屏启动，
     *   系统收起的才是副屏那条通知栏（否则落到没面板的主屏，通知栏纹丝不动）。
     */
    private fun screenshotIntent(context: Context): PendingIntent {
        val intent = Intent(context, CoverShotActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val coverId = CoverDisplay.coverDisplayId(context)
        val options = if (coverId != android.view.Display.DEFAULT_DISPLAY) {
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(coverId).toBundle()
        } else {
            null
        }
        return if (options != null) {
            PendingIntent.getActivity(
                context, 3000, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options
            )
        } else {
            PendingIntent.getActivity(
                context, 3000, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    /**
     * 录屏按钮 → **发给保活服务的 PendingIntent**（不是广播）。
     *
     * 为什么不复用 [OrientationActionReceiver]：录屏的启停要跑 shell（Shizuku）往返、
     * 还要等 1~2 秒收尾，属于"长时间动作"，放在 Service 里最合适
     * （接收器里必须 goAsync 才行，而保活服务本来就常驻）。
     *
     * `getForegroundService`：服务若已被系统回收，这一次点击能把它重新拉起
     * （普通 startService 在后台会被 Android 8+ 拒绝）。
     */
    private fun recordToggleIntent(context: Context): PendingIntent {
        val intent = Intent(context, CoverKeeperService::class.java)
            .setAction(CoverKeeperService.ACTION_RECORD_TOGGLE)
        return PendingIntent.getForegroundService(
            context,
            4001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 布局里到底有没有录屏按钮。
     *
     * 这是**防御性**检查：RemoteViews 对不存在的 id 调 setViewVisibility /
     * setOnClickPendingIntent，宿主 apply 时会抛 `ActionException`，桌面/通知栏上
     * 会直接显示"加载窗口小工具时出现问题"（本项目踩过）。正常情况它总在
     * （`notification_orientation.xml` 里有 `@+id/notif_record`），
     * 这里只是防止以后有人把那个视图删掉后**静默**变成通知栏报错。
     */
    private fun hasRecordButton(context: Context): Boolean {
        if (layoutRecordChecked) return layoutHasRecord
        layoutHasRecord = runCatching {
            val view = android.view.LayoutInflater.from(context)
                .inflate(R.layout.notification_orientation, null)
            view.findViewById<android.view.View>(R.id.notif_record) != null
        }.getOrDefault(true)
        layoutRecordChecked = true
        return layoutHasRecord
    }

    // ★ 第九轮**最终定稿**：本通知里的「退出」药丸已按用户要求删除 ✗（原
    //   `exitOverlayIntent()` / `hasExitButton()` / `lastExitVisible` / 布局 id `notif_exit`
    //   一并清理 ✓，不留"可见但没有点击处理"的按钮 ✗）。
    //   ★ 用户最终决定：**不提供任何逃生通道** ✗ ——
    //     · 通知栏两处出口都删了（本通知药丸 ✗ + 保活通知(1001) 那条 action ✗）；
    //     · 视频1 页右滑方案也已撤回 ✗；
    //     · ⇒ 用户正常关闭悬浮窗模式的**唯一途径** = 控制台 ③ 页「悬浮窗防退出模式」开关 ✓。
    //   ⚠ 所以**不要**再往这条通知里加任何"退出/脱困"按钮 ✗。

    /** 进应用 */
    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            2000,
            Intent(context, com.dsh.sportscam.MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun labelOf(orientation: Int): String = when (orientation) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "横屏"
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> "反向横屏"
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> "竖屏"
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> "反向竖屏"
        else -> "跟随系统"
    }

    /**
     * 构建方向通知（**带保险**）。
     *
     * ★ 第五轮补充（Lead 要求）：自定义视图的构建整体 try/catch ——
     *   任何 RemoteViews 侧的意外（白名单方法/控件类型不匹配、布局被改、
     *   资源缺失……）都**不能**让整条通知消失 ✗。
     *   失败时退回一条**纯文字通知**（标准模板、不含自定义视图）✓ 并落盘
     *   `[Notif] 自定义视图构建失败 …` ✓ —— 用户最多"少几个按钮"，不会"整条没了"。
     */
    fun build(context: Context): Notification {
        ensureChannel(context)
        return runCatching { buildWithCustomView(context) }.getOrElse { t ->
            CoverDebugLog.log(
                "Notif",
                "自定义视图构建失败 → 退回纯文字通知：${t::class.java.simpleName} ${t.message}"
            )
            buildPlainText(context)
        }
    }

    /**
     * 纯文字兜底通知：**不使用** setCustomContentView / setCustomBigContentView，
     * 也不碰任何自定义控件，因此**不可能**因为白名单/类型问题而 apply 失败 ✓。
     * 进应用的能力保留（标准模板下 contentIntent 本来就有效 ✓）。
     */
    private fun buildPlainText(context: Context): Notification {
        val current = CoverSettings.orientationLock(context)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("屏幕方向 · ${labelOf(current)}")
            .setContentText("点这里进入启动器；方向可在控制台/通知栏切换（自定义按钮暂不可用）")
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .build()
    }

    private fun buildWithCustomView(context: Context): Notification {
        val current = CoverSettings.orientationLock(context)

        // ★★ 两套视图（解决"开关一开按钮就变扁、难按"）：
        //    通知的**收起态**自定义视图高度有限，硬塞两行会被系统压缩变形（用户实测）；
        //    所以：收起态 = 标题 + 方向行；**展开态**（下拉后）= 再加一行截屏。
        val small = RemoteViews(context.packageName, R.layout.notification_orientation)
        val big = RemoteViews(context.packageName, R.layout.notification_orientation)

        val title = "运动相机 · 副屏（${labelOf(current)}）  点此进入"
        for (v in listOf(small, big)) {
            // 标题行：显示当前方向 + 点它进应用（自定义视图里必须自己挂点击）
            v.setTextViewText(R.id.notif_title, title)
            v.setOnClickPendingIntent(R.id.notif_title, openAppIntent(context))
            v.setOnClickPendingIntent(
                R.id.orient_landscape,
                buttonIntent(context, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            )
            v.setOnClickPendingIntent(
                R.id.orient_reverse_landscape,
                buttonIntent(context, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
            )
            v.setOnClickPendingIntent(
                R.id.orient_portrait,
                buttonIntent(context, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            )
            v.setOnClickPendingIntent(
                R.id.orient_reverse_portrait,
                buttonIntent(context, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT)
            )
            v.setOnClickPendingIntent(
                R.id.orient_unspecified,
                buttonIntent(context, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            )
        }

        // 截屏行：只有「开关打开 **且** 无障碍服务在跑」才显示（截屏走无障碍 takeScreenshot）。
        // 若发现"开关还开着但服务已掉"，顺手关掉（自愈，保证状态一致）。
        val shotEnabled = CoverSettings.isScreenshotEnabled(context)
        val shotAvailable = shotEnabled && CoverTriggerAccessibilityService.running
        if (shotEnabled && !shotAvailable) {
            runCatching { CoverSettings.setScreenshotEnabled(context, false) }
            CoverDebugLog.log("Shot", "通知构建时发现无障碍不在 → 自动关闭截屏开关")
        }
        // ★ 只隐藏**按钮本身**，不要隐藏整行：
        //   第二行还放着"点此进入"的标题，整行藏掉会连入口一起消失。
        //   收起态与展开态**都**按开关显隐 —— 下拉通知栏默认看到的是收起态，
        //   若只在大视图里显示按钮，用户就会以为"开关开了但按钮不出现"。
        for (v in listOf(small, big)) {
            v.setViewVisibility(
                R.id.notif_screenshot,
                if (shotAvailable) android.view.View.VISIBLE else android.view.View.GONE
            )
            if (shotAvailable) {
                // ★★ 第五轮补充（Lead 指出的坑 + 硬约束第 3 节 ①）：方法必须与控件类型匹配！
                //   · 布局里 notif_screenshot 是 **ImageView** → 只能 setImageViewResource ✓；
                //   · 若探测到 TEXT（布局被改回 TextView）→ 写文字 ✓；
                //   · 探测不到（UNKNOWN）→ **什么方法都不调**，宁可不更新那颗按钮 ✗ 也绝不猜着调 ✗，
                //     因为类型不匹配会让宿主 apply 抛 ActionException → **整条自定义视图全废** ✗。
                // ★★ 第六轮定稿：布局是 **TextView + 字形**，所以实际走 TEXT 分支 ——
                //   字形 `✂`（U+2702）+ **黑色**（卡片浅灰底，深色才看得见 ✓）。
                //   方法↔类型铁律不变：IMAGE 分支只给 ImageView 用、TEXT 分支只给 TextView 用，
                //   UNKNOWN 时**一个方法都不调**（否则宿主 apply 抛 ActionException → 整条视图全废 ✗）。
                when (viewKind(context, R.id.notif_screenshot)) {
                    ViewKind.IMAGE ->
                        v.setImageViewResource(R.id.notif_screenshot, R.drawable.ic_notif_scissors)
                    ViewKind.TEXT -> {
                        v.setTextViewText(R.id.notif_screenshot, TEXT_SHOT_ICON)
                        v.setTextColor(R.id.notif_screenshot, COLOR_SHOT_ICON)
                    }
                    ViewKind.UNKNOWN ->
                        CoverDebugLog.log(
                            "Notif",
                            "notif_screenshot 类型未知 → 跳过图标更新（避免 ActionException 让整条通知失效）"
                        )
                }
                v.setOnClickPendingIntent(R.id.notif_screenshot, screenshotIntent(context))
            }
        }

        // ---- 录屏按钮（第二轮 D1）：未录屏 ▶ / 录屏中 ■，只有一个键 ----
        //
        // 显示条件（两个都要满足）：
        //   ① 用户开了「通知栏录屏按钮」（默认关）；
        //   ② Shizuku 可用 —— 录屏走 `screenrecord --display-id`，只有 shell 能跑，
        //      没有 Shizuku 时按钮**隐藏**（不是点了没反应），原因写在
        //      「截屏与录屏」界面里。
        // ⚠ 这里**不**自动关闭用户的开关（不像截屏那样自愈）：
        //   Shizuku 的 binder 会随 adb 断开/重连起落，开关状态应当保留，
        //   等 Shizuku 回来按钮自己就出现了。
        val recEnabled = RecordSettings.isRecordEnabled(context)
        val shizukuReady = ShizukuShell.isUsable
        val recording = CoverScreenRecorder.isRecording(context)
        // ★ 第四轮：合并音视频期间显示"⋯"，并且**仍然显示**（用户要知道还在处理）
        val muxing = CoverScreenRecorder.isMuxing
        val hasRecord = hasRecordButton(context)
        val showRecord = hasRecord && (muxing || (recEnabled && shizukuReady))
        if (recEnabled && !shizukuReady && !muxing) {
            CoverDebugLog.log(
                "Rec",
                "通知构建：录屏开关已开但 Shizuku 不可用 → 隐藏录屏按钮（不自动关开关）"
            )
        }
        if (hasRecord) {
            // ★★ 同上：notif_record 在布局里是 **ImageView** → 三态都走 setImageViewResource ✓；
            //   探测到 TEXT 才写文字；UNKNOWN 时**什么都不调**（防止类型不匹配把整条通知搞废 ✗）。
            val kind = viewKind(context, R.id.notif_record)
            val icon = when {
                muxing -> ICON_RECORD_BUSY
                recording -> ICON_RECORD_STOP
                else -> ICON_RECORD_START
            }
            val text = when {
                muxing -> TEXT_RECORD_BUSY
                recording -> TEXT_RECORD_STOP
                else -> TEXT_RECORD_START
            }
            val color = when {
                muxing -> COLOR_RECORD_BUSY
                recording -> COLOR_RECORD_STOP
                else -> COLOR_RECORD_START
            }
            for (v in listOf(small, big)) {
                v.setViewVisibility(
                    R.id.notif_record,
                    if (showRecord) android.view.View.VISIBLE else android.view.View.GONE
                )
                if (showRecord) {
                    when (kind) {
                        ViewKind.IMAGE -> v.setImageViewResource(R.id.notif_record, icon)
                        ViewKind.TEXT -> {
                            v.setTextViewText(R.id.notif_record, text)
                            v.setTextColor(R.id.notif_record, color)
                        }
                        ViewKind.UNKNOWN -> CoverDebugLog.log(
                            "Notif",
                            "notif_record 类型未知 → 跳过状态更新（避免 ActionException 让整条通知失效）"
                        )
                    }
                    v.setOnClickPendingIntent(R.id.notif_record, recordToggleIntent(context))
                }
            }
            if (kind == ViewKind.UNKNOWN) {
                CoverDebugLog.log(
                    "Rec",
                    "通知构建：notif_record 类型探测为 UNKNOWN（布局被改过？）→ " +
                        "按钮可能显示为布局默认图标，但不会影响整条通知"
                )
            }
        }
        if (recording) {
            // 标题里也带一句"录制中"：■ 这个图标很小，怕用户看错状态
            val recTitle = "运动相机 · 副屏（录制中）  点此进入"
            for (v in listOf(small, big)) v.setTextViewText(R.id.notif_title, recTitle)
        }

        // ★ 第九轮**最终定稿**：「退出」药丸整块已按用户要求删除 ✗（连显隐/点击/日志一起清 ✓）。
        //   现在本通知只有：方向 5 键 + ✂（开关开启时）+ ▶/■/⋯（开关开启时）+ 标题 ✓。
        //   ★ 用户最终决定：不提供任何逃生通道 ✗（通知栏两处都删 ✗ + 视频页右滑也撤回 ✗）
        //     ⇒ 用户正常关闭悬浮窗模式的**唯一途径** = 控制台 ③ 页「悬浮窗防退出模式」开关 ✓。

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("屏幕方向 · ${labelOf(current)}")
            .setContentText("点标题进入启动器；下方按钮改副屏方向")
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setContentIntent(openAppIntent(context))   // 标准视图下有效（保险）
            .setOngoing(true)
            // ★★ 第五轮第 1 项：这里**故意不 setGroup(...)**！
            //   让本通知保持 group=null，同时保活通知显式分组 →
            //   全应用只剩这一条"未分组通知" ⇒ 系统没有可自动合并的对象 ⇒ 它必定独立成行，
            //   自定义视图（方向 5 键 + 截屏 + 录屏）正常渲染 ✓。
            //   完整证据链见文件顶部 GROUP 相关注释。
            .build()
    }

    /**
     * 显示 / 刷新方向通知。
     *
     * ★ 第五轮补充（Lead 要求）：日志必须**如实**区分成功/失败 ——
     *   `show OK` = `notify()` 真的返回了；`show FAILED` = 抛异常（带**异常原文**）。
     *   另外成功后再用 [NotificationManager.getActiveNotifications] 自检一次
     *   （id 在不在系统里），把"到底有没有进 NMS"这条唯一权威信息也落盘，
     *   免得以后又靠 dumpsys 反推 ✗。
     *
     * @param reason 调用原因（尽量填：`开关打开`/`录屏开始`/`1s 守卫`…）
     */
    fun show(context: Context, reason: String = "") {
        val n = runCatching { build(context) }.getOrElse { t ->
            CoverDebugLog.log(
                "Notif",
                "build 失败 id=$NOTIFICATION_ID 原因=${reason.ifEmpty { "-" }} " +
                    "${t::class.java.simpleName} ${t.message}"
            )
            return
        }
        val mgr = manager(context)
        runCatching { mgr.notify(NOTIFICATION_ID, n) }
            .onSuccess {
                // 自检：刚 notify 过的 id 是否真的进了系统通知列表（权威判据）
                val active = runCatching {
                    mgr.activeNotifications.any { it.id == NOTIFICATION_ID }
                }.getOrNull()
                CoverDebugLog.log(
                    "Notif",
                    "show OK id=$NOTIFICATION_ID channel=$CHANNEL_ID " +
                        "方向=${labelOf(CoverSettings.orientationLock(context))} " +
                        "系统里已记录=${active ?: "未知"} 原因=${reason.ifEmpty { "-" }}"
                )
            }
            .onFailure { t ->
                CoverDebugLog.log(
                    "Notif",
                    "show FAILED id=$NOTIFICATION_ID 原因=${reason.ifEmpty { "-" }} " +
                        "异常=${t::class.java.name}: ${t.message}"
                )
                Log.w(TAG, "显示方向通知失败", t)
            }
    }

    /**
     * 只在该通知被用户开启时才刷新（录屏状态变化时用）。
     *
     * 为什么要有这个包装：录屏开始/结束后要立刻把 ▶ 换成 ■，但如果用户
     * **关掉了方向通知**，直接 [show] 会把整条通知又发出来 —— 那就是"关不掉的开关"。
     * ⚠ 注意这里判的是**方向通知自己的开关**；截屏/录屏按钮各有各的开关，
     *   跟"这条通知要不要存在"无关 ✓（第五轮第 1 项要求：任何开关状态下方向通知都要在）。
     */
    fun showIfEnabled(context: Context, reason: String = "") {
        if (!CoverSettings.isOrientationNotifEnabled(context)) {
            CoverDebugLog.log(
                "Notif",
                "showIfEnabled 跳过（用户关掉了方向通知开关）原因=${reason.ifEmpty { "-" }}"
            )
            return
        }
        show(context, reason)
    }

    fun cancel(context: Context, reason: String = "") {
        runCatching { manager(context).cancel(NOTIFICATION_ID) }
        CoverDebugLog.log(
            "Notif",
            "cancel id=$NOTIFICATION_ID 原因=${reason.ifEmpty { "-" }}（调用=${callerHint()}）"
        )
    }

    /** 谁调了 cancel —— 排查"通知莫名消失"时必须的信息 */
    private fun callerHint(): String = runCatching {
        Throwable().stackTrace.firstOrNull { !it.className.startsWith("com.dsh.sportscam.cover.OrientationNotification") }
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "-"
    }.getOrDefault("-")
}
