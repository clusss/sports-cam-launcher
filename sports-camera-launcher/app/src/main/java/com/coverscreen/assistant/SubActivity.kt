/*
 * ============================================================================
 *  兼容类名：原版《超级小副屏》的副屏界面 Activity。
 *
 *  与同目录的 [MainActivity] 同一个理由 —— vivo 快捷启动/外部跳转可能保存了
 *  精确 ComponentName。原版是：
 *      cn.pashiguoke.subscreen/com.coverscreen.assistant.SubActivity
 *  行为完全等同于 [com.dsh.sportscam.CoverHomeActivity]（副屏界面，独立任务栈）。
 * ============================================================================
 */
package com.coverscreen.assistant

/**
 * 原版副屏界面类名。行为完全等同于 [com.dsh.sportscam.CoverHomeActivity]。
 */
class SubActivity : com.dsh.sportscam.CoverHomeActivity()
