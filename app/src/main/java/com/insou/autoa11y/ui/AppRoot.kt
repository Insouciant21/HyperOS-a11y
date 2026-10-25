package com.insou.autoa11y.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.insou.autoa11y.shell.ShellGateway
import com.insou.autoa11y.shell.ShellState
import com.insou.autoa11y.ui.glass.glassSource
import com.insou.autoa11y.ui.glass.liquidGlass
import com.insou.autoa11y.ui.glass.rememberGlassBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar

private class Tab(val label: String, val icon: ImageVector)

/** Bottom clearance so scrollable content can rest above the glass pill
 *  (content still extends under it, feeding the lens). */
val GlassNavBottomPadding = 132.dp

@Composable
fun AppRoot() {
    val tabs = listOf(
        Tab("无障碍", Icons.Filled.Home),
        Tab("安装", Icons.Filled.Add),
        Tab("安装权限", Icons.Filled.Lock),
        Tab("通知权限", Icons.Filled.Notifications),
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }

    // -style liquid glass lens on a floating pill bar.
    val dark = isSystemInDarkTheme()
    val glassTint = if (dark) Color(0xFF20222A).copy(alpha = 0.62f)
    else Color(0xFFFFFFFF).copy(alpha = 0.45f)

    val backdrop = rememberGlassBackdrop()
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    var panePos by remember { mutableStateOf<Offset?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .glassSource(backdrop)
                .onGloballyPositioned { rootPos = it.positionInRoot() },
            topBar = { TopAppBar(title = "A11yKit") },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .padding(horizontal = 16.dp),
            ) {
                ShizukuBanner()
                when (selected) {
                    0 -> A11yScreen()
                    1 -> InstallScreen()
                    2 -> InstallPermsScreen()
                    else -> NotifPermsScreen()
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .onGloballyPositioned { panePos = it.positionInRoot() }
                .liquidGlass(
                    backdrop = backdrop,
                    paneOffsetInRoot = { panePos ?: Offset.Zero },
                    rootOffsetInRoot = { rootPos },
                    tint = glassTint,
                ),
        ) {
            NavigationBar(
                color = Color.Transparent,
                showDivider = false,
                defaultWindowInsetsPadding = false,
            ) {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = tab.icon,
                        label = tab.label,
                    )
                }
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
