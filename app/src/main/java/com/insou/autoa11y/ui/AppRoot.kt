package com.insou.autoa11y.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

    // Touch glow, shared between the tab row and the glass shader.
    var glowCenter by remember { mutableStateOf(Offset(-1f, -1f)) }
    var glowPower by remember { mutableFloatStateOf(0f) }

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
                    glowCenter = { glowCenter },
                    glowStrength = { glowPower * 0.45f },
                ),
        ) {
            GlassTabRow(
                tabs = tabs,
                selected = selected,
                onSelect = { selected = it },
                onGlow = { x, y -> glowCenter = Offset(x, y) },
                onGlowPower = { glowPower = it },
            )
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

/** A frame-stepped physical spring with exposed velocity (_Spring). */
private class FcSpring(initial: Float) {
    var value by mutableFloatStateOf(initial)
    var velocity by mutableFloatStateOf(0f)
    var target = initial
    private var stiffness = 380f
    private var dampingRatio = 0.85f

    fun springTo(t: Float, k: Float, z: Float) {
        target = t
        stiffness = k
        dampingRatio = z
    }

    fun step(dt: Float) {
        val c = 2f * dampingRatio * sqrt(stiffness)
        val a = stiffness * (target - value) - c * velocity
        velocity += a * dt
        value += velocity * dt
        if (abs(target - value) < 0.0005f && abs(velocity) < 0.005f) {
            value = target
            velocity = 0f
        }
    }
}

// Follow tracks the finger (stiff, critically damped); travel settles the lens
// on release (softer, slight bounce) — two-spring scheme.
private const val FOLLOW_K = 6000f
private const val FOLLOW_Z = 1.0f
private const val TRAVEL_K = 380f
private const val TRAVEL_Z = 0.78f

/**
 *  floating nav bar port: the lens (capsule) position is a
 * spring-driven continuous index. It follows the finger while dragging, goes
 * jelly-wide with speed, springs to the nearest or flung destination on
 * release, ticks haptics when crossing a destination, grows under a press,
 * and feeds a touch glow to the glass shader. Rubber-bands at the ends; a
 * fast release flings at most one destination past the finger.
 */
@Composable
private fun GlassTabRow(
    tabs: List<Tab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onGlow: (Float, Float) -> Unit,
    onGlowPower: (Float) -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tabWidth = maxWidth / tabs.size
        val tabWidthPx = with(density) { tabWidth.toPx() }
        val lensBasePx = with(density) { 46.dp.toPx() }
        val liftGrowthPx = with(density) { 6.dp.toPx() }

        val lensColor = MiuixTheme.colorScheme.primary
        val lens = remember { FcSpring(selected.toFloat()) }
        val lift = remember { FcSpring(0f) }
        val glow = remember { FcSpring(0f) }
        var jelly by remember { mutableFloatStateOf(0f) }
        var liveIndex by remember { mutableIntStateOf(selected) }

        // Push glow center & power to the shader each frame.
        LaunchedEffect(Unit) {
            var lastGlow = -1f
            while (true) {
                withFrameNanos { }
                if (glow.value != lastGlow) {
                    lastGlow = glow.value
                    onGlowPower(glow.value)
                }
            }
        }

        // Physics frame loop (three substeps for the stiff follow spring).
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                if (last != 0L) {
                    val dt = min((now - last) / 1e9f, 0.05f)
                    repeat(3) {
                        lens.step(dt / 3f)
                        lift.step(dt / 3f)
                        glow.step(dt / 3f)
                    }
                    // Jelly: stretch with lens speed (index/s), ease back.
                    val jTarget = min(abs(lens.velocity) / 5f, 1f) * 0.30f
                    jelly += (jTarget - jelly) * min(1f, dt * 14f)
                    liveIndex = lens.value.roundToInt().coerceIn(0, tabs.size - 1)
                }
                last = now
            }
        }

        fun positionIndex(x: Float): Float = x / tabWidthPx - 0.5f

        fun rubber(pos: Float): Float {
            val last = tabs.size - 1f
            return when {
                pos < 0f -> pos * 0.35f
                pos > last -> last + (pos - last) * 0.35f
                else -> pos
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .drawBehind {
                    // The traveling lens capsule, jelly-deformed and press-lifted.
                    val w = tabWidthPx * (1f + jelly) + lift.value * liftGrowthPx
                    val h = lensBasePx * (1f - jelly * 0.55f) + lift.value * liftGrowthPx
                    drawRoundRect(
                        color = lensColor.copy(
                            alpha = 0.14f + 0.10f * lift.value,
                        ),
                        topLeft = Offset(lens.value * tabWidthPx, (size.height - h) / 2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(h / 2f, h / 2f),
                    )
                }
                .pointerInput(tabs.size, tabWidthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var isDrag = false
                        var crossIndex = positionIndex(down.position.x)
                            .roundToInt().coerceIn(0, tabs.size - 1)

                        lift.springTo(1f, 340f, 0.85f)
                        glow.springTo(1f, 260f, 0.9f)
                        onGlow(down.position.x, size.height / 2f)
                        lens.springTo(crossIndex.toFloat(), TRAVEL_K, TRAVEL_Z)

                        val slop = viewConfiguration.touchSlop
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!change.pressed) break
                            if (change.positionChange() == Offset.Zero) continue
                            val pos = change.position
                            if (!isDrag && abs(pos.x - down.position.x) > slop) isDrag = true
                            if (isDrag) {
                                tracker.addPosition(change.uptimeMillis, pos)
                                onGlow(pos.x, size.height / 2f)
                                val p = rubber(positionIndex(pos.x))
                                lens.springTo(p, FOLLOW_K, FOLLOW_Z)
                                val idx = p.roundToInt().coerceIn(0, tabs.size - 1)
                                if (idx != crossIndex) {
                                    crossIndex = idx
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                        }

                        // Fling projection: at most one destination past the finger.
                        val vx = if (isDrag) tracker.calculateVelocity().x else 0f
                        val lead = vx / tabWidthPx * 0.12f
                        val target = (lens.target + lead).roundToInt()
                            .coerceIn(crossIndex - 1, crossIndex + 1)
                            .coerceIn(0, tabs.size - 1)
                        if (target != crossIndex) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        if (target != selected) onSelect(target)

                        lens.springTo(target.toFloat(), TRAVEL_K, TRAVEL_Z)
                        lift.springTo(0f, 300f, 0.9f)
                        glow.springTo(0f, 90f, 1f)
                    }
                },
        ) {
            tabs.forEachIndexed { i, tab ->
                val isOn = i == liveIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            onSelect(i)
                            lens.springTo(i.toFloat(), TRAVEL_K, TRAVEL_Z)
                        },
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
