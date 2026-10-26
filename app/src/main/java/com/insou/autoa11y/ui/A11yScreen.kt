package com.insou.autoa11y.ui

import android.content.ComponentName
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.insou.autoa11y.data.DeviceStore
import com.insou.autoa11y.data.ServiceEntry
import com.insou.autoa11y.shell.A11yWatchdog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private fun norm(c: String): String =
    ComponentName.unflattenFromString(c)?.flattenToString() ?: c

@Composable
fun A11yScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<ServiceEntry>>(emptyList()) }
    var enabled by remember { mutableStateOf<Set<String>>(emptySet()) }
    var groupedEnabled by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyComponent by remember { mutableStateOf<String?>(null) }

    fun reload() {
        if (busyComponent != null) return
        entries = DeviceStore.installedA11y(context)
        enabled = DeviceStore.enabledA11y(context).map(::norm).toSet()
        groupedEnabled = enabled
    }

    LaunchedEffect(Unit) { reload() }

    fun toggle(entry: ServiceEntry, turnOn: Boolean) {
        if (busyComponent != null) return
        val previous = enabled
        val component = norm(entry.component)
        busyComponent = entry.component
        enabled = if (turnOn) enabled + component else enabled - component
        val started = SystemClock.uptimeMillis()
        scope.launch {
            try {
                val result = A11yWatchdog.setEnabledByUser(entry.component, turnOn)
                enabled = result.split(':').filter { it.isNotBlank() }.map(::norm).toSet()
                error = null
                // Keep this switch composed while its spring settles, then regroup.
                delay((350L - (SystemClock.uptimeMillis() - started)).coerceAtLeast(0L))
                groupedEnabled = enabled
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                enabled = previous
                error = e.message ?: e.toString()
            } finally {
                busyComponent = null
            }
        }
    }

    val watchdogOn by A11yWatchdog.watch.collectAsState()
    val watchdogLog by A11yWatchdog.log.collectAsState()
    val watchdogEnabled by A11yWatchdog.masterState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(vertical = 4.dp)) {
            BasicComponent(
                content = {
                    Text(text = "自愈保护")
                    Text(
                        text = (watchdogLog.lastOrNull() ?: "开启的服务被系统关闭后自动恢复")
                            .let { "保护 ${watchdogOn.size} 个 · $it" },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
                endActions = {
                    Switch(
                        checked = watchdogEnabled,
                        onCheckedChange = { A11yWatchdog.master = it },
                    )
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${entries.size} 个服务",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "刷新",
                modifier = Modifier.clickable { reload() },
            )
        }

        error?.let {
            Card(modifier = Modifier.padding(vertical = 4.dp)) {
                BasicComponent(content = { Text(text = "错误: $it") })
            }
        }

        val (on, off) = entries.partition { norm(it.component) in groupedEnabled }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (on.isNotEmpty()) {
                item { SmallTitle(text = "已开启（${on.size}）") }
                item {
                    Card(modifier = Modifier.padding(vertical = 4.dp)) {
                        on.forEach { entry ->
                            key(entry.component) {
                                ServiceRow(
                                    entry, norm(entry.component) in enabled, busyComponent != null,
                                ) { t -> toggle(entry, t) }
                            }
                        }
                    }
                }
            }
            item { SmallTitle(text = "未开启（${off.size}）") }
            item {
                Card(modifier = Modifier.padding(vertical = 4.dp)) {
                    if (off.isEmpty()) {
                        BasicComponent(content = { Text(text = "无") })
                    } else {
                        off.forEach { entry ->
                            key(entry.component) {
                                ServiceRow(
                                    entry, norm(entry.component) in enabled, busyComponent != null,
                                ) { t -> toggle(entry, t) }
                            }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(GlassNavBottomPadding)) }
        }
    }
}

@Composable
private fun ServiceRow(
    entry: ServiceEntry,
    checked: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    BasicComponent(
        enabled = !busy,
        onClick = { onToggle(!checked) },
        startAction = { AppIcon(packageName = entry.packageName) },
        content = {
            Text(text = entry.label)
            Text(
                text = entry.summary,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        },
        endActions = {
            // Display-only: the whole row is the single toggle target, so a tap
            // on the switch area cannot double-fire onClick + onCheckedChange.
            StatusSwitch(checked = checked, busy = busy)
        },
    )
}
