package com.insou.autoa11y.ui

import android.content.ComponentName
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.insou.autoa11y.data.AppEntry
import com.insou.autoa11y.data.DeviceStore
import com.insou.autoa11y.data.ServiceEntry
import com.insou.autoa11y.shell.ShellGateway
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val OP_INSTALL = "REQUEST_INSTALL_PACKAGES"

private fun norm(c: String): String =
    ComponentName.unflattenFromString(c)?.flattenToString() ?: c

@Composable
fun PermsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 本应用未知安装权
    var ownInstallOn by remember { mutableStateOf<Boolean?>(null) }

    // 全设备未知安装权
    var installApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var allowSet by remember { mutableStateOf<Set<String>?>(null) }

    // 通知使用权
    var listeners by remember { mutableStateOf<List<ServiceEntry>>(emptyList()) }
    var enabledListeners by remember { mutableStateOf<Set<String>>(emptySet()) }

    var error by remember { mutableStateOf<String?>(null) }
    var busyKey by remember { mutableStateOf<String?>(null) }

    suspend fun reloadAllowSet() {
        allowSet = try {
            val out = ShellGateway.call { it.queryAppOps(OP_INSTALL, "allow") }
            out.lineSequence().map { it.trim() }.filter { it.startsWith("com.") || it.contains('.') }
                .toSet()
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
            allowSet
        }
    }

    LaunchedEffect(Unit) {
        installApps = DeviceStore.installRequesters(context)
        listeners = DeviceStore.installedNotifListeners(context)
        enabledListeners = DeviceStore.enabledNotifListeners(context).map(::norm).toSet()
        reloadAllowSet()
        ownInstallOn = try {
            ShellGateway.call { it.getAppOps(context.packageName, OP_INSTALL) }.contains("allow")
        } catch (e: Throwable) {
            null
        }
    }

    fun toggleInstallPerm(pkg: String, turnOn: Boolean, isOwn: Boolean = false) {
        busyKey = pkg
        scope.launch {
            try {
                val out = ShellGateway.call {
                    it.setAppOps(pkg, OP_INSTALL, if (turnOn) "allow" else "default")
                }
                if (isOwn) {
                    ownInstallOn = out.contains("allow")
                } else {
                    reloadAllowSet()
                }
                error = null
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                busyKey = null
            }
        }
    }

    fun toggleListener(entry: ServiceEntry, turnOn: Boolean) {
        busyKey = entry.component
        scope.launch {
            try {
                val result = ShellGateway.call { it.setNotificationListener(entry.component, turnOn) }
                enabledListeners = result.split(':').filter { it.isNotBlank() }.map(::norm).toSet()
                error = null
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                busyKey = null
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { SmallTitle(text = "本应用") }
        item {
            Card(modifier = Modifier.padding(vertical = 4.dp)) {
                BasicComponent(
                    content = {
                        Text(text = "未知应用安装权（REQUEST_INSTALL_PACKAGES）")
                        Text(
                            text = ownInstallOn?.let { if (it) "已允许" else "默认" } ?: "读取中…",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                    endActions = {
                        Switch(
                            checked = ownInstallOn == true,
                            onCheckedChange = null,
                            enabled = busyKey == null,
                        )
                    },
                )
            }
        }

        error?.let {
            item {
                Card(modifier = Modifier.padding(vertical = 4.dp)) {
                    BasicComponent(content = { Text(text = "错误: $it") })
                }
            }
        }

        item { SmallTitle(text = "未知应用安装权 · 全设备（${installApps.size}）") }
        item {
            Card(modifier = Modifier.padding(vertical = 4.dp)) {
                val allow = allowSet
                when {
                    installApps.isEmpty() -> BasicComponent(content = { Text(text = "无声明此权限的应用") })
                    allow == null -> BasicComponent(content = { Text(text = "读取状态中…（需要 Shizuku）") })
                    else -> installApps.forEach { app ->
                        BasicComponent(
                            enabled = busyKey == null,
                            onClick = { toggleInstallPerm(app.packageName, app.packageName !in allow) },
                            startAction = { AppIcon(packageName = app.packageName) },
                            content = {
                                Text(text = app.label)
                                Text(
                                    text = app.packageName,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            },
                            endActions = {
                                Switch(checked = app.packageName in allow, onCheckedChange = null, enabled = busyKey == null)
                            },
                        )
                    }
                }
            }
        }

        item { SmallTitle(text = "通知使用权（${listeners.size}）") }
        item {
            Card(modifier = Modifier.padding(vertical = 4.dp)) {
                if (listeners.isEmpty()) {
                    BasicComponent(content = { Text(text = "无已安装的监听服务") })
                } else {
                    listeners.forEach { entry ->
                        val on = norm(entry.component) in enabledListeners
                        BasicComponent(
                            enabled = busyKey == null,
                            onClick = { toggleListener(entry, !on) },
                            startAction = { AppIcon(packageName = entry.packageName) },
                            content = {
                                Text(text = entry.label)
                                Text(
                                    text = entry.summary,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            },
                            endActions = {
                                Switch(checked = on, onCheckedChange = null, enabled = busyKey == null)
                            },
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(GlassNavBottomPadding)) }
    }
}
