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

private fun norm(c: String): String =
    ComponentName.unflattenFromString(c)?.flattenToString() ?: c

@Composable
fun NotifPermsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var listeners by remember { mutableStateOf<List<ServiceEntry>>(emptyList()) }
    var enabledListeners by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        listeners = DeviceStore.installedNotifListeners(context)
        enabledListeners = DeviceStore.enabledNotifListeners(context).map(::norm).toSet()
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
        error?.let {
            item {
                Card(modifier = Modifier.padding(vertical = 4.dp)) {
                    BasicComponent(content = { Text(text = "错误: $it") })
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
