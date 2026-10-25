package com.insou.autoa11y.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface ShellState {
    data object Dead : ShellState          // Shizuku server not running
    data object Unauthorized : ShellState  // running, permission not granted
    data class Ready(val binder: IBinder) : ShellState
}

object ShellGateway {

    private lateinit var appContext: Context
    private var service: IShellService? = null
    private var binding = false

    private val _state = MutableStateFlow<ShellState>(ShellState.Dead)
    val state: StateFlow<ShellState> = _state

    private val userServiceArgs by lazy {
        UserServiceArgs(
            ComponentName(appContext.packageName, ShellService::class.java.name)
        )
            .tag("a11y_shell")
            .version(3)
            .processNameSuffix("shell")
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshState()
        Shizuku.addBinderReceivedListener { refreshState() }
        Shizuku.addBinderDeadListener {
            service = null
            _state.value = ShellState.Dead
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) refreshState() else _state.value = ShellState.Unauthorized
        }
    }

    fun refreshState() {
        val alive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!alive) {
            service = null
            _state.value = ShellState.Dead
            return
        }
        val granted = try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        val binder = try { Shizuku.getBinder() } catch (_: Throwable) { null }
        _state.value = when {
            binder == null -> ShellState.Dead
            !granted -> ShellState.Unauthorized
            else -> ShellState.Ready(binder)
        }
    }

    fun requestPermission() {
        try {
            if (!Shizuku.isPreV11()) Shizuku.requestPermission(1001)
        } catch (_: Throwable) {
        }
    }

    private suspend fun awaitService(): IShellService {
        service?.let { return it }
        refreshState()
        when (val s = _state.value) {
            is ShellState.Dead -> error("Shizuku 未运行")
            is ShellState.Unauthorized -> error("未获得 Shizuku 授权")
            is ShellState.Ready -> Unit
        }
        if (!binding) {
            binding = true
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = IShellService.Stub.asInterface(binder)
                    binding = false
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }
            }
            try {
                Shizuku.bindUserService(userServiceArgs, conn)
            } catch (e: Throwable) {
                binding = false
                throw e
            }
        }
        // Wait (max 8s) for the binder to arrive
        val waited = withTimeoutOrNull(8000) {
            while (service == null) kotlinx.coroutines.delay(50)
            service
        }
        return waited ?: error("UserService 连接超时")
    }

    /** Run one privileged operation; retries once after a rebind. */
    suspend fun <T> call(block: (IShellService) -> T): T {
        return try {
            block(awaitService())
        } catch (e: IllegalStateException) {
            service = null
            refreshState()
            block(awaitService())
        }
    }
}
