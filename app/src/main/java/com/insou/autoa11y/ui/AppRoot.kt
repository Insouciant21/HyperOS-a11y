package com.insou.autoa11y.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
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
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class Tab(val label: String, val icon: ImageVector)

/** Bottom clearance so scrollable content can rest above the glass nav bar
 *  (content still extends under it, feeding the blur). */
val GlassNavBottomPadding = 132.dp

@Composable
fun AppRoot() {
    val tabs = listOf(
        Tab("无障碍", Icons.Filled.Home),
        Tab("安装", Icons.Filled.Add),
        Tab("权限", Icons.Filled.Settings),
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }

    // Liquid glass: the content layer is captured, the bottom bar blurs it
    // progressively (frosted at the bottom edge, clear at the top edge).
    val backdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { TopAppBar(title = "A11yKit") },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .padding(top = padding.calculateTopPadding())
                    .padding(horizontal = 16.dp),
            ) {
                ShizukuBanner()
                when (selected) {
                    0 -> A11yScreen()
                    1 -> InstallScreen()
                    else -> PermsScreen()
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = { progressiveBlur(radiusX = 36f, gradient = ProgressiveBlur.Bottom) },
                ),
        ) {
            NavigationBar(
                color = MiuixTheme.colorScheme.surface.copy(alpha = 0.55f),
                showDivider = false,
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
