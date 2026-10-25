package com.insou.autoa11y.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.insou.autoa11y.shell.ShellGateway
import com.insou.autoa11y.shell.ShellState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar

private class Tab(val label: String, val icon: ImageVector)

@Composable
fun AppRoot() {
    val tabs = listOf(
        Tab("无障碍", Icons.Filled.Home),
        Tab("安装", Icons.Filled.Add),
        Tab("权限", Icons.Filled.Settings),
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = { TopAppBar(title = "A11yKit") },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = tab.icon,
                        label = tab.label,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            ShizukuBanner()
            when (selected) {
                0 -> A11yScreen()
                1 -> InstallScreen()
                else -> PermsScreen()
            }
        }
    }
}

@Composable
private fun ShizukuBanner() {
    val context = LocalContext.current
    val state by ShellGateway.state.collectAsState()

    when (state) {
        is ShellState.Dead -> Banner(
            message = "Shizuku 未运行，特权操作不可用",
            action = "打开",
        ) {
            context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?.let { context.startActivity(it) }
        }

        is ShellState.Unauthorized -> Banner(
            message = "未获得 Shizuku 授权",
            action = "授权",
        ) {
            ShellGateway.requestPermission()
        }

        is ShellState.Ready -> Unit
    }
}

@Composable
private fun Banner(message: String, action: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        BasicComponent(
            onClick = onClick,
            content = { Text(text = message) },
            endActions = { Text(text = action) },
        )
    }
}
