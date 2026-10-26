package com.insou.autoa11y.ui

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
import com.insou.autoa11y.shell.ShellGateway
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val OP_INSTALL = "REQUEST_INSTALL_PACKAGES"

/** Process-wide cache so re-entering the tab shows the list instantly
 *  (tab content is disposed on switch; the loader would otherwise re-run
 *  hundreds of binder calls on every visit). */
private var cachedApps: List<AppEntry>? = null
private var cachedAllow: Set<String>? = null

@Composable
fun InstallPermsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installApps by remember { mutableStateOf(cachedApps ?: emptyList()) }
    var allowSet by remember { mutableStateOf(cachedAllow) }
    var loading = installApps.isEmpty()
    var error by remember { mutableStateOf<String?>(null) }
    var busyKey by remember { mutableStateOf<String?>(null) }

    suspend fun reloadAllowSet() {
        allowSet = try {
            val out = ShellGateway.call { it.queryAppOps(OP_INSTALL, "allow") }
            out.lineSequence().map { it.trim() }.filter { it.contains('.') }.toSet()
                .also { cachedAllow = it }
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
            allowSet
        }
    }

    LaunchedEffect(Unit) {
        if (cachedApps != null) {
            // Show cache immediately, refresh state (one fast shell call).
            reloadAllowSet()
            return@LaunchedEffect
        }
        // Cold load: shell dumpsys parse is primary (fast, complete, immune
        // to MIUI package-visibility filtering); PM query is the fallback
        // when Shizuku is unavailable.
        val shellPkgs = try {
            ShellGateway.call { it.queryInstallRequesters() }
                .lineSequence().map { it.trim() }.filter { it.contains('.') }.toList()
        } catch (e: Throwable) {
            emptyList()
        }
        if (shellPkgs.isNotEmpty()) {
            val labels = DeviceStore.labelsFor(context, shellPkgs)
            installApps = shellPkgs
                .map { AppEntry(labels[it] ?: it, it) }
                .sortedBy { it.label.lowercase() }
                .also { cachedApps = it }
        } else {
            installApps = DeviceStore.installRequesters(context)
                .also { cachedApps = it }
        }
        reloadAllowSet()
    }

    fun toggleInstallPerm(pkg: String, turnOn: Boolean) {
        busyKey = pkg
        scope.launch {
            try {
                ShellGateway.call {
                    it.setAppOps(pkg, OP_INSTALL, if (turnOn) "allow" else "default")
                }
                reloadAllowSet()
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

        item { SmallTitle(text = "全设备（${installApps.size}）") }
        item {
            Card(modifier = Modifier.padding(vertical = 4.dp)) {
                val allow = allowSet
                when {
                    loading && installApps.isEmpty() ->
                        BasicComponent(content = { Text(text = "读取中…") })
                    installApps.isEmpty() ->
                        BasicComponent(content = { Text(text = "无声明此权限的应用") })
                    allow == null ->
                        BasicComponent(content = { Text(text = "读取状态中…（需要 Shizuku）") })
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

        item { Spacer(modifier = Modifier.height(GlassNavBottomPadding)) }
    }
}
