package com.dsh.sportscam.device

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 手电筒控制器。
 *
 * 用 CameraManager.setTorchMode(cameraId, on) 直接控制闪光灯：
 * - 该 API 不需要 CAMERA 权限，也不需要预览；
 * - 注册 TorchCallback 以便和系统状态保持同步（例如用户从通知栏关灯后 UI 要跟着变）。
 */
class FlashlightController(context: Context) {

    companion object {
        private const val TAG = "FlashlightController"
    }

    private val cameraManager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val _isOn = MutableStateFlow(false)
    val isOn: StateFlow<Boolean> = _isOn.asStateFlow()

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    /** 具备 FLASH_INFO_AVAILABLE 的摄像头 id（改造机可能没有前置，但仍会有后置闪光灯） */
    private var torchCameraId: String? = null

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) _isOn.value = enabled
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == torchCameraId) _isOn.value = false
        }
    }

    init {
        detectTorchCamera()
    }

    private fun detectTorchCamera() {
        val manager = cameraManager ?: run {
            _available.value = false
            return
        }
        torchCameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "枚举摄像头失败", t)
            null
        }
        _available.value = torchCameraId != null

        if (torchCameraId != null) {
            try {
                manager.registerTorchCallback(torchCallback, null)
            } catch (t: Throwable) {
                Log.w(TAG, "注册手电筒回调失败", t)
            }
        }
    }

    /** 切换开关；返回是否切换成功 */
    fun toggle(): Boolean {
        val id = torchCameraId ?: return false
        val manager = cameraManager ?: return false
        val target = !_isOn.value
        return try {
            manager.setTorchMode(id, target)
            _isOn.value = target
            true
        } catch (e: CameraAccessException) {
            // 相机被占用 / 权限异常
            Log.w(TAG, "setTorchMode 失败", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "摄像头 id 失效", e)
            false
        } catch (t: Throwable) {
            Log.w(TAG, "setTorchMode 未知异常", t)
            false
        }
    }

    /** 关闭手电筒（退出启动器时调用） */
    fun turnOff() {
        val id = torchCameraId ?: return
        if (!_isOn.value) return
        try {
            cameraManager?.setTorchMode(id, false)
        } catch (t: Throwable) {
            Log.w(TAG, "关闭手电筒失败", t)
        } finally {
            _isOn.value = false
        }
    }

    fun release() {
        try {
            cameraManager?.unregisterTorchCallback(torchCallback)
        } catch (_: Throwable) {
        }
    }
}
