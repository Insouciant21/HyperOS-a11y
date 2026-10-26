package com.insou.autoa11y.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.insou.autoa11y.shell.ShellGateway
import com.insou.autoa11y.shell.ShellState
import com.insou.autoa11y.ui.glass.glassSource
import com.insou.autoa11y.ui.glass.liquidGlass
import com.insou.autoa11y.ui.glass.rememberGlassBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private class Tab(val label: String, val icon: ImageVector)

/** Bottom clearance so scrollable content can rest above the glass pill
 *  (content still extends under it, feeding the lens). */
val GlassNavBottomPadding = 132.dp

@Composable
fun AppRoot() {
    val tabs = listOf(
        Tab("无障碍", Icons.Outlined.Home),
        Tab("安装", Icons.Outlined.Add),
        Tab("安装权限", Icons.Outlined.Lock),
        Tab("通知权限", Icons.Outlined.Notifications),
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }

    // -style liquid glass lens on a floating pill bar.
    val dark = isSystemInDarkTheme()
    //  tints: dark glass tint α0.48, light F4F4F4 α0.576.
    val glassTint = if (dark) Color(0xFF20222A).copy(alpha = 0.48f)
    else Color(0xFFF4F4F4).copy(alpha = 0.576f)
    val glassSaturation = if (dark) 1.925f else 2.1f

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
                    saturation = glassSaturation,
                ),
        ) {
            GlassTabRow(
                tabs = tabs,
                selected = selected,
                onSelect = { selected = it },
            )
        }
    }
}

/**
 * Draggable tab row,  pill style: a stretchy capsule indicator that
 * follows a horizontal drag across the pill and snaps to the nearest tab on
 * release; items remain individually tappable.
 */
@Composable
private fun GlassTabRow(
    tabs: List<Tab>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tabWidth = maxWidth / tabs.size
        val density = LocalDensity.current
        val tabWidthPx = with(density) { tabWidth.toPx() }

        var dragging by remember { mutableStateOf(false) }
        var dragX by remember { mutableFloatStateOf(selected * tabWidthPx) }
        val anchorX = selected * tabWidthPx
        val snapX by animateFloatAsState(
            targetValue = anchorX,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        )
        val indicatorLeft = (if (dragging) minOf(dragX, anchorX) else anchorX)
        val indicatorRight = (if (dragging) maxOf(dragX, anchorX) + tabWidthPx else anchorX + tabWidthPx)

        // Stretchy capsule indicator behind the items.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(indicatorLeft.roundToInt(), 0) }
                .width(with(density) { (indicatorRight - indicatorLeft).toDp() })
                .height(48.dp)
                .background(
                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(24.dp),
                ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .pointerInput(tabWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragX = selected * tabWidthPx
                        },
                        onDragEnd = {
                            dragging = false
                            onSelect((dragX / tabWidthPx).roundToInt().coerceIn(0, tabs.size - 1))
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { _, amount ->
                            dragX = (dragX + amount)
                                .coerceIn(0f, tabWidthPx * (tabs.size - 1))
                        },
                    )
                },
        ) {
            tabs.forEachIndexed { i, tab ->
                val isOn = i == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (isOn) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = tab.label,
                        fontSize = 11.sp,
                        color = if (isOn) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
