package com.dsh.sportscam.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate

/**
 * 竖屏下把内容**逆时针旋转 90°**，并交换测量宽高 —— 也就是"竖屏里塞一个横屏界面"。
 *
 * 用途（按需求）：
 *  · 运动相机主界面：竖屏时图标整体逆时针转 90°，横向翻页随之变成"上下滚动"；
 *  · 相簿 1 / 视频 1：竖屏时依然能**横屏看照片**（用户把手机转过来看即可），
 *    布局按横屏重新排布，不做一套竖屏专用交互。
 *
 * 实现要点：`Modifier.rotate` 只影响绘制，**不影响测量** —— 所以必须同时把宽高互换
 * （外层量到 422×682，内层按 682×422 布局），否则内容会按竖屏尺寸排完再被转出屏幕。
 */
@Composable
fun LandscapeInPortrait(
    enabled: Boolean,
    content: @Composable BoxWithConstraintsScope.() -> Unit
) {
    if (!enabled) {
        // 横屏路径也要给出 BoxWithConstraintsScope（content 是接收者 lambda），
        // 尺寸与普通布局一致，不做任何旋转
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) { content() }
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        // ★ 内层也用 BoxWithConstraints：把**交换后的尺寸**（682×422）作为接收者作用域
        //   暴露给 content。
        //
        //   为什么必须这样：content 里的 `maxWidth`/`maxHeight` 原本会解析到**外层**
        //   竖屏坐标系（422×682），于是 `(maxWidth - pageWidth)/2` 之类的居中/内边距
        //   全按错误的轴计算 —— 旋转后的画布与分页器可绘制区对不齐，表现为屏幕上
        //   "上下两片遮罩、中间只剩一小块可操作区域"。用接收者作用域把它对齐即可。
        BoxWithConstraints(
            modifier = Modifier
                // ★★ 必须用 requiredSize，**不能用 size**（这是"上下两片遮罩"的真凶）。
                //
                //   `Modifier.size` 会把请求尺寸再和**上层约束**取交集：竖屏时外层是
                //   422×682，"要 682 宽"被压回 422 → 量出来是 **422×422 的方块**，
                //   屏幕上下各留一条 ~130px 的死区。
                //   实测物证（竖屏 422×682，`uiautomator dump` + 截图像素剖面）：
                //     · 分页器 ScrollView 的 bounds = [0,130][422,552]（422×422，不是 422×682）；
                //     · 实际画出来的内容只在 y=221..460，上下 221px 全是背景色。
                //   转 90° 后"上一页/下一页"正好落进这两条死区 → 看着有渐隐图标却点不动。
                //   `requiredSize` 不受上层约束影响，内层才真的是 682×422。
                .requiredSize(width = h, height = w)
                .rotate(-90f)                  // ★ 逆时针 90°
                .align(Alignment.Center)
        ) {
            // 不能 clip：交换后的盒子比父容器宽，旋转后正好铺满屏幕，裁剪会把内容切掉
            content()
        }
    }
}
