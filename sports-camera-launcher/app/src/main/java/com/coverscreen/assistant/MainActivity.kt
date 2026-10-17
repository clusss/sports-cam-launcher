/*
 * ============================================================================
 *  兼容类名：原版《超级小副屏》的入口 Activity。
 *
 *  为什么需要它：
 *  vivo 的「快捷启动 / SmartKey」很可能把要启动的应用保存成**精确 ComponentName**
 *  （包名 + 类名），而不只是包名。小副屏原来的入口是
 *      cn.pashiguoke.subscreen/com.coverscreen.assistant.MainActivity
 *  我们现在沿用了同一个包名（为了通过 ROM 的副屏白名单），但类名是
 *  com.dsh.sportscam.MainActivity —— 于是快捷启动会去拉起一个**已不存在**的类：
 *  表现就是"放一段启动动画，然后什么都不发生，回到锁屏"。
 *
 *  在这里原样提供同名类，等于把那个历史 ComponentName 接回来：
 *  无论 vivo 存的是包名还是精确类名，都能正确启动到我们的启动器。
 *  这个类本身不做任何事，只是继承真正的入口（跳板）行为。
 * ============================================================================
 */
package com.coverscreen.assistant

/**
 * 原版入口类名。行为完全等同于 [com.dsh.sportscam.MainActivity]（入口跳板）。
 */
class MainActivity : com.dsh.sportscam.MainActivity()
