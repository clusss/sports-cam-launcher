package com.dsh.sportscam.cover

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志**落盘**到外部私有目录：
 *   /sdcard/Android/data/cn.pashiguoke.subscreen/files/cover_debug.log
 *
 * 为什么不用 Log.d：
 * 这台 ROM（OriginOS）会把应用自身的 Log.d 从 logcat 里过滤掉 —— 排查"快捷启动
 * 为什么没进界面"时，只能看到系统侧的 wm_* 事件，看不到应用内部走了哪个分支，
 * 于是只能靠推测（已经因此走过好几个弯路）。落盘是唯一能拿到应用内部决策的办法，
 * 而且这个目录 adb 可以直接读。
 *
 * 文件超过 256KB 自动清空，避免无限增长。
 */
object CoverDebugLog {

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return
            logFile = File(dir, "cover_debug.log").also { f ->
                if (f.exists() && f.length() > 256 * 1024) f.writeText("")
            }
        }
    }

    fun log(tag: String, msg: String) {
        runCatching {
            val f = logFile ?: return
            f.appendText("${fmt.format(Date())} [$tag] $msg\n")
        }
    }
}
