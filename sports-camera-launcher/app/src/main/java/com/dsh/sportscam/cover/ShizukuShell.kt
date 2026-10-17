package com.dsh.sportscam.cover

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Shizuku 通道状态 */
data class ShizukuState(
    val installed: Boolean = false,
    val binderAlive: Boolean = false,
    val granted: Boolean = false,
    val version: Int = -1
) {
    /** 可以用来执行特权命令 */
    val usable: Boolean get() = binderAlive && granted
}

/**
 * Shizuku 特权通道 —— 以 shell(uid 2000) 身份执行命令。
 *
 * 这是「把别的 App 搬到副屏」「改副屏分辨率/DPI」「改屏幕方向」等能力的**唯一**来源：
 * 普通 App 没有这些权限，只有 shell 有。
 *
 * 实现要点（来自反编译结论）：
 *  - `Shizuku.newProcess` 不在公开 API 表面里，必须**反射**调用；
 *  - **必须先排空 stdout 再 waitFor()**，否则大输出会把管道写满导致死锁；
 *  - 统一追加 ` 2>&1`，便于用回显内容判断成败；
 *  - 必须监听 binder 的 received / dead，不能只在启动时检查一次。
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_PERMISSION_CODE = 1001

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var initialized = false
    private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null

    /** 在 Application/MainActivity 启动时调用一次（幂等） */
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val app = context.applicationContext
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { refresh(app) }
            Shizuku.addBinderDeadListener { refresh(app) }
        }.onFailure { Log.w(TAG, "注册 Shizuku binder 监听失败", it) }

        // 授权对话框的结果回调：用户点"允许"后立刻刷新状态（否则 UI 一直显示未授权）
        runCatching {
            val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                Log.d(TAG, "requestPermission 结果 code=$requestCode grantResult=$grantResult")
                if (requestCode == REQUEST_PERMISSION_CODE) refresh(app)
            }
            permissionListener = listener
            Shizuku.addRequestPermissionResultListener(listener)
        }.onFailure { Log.w(TAG, "注册授权结果监听失败", it) }

        refresh(app)
    }

    fun refresh(context: Context) {
        val installed = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = if (alive) runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false) else false

        val version = if (alive) runCatching { Shizuku.getVersion() }.getOrDefault(-1) else -1

        _state.value = ShizukuState(installed, alive, granted, version)
        Log.d(TAG, "refresh -> installed=$installed alive=$alive granted=$granted v=$version")
    }

    /** 弹出 Shizuku 授权对话框 */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_PERMISSION_CODE) }
            .onFailure { Log.w(TAG, "requestPermission 失败", it) }
    }

    fun release() {
        permissionListener?.let { runCatching { Shizuku.removeRequestPermissionResultListener(it) } }
        permissionListener = null
    }

    val isUsable: Boolean get() = _state.value.usable

    /**
     * 执行命令并返回合并了 stderr 的回显；失败返回 null。
     * 调用方用回显里有没有 "Error"/"Exception"/"Permission" 判断成败。
     */
    suspend fun exec(command: String): String? = withContext(Dispatchers.IO) {
        if (!isUsable) return@withContext null
        val process = try {
            newProcess("$command 2>&1")
        } catch (t: Throwable) {
            Log.w(TAG, "newProcess 失败: $command", t)
            null
        } ?: return@withContext null

        try {
            // 顺序很重要：先读完 stdout，再 waitFor（避免管道写满死锁）
            val out = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.waitFor()
            out
        } catch (t: Throwable) {
            Log.w(TAG, "读取命令输出失败: $command", t)
            null
        } finally {
            runCatching { process.destroy() }
        }
    }

    /** 只要成败；判断标准：有回显且不含 Error/Exception/Permission */
    suspend fun execOk(command: String): Boolean {
        val out = exec(command) ?: return false
        return !(out.contains("Error") || out.contains("Exception") || out.contains("Permission"))
    }

    /**
     * 反射调用 `Shizuku.newProcess(String[] cmd, String[] env, String dir)`。
     * 该方法不在公开 API 里，只能反射；调用形态： newProcess(["sh","-c",cmd], null, null)
     */
    private fun newProcess(command: String): Process? {
        val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
    }
}
