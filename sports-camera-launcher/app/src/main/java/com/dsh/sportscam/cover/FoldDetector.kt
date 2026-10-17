package com.dsh.sportscam.cover

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * 折叠状态检测。
 *
 * 反编译结论（《超级小副屏》utils/FoldDetector）：
 *  - 优先用 **铰链角度传感器** `Sensor.TYPE_HINGE_ANGLE`(36)；
 *    阈值：< 20° = 折叠，< 150° = 半折叠，否则 = 展开；
 *  - 没有铰链传感器时退回屏幕尺寸（dp）：宽<280 或 高<450 = 折叠；高<650 = 半折叠。
 *
 * 用途：折叠动作发生时（展开→折叠/半折叠）把启动器拉回副屏，
 * 实现"合上手机副屏直接就是桌面"。
 */
class FoldDetector(
    context: Context,
    private val onStateChanged: (State) -> Unit
) : SensorEventListener {

    enum class State { FOLDED, HALF_FOLDED, UNFOLDED, UNKNOWN }

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val hingeSensor: Sensor? =
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) }.getOrNull()

    @Volatile
    private var current: State = State.UNKNOWN

    val hasHingeSensor: Boolean get() = hingeSensor != null

    fun start() {
        val sm = sensorManager ?: return
        if (hingeSensor != null) {
            runCatching { sm.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_UI) }
            Log.d(TAG, "使用铰链角度传感器")
        } else {
            // 没有铰链传感器：先用尺寸判一次
            evaluateByMetrics()
            Log.d(TAG, "无铰链传感器，改用屏幕尺寸判断")
        }
    }

    fun stop() {
        runCatching { sensorManager?.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor?.type != Sensor.TYPE_HINGE_ANGLE) return
        val angle = e.values.firstOrNull() ?: return
        publish(
            when {
                angle < 20f -> State.FOLDED
                angle < 150f -> State.HALF_FOLDED
                else -> State.UNFOLDED
            }
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** 尺寸兜底判断 */
    private fun evaluateByMetrics() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        runCatching { wm.defaultDisplay.getRealMetrics(metrics) }.getOrElse { return }
        val density = if (metrics.densityDpi > 0) metrics.densityDpi / 160f else 1f
        val widthDp = metrics.widthPixels / density
        val heightDp = metrics.heightPixels / density
        publish(
            when {
                widthDp < 280f || heightDp < 450f -> State.FOLDED
                heightDp < 650f -> State.HALF_FOLDED
                else -> State.UNFOLDED
            }
        )
    }

    private fun publish(state: State) {
        if (state == current) return
        current = state
        Log.d(TAG, "折叠状态 -> $state")
        onStateChanged(state)
    }

    companion object {
        private const val TAG = "FoldDetector"
    }
}
