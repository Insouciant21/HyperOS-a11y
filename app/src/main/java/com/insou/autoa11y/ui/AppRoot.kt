package com.insou.autoa11y.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import com.insou.autoa11y.shell.ShellGateway
import com.insou.autoa11y.shell.ShellState
import com.insou.autoa11y.ui.glass.GooPill
import com.insou.autoa11y.ui.glass.glassSource
import com.insou.autoa11y.ui.glass.glassLensContent
import com.insou.autoa11y.ui.glass.outsideGlassLens
import com.insou.autoa11y.ui.glass.raisedGlassLens
import com.insou.autoa11y.ui.glass.liquidGlass
import com.insou.autoa11y.ui.glass.rememberGlassBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.tanh
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

    val dark = isSystemInDarkTheme()
    val captureMarginPx = with(LocalDensity.current) { 48.dp.toPx() }
    val backdrop = rememberGlassBackdrop()
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    // Bar rect in glassSource (Scaffold content) coordinates; only this strip
    // needs capturing for the glass shader.
    var barRect by remember { mutableStateOf(Rect.Zero) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .glassSource(backdrop) {
                    // Inflate for refraction/dispersion sampling past the rim.
                    barRect.inflate(captureMarginPx).takeIf { !it.isEmpty }
                }
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
                // Keep-alive pages: all four stay composed (zero recomposition
                // per switch); the switch animates alpha + a quarter-width
                // slide purely in the draw phase. AnimatedContent would
                // dispose/recompose the target page on every switch - the
                // 40-120ms hitches seen under gfxinfo.
                KeepAlivePages(
                    selected,
                    listOf(
                        { A11yScreen() },
                        { InstallScreen() },
                        { InstallPermsScreen() },
                        { NotifPermsScreen() },
                    ),
                )
            }
        }

        GlassNav(
            tabs = tabs,
            selected = selected,
            onSelect = { selected = it },
            dark = dark,
            captureMarginPx = captureMarginPx,
            backdrop = backdrop,
            rootPos = { rootPos },
            onBarRect = { barRect = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .widthIn(max = (tabs.size * 86 + 16).dp)
                .fillMaxWidth(),
        )
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

/** All pages stay composed; switching animates alpha/slide in draw phase. */
@Composable
private fun KeepAlivePages(
    selected: Int,
    pages: List<@Composable () -> Unit>,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        pages.forEachIndexed { i, page ->
            val isActive = i == selected
            val alpha by animateFloatAsState(
                targetValue = if (isActive) 1f else 0f,
                animationSpec = tween(220),
                label = "alpha$i",
            )
            val slide by animateFloatAsState(
                targetValue = if (isActive) 0f else ((i - selected).coerceIn(-1, 1)) * 0.25f,
                animationSpec = tween(300, easing = EaseOut),
                label = "slide$i",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (isActive) 1f else 0f)
                    .graphicsLayer {
                        this.alpha = alpha
                        translationX = slide * size.width
                    }
                    .drawWithContent { if (alpha > 0.004f) drawContent() }
                    // Inactive pages sit below the active one; consume stray
                    // hits that fall through the active page's empty areas.
                    .pointerInput(isActive) {
                        if (!isActive) {
                            awaitEachGesture {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
            ) {
                page()
            }
        }
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

/** A frame-stepped physical spring with exposed velocity (physical spring). */
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

    val acceleration: Float
        get() = stiffness * (target - value) - 2f * dampingRatio * sqrt(stiffness) * velocity

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

// The drag follows continuously; a release preserves velocity while it settles.
private const val FOLLOW_K = 1024f
private const val FOLLOW_Z = 0.9f
private const val TRAVEL_K = 380f
private const val TRAVEL_Z = 0.78f
private const val LIFT_K = 500f

/** A resting selection rises into a clear lens for the whole press and travel. */
@Composable
private fun GlassNav(
    tabs: List<Tab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    dark: Boolean,
    captureMarginPx: Float,
    backdrop: androidx.compose.ui.graphics.layer.GraphicsLayer,
    rootPos: () -> Offset,
    onBarRect: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val surface = rememberGraphicsLayer()

    BoxWithConstraints(modifier = modifier) {
        val rowHeight = 62.dp
        val inset = 8.dp
        val tabWidth = (maxWidth - inset * 2) / tabs.size
        val insetPx = with(density) { inset.toPx() }
        val tabWidthPx = with(density) { tabWidth.toPx() }
        val rowWidthPx = with(density) { maxWidth.toPx() }
        val rowHeightPx = with(density) { rowHeight.toPx() }
        val restWidthPx = tabWidthPx + with(density) { 8.dp.toPx() }
        val restHeightPx = with(density) { 54.dp.toPx() }
        val lensGrowthPx = with(density) { 16.dp.toPx() }
        val barGrowthPx = with(density) { 16.dp.toPx() }

        val glassTint = if (dark) Color(0xFF2C2D2F).copy(alpha = 0.96f)
        else Color(0xFFF0F1F4).copy(alpha = 0.94f)
        val selectionTint = if (dark) Color(0xFF081126) else Color(0xFFDDE8FF)
        val primary = if (dark) Color(0xFF437FF7) else Color(0xFF3478F6)
        val dimColor = if (dark) Color(0xFFC2C4D0) else Color(0xFF656975)

        val lens = remember { FcSpring(selected.toFloat()) }
        val lift = remember { FcSpring(0f) }
        val swell = remember { FcSpring(0f) }
        val pull = remember { FcSpring(0f) }
        val jelly = remember { FcSpring(0f) }
        val glow = remember { FcSpring(0f) }
        var fingerDown by remember { mutableStateOf(false) }
        var dropPending by remember { mutableStateOf(false) }
        var barRect by remember { mutableStateOf(Rect.Zero) }
        var rowPos by remember { mutableStateOf(Offset.Zero) }
        var glowAt by remember { mutableStateOf(Offset(-1f, -1f)) }

        val backdropOrigin = {
            if (barRect.isEmpty) Offset.Zero else Offset(
                (barRect.left - captureMarginPx).coerceAtLeast(0f),
                (barRect.top - captureMarginPx).coerceAtLeast(0f),
            )
        }
        val lensBounds = {
            val raised = lift.value.coerceIn(0f, 1f)
            val stretch = jelly.value.coerceIn(-0.15f, 0.2f)
            val width = (restWidthPx + lensGrowthPx * raised) * (1f + stretch)
            val height = (restHeightPx + lensGrowthPx * raised) * (1f - stretch * 1.4f)
            val cx = insetPx + (lens.value + 0.5f) * tabWidthPx
            Rect(cx - width / 2f, (rowHeightPx - height) / 2f,
                cx + width / 2f, (rowHeightPx + height) / 2f)
        }

        LaunchedEffect(selected) {
            if (!fingerDown) lens.springTo(selected.toFloat(), TRAVEL_K, TRAVEL_Z)
        }
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                if (last != 0L) {
                    val dt = min((now - last) / 1e9f, 0.05f)
                    val braking = max(0f, -lens.acceleration * sign(lens.velocity))
                    val deformation = 0.2f * tanh(abs(lens.velocity) / 3f) -
                        0.15f * tanh(braking / 25f)
                    jelly.springTo(deformation, 100f, 0.4f)
                    repeat(4) {
                        val step = dt / 4f
                        lens.step(step)
                        lift.step(step)
                        swell.step(step)
                        pull.step(step)
                        jelly.step(step)
                        glow.step(step)
                    }
                    // Even a quick tap completes its rise and arrives before it drops.
                    if (dropPending && !fingerDown && lift.value >= 0.975f &&
                        abs(lens.target - lens.value) < 0.04f) {
                        dropPending = false
                        lift.springTo(0f, LIFT_K, 1f)
                    }
                }
                last = now
            }
        }

        fun rubberBand(distance: Float, limit: Float): Float =
            sign(distance) * limit * (1f - 1f / (1f + abs(distance) * 0.55f / limit))

        fun positionIndex(x: Float): Float {
            val position = (x - insetPx) / tabWidthPx - 0.5f
            val last = tabs.lastIndex.toFloat()
            return when {
                position < 0f -> rubberBand(position, 0.35f)
                position > last -> last + rubberBand(position - last, 0.35f)
                else -> position
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .onGloballyPositioned {
                    rowPos = it.positionInRoot()
                    barRect = Rect(rowPos - rootPos(), it.size.toSize())
                    onBarRect(barRect)
                }
                .pointerInput(tabs.size, tabWidthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var isDrag = false
                        var released = false
                        var crossIndex = positionIndex(down.position.x)
                            .roundToInt().coerceIn(0, tabs.lastIndex)
                        fingerDown = true
                        dropPending = false
                        lift.springTo(1f, LIFT_K, 1f)
                        swell.springTo(1f, 311f, 0.593f)
                        glow.springTo(1f, 1400f, 1f)
                        glowAt = down.position
                        lens.springTo(crossIndex.toFloat(), TRAVEL_K, TRAVEL_Z)

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                tracker.addPosition(change.uptimeMillis, change.position)
                                if (!change.pressed) {
                                    change.consume()
                                    released = true
                                    break
                                }
                                if (change.positionChange() == Offset.Zero) continue
                                glowAt = change.position
                                if (abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) {
                                    isDrag = true
                                }
                                if (isDrag) {
                                    change.consume()
                                    val x = change.position.x
                                    val outside = x - x.coerceIn(0f, rowWidthPx)
                                    pull.springTo(rubberBand(outside, rowHeightPx * 7f / 32f), 1400f, 1f)
                                    val position = positionIndex(x)
                                    lens.springTo(position, FOLLOW_K, FOLLOW_Z)
                                    val index = position.roundToInt().coerceIn(0, tabs.lastIndex)
                                    if (index != crossIndex) {
                                        crossIndex = index
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                }
                            }
                        } finally {
                            val target = if (!released) currentSelected else if (!isDrag) {
                                crossIndex
                            } else {
                                val lead = tracker.calculateVelocity().x / tabWidthPx * 0.1f
                                (lens.target + lead).roundToInt()
                                    .coerceIn(crossIndex - 1, crossIndex + 1)
                                    .coerceIn(0, tabs.lastIndex)
                            }
                            if (released && target != crossIndex) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            fingerDown = false
                            dropPending = true
                            lens.springTo(target.toFloat(), TRAVEL_K, TRAVEL_Z)
                            swell.springTo(0f, 311f, 0.593f)
                            pull.springTo(0f, 311f, 0.593f)
                            glow.springTo(0f, 120f, 1f)
                            if (released && target != currentSelected) currentOnSelect(target)
                        }
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val growth = swell.value * barGrowthPx / rowWidthPx
                        scaleX = (1f + growth) * (1f + abs(pull.value) / rowWidthPx * 0.5f)
                        scaleY = 1f + growth
                        translationX = pull.value
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .glassSource(surface)
                        .liquidGlass(
                            backdrop = backdrop,
                            paneOffsetInRoot = { rowPos },
                            rootOffsetInRoot = rootPos,
                            cornerRadius = rowHeight / 2,
                            bevel = 2.dp,
                            depth = 2.dp,
                            hazeSigma = 8.dp,
                            rim = if (dark) 0.18f else 0.10f,
                            rimWidth = 1.5.dp,
                            tint = glassTint,
                            saturation = 1f,
                            glowCenter = { glowAt },
                            glowStrength = { 0.06f * glow.value * glow.value },
                            glowReach = { 28.dp * (1f + 3f * (1f - glow.value.coerceIn(0f, 1f))) },
                            gooLens = {
                                val bounds = lensBounds()
                                GooPill(
                                    cx = bounds.center.x,
                                    cy = bounds.center.y,
                                    halfSeg = max(0f, (bounds.width - bounds.height) / 2f),
                                    radius = bounds.height / 2f,
                                    tint = selectionTint.copy(alpha = 1f - lift.value.coerceIn(0f, 1f)),
                                )
                            },
                            backdropOrigin = backdropOrigin,
                        ),
                )
                Box(
                    modifier = Modifier.fillMaxSize().raisedGlassLens(
                        backdrop = backdrop,
                        surface = surface,
                        paneOffset = { rowPos },
                        rootOffset = rootPos,
                        backdropOrigin = backdropOrigin,
                        lens = lensBounds,
                        lift = { lift.value },
                        dark = dark,
                    ),
                )
                GlassNavItems(
                    tabs = tabs,
                    tint = dimColor,
                    inset = inset,
                    selectedIndex = selected,
                    onActivate = {
                        lens.springTo(it.toFloat(), TRAVEL_K, TRAVEL_Z)
                        if (!fingerDown) {
                            lift.springTo(1f, LIFT_K, 1f)
                            dropPending = true
                        }
                        currentOnSelect(it)
                    },
                    modifier = Modifier.fillMaxSize().outsideGlassLens(lensBounds),
                )
                GlassNavItems(
                    tabs = tabs,
                    tint = primary,
                    inset = inset,
                    scale = { 1f + 0.16f * lift.value.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxSize()
                        .clearAndSetSemantics { }
                        .glassLensContent(lensBounds, { lift.value }, dark),
                )
            }
        }
    }
}

@Composable
private fun GlassNavItems(
    tabs: List<Tab>,
    tint: Color,
    inset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    scale: () -> Float = { 1f },
    selectedIndex: Int = -1,
    onActivate: ((Int) -> Unit)? = null,
) {
    Row(modifier = modifier.padding(horizontal = inset)) {
        tabs.forEachIndexed { index, tab ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (onActivate == null) Modifier else Modifier.selectable(
                            selected = selectedIndex == index,
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onActivate(index) },
                        ),
                    )
                    .graphicsLayer {
                        scaleX = scale()
                        scaleY = scale()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    imageVector = tab.icon,
                    modifier = Modifier.size(28.dp),
                    contentDescription = null,
                    tint = tint,
                )
                Text(
                    text = tab.label,
                    fontSize = 10.sp,
                    lineHeight = 14.5f.sp,
                    fontWeight = FontWeight.Medium,
                    color = tint,
                )
            }
        }
    }
}
