package com.insou.autoa11y.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

// The background and the blue destinations need separate optical passes:
// the raised surface reads outward, while its contents bend inward at the rim.
private const val NAV_LENS_AGSL = """
uniform shader content;
uniform float2 uSize;
uniform float4 uLens;
uniform float uLift;
uniform float uDensity;
uniform float uContent;
uniform float uDark;

float4 sampleAt(float2 p) {
    return content.eval(clamp(p, float2(0.5), uSize - 0.5));
}

float4 main(float2 uv) {
    float2 p = uv - uLens.xy;
    float radius = min(uLens.z, uLens.w);
    float2 core = max(uLens.zw - radius, 0.0);
    float2 q = p - clamp(p, -core, core);
    float d = length(q) - radius;
    float mask = 1.0 - smoothstep(-0.8, 0.8, d);
    if (mask < 0.001) return float4(0.0);

    float edge = max(-d, 0.0);
    float2 n = q / max(length(q), 0.001);
    float bevel = mix(5.5, 11.2, uContent) * uDensity;
    float rise = 1.0 - clamp(edge / bevel, 0.0, 1.0);
    float2 shift;
    if (uContent > 0.5) {
        float bend = 14.0 * uDensity * uLift
            * (1.0 - sqrt(max(1.0 - rise * rise, 0.0)));
        shift = -n * bend;
    } else {
        shift = n * 4.3 * uDensity * uLift
            * smoothstep(0.0, bevel, edge)
            * (1.0 - smoothstep(bevel, max(radius, bevel + 1.0), edge));
    }

    float reach = 20.0 * uDensity;
    float spread = mix(6.5, 3.6, uContent) * uDensity * uLift;
    float2 part = n.yx * spread * 0.5
        * (0.6 + 0.4 * abs(n.y) * rise)
        * (1.0 - smoothstep(0.0, reach, edge));
    float4 center = sampleAt(uv + shift);
    float4 red = sampleAt(uv + shift + part);
    float4 blue = sampleAt(uv + shift - part);
    float alpha = max(center.a, max(red.a, blue.a));
    float3 rgb = float3(red.r, center.g, blue.b);
    rgb *= 1.0 + 0.33 * uDark * uLift;

    if (uContent > 0.5) {
        return float4(min(rgb, float3(alpha)), alpha) * mask;
    }

    // A narrow dark outline and a highlight on the top and bottom faces.
    rgb *= 1.0 - 0.045 * abs(n.x) * exp(-edge / (8.0 * uDensity));
    float rim = pow(1.0 - clamp(edge / (1.6 * uDensity), 0.0, 1.0), 2.0);
    rgb += (1.0 - rgb) * rim * (0.12 + 0.18 * abs(n.y));
    float line = 1.0 - smoothstep(0.0, 0.65 * uDensity, edge);
    rgb *= 1.0 - 0.42 * line;
    float coverage = mask * uLift;
    return float4(clamp(rgb, 0.0, 1.0) * coverage, coverage);
}
"""

private fun lensPath(bounds: Rect) = Path().apply {
    addRoundRect(RoundRect(bounds, CornerRadius(bounds.height / 2f)))
}

fun Modifier.outsideGlassLens(lens: () -> Rect): Modifier = drawWithContent {
    clipPath(lensPath(lens()), ClipOp.Difference) { this@drawWithContent.drawContent() }
}

private fun RuntimeShader.configureLens(
    bounds: Rect,
    width: Float,
    height: Float,
    outset: Float,
    density: Float,
    lift: Float,
    content: Boolean,
    dark: Boolean,
) {
    setFloatUniform("uSize", width, height)
    setFloatUniform(
        "uLens", bounds.center.x + outset, bounds.center.y + outset,
        bounds.width / 2f, bounds.height / 2f,
    )
    setFloatUniform("uLift", lift)
    setFloatUniform("uDensity", density)
    setFloatUniform("uContent", if (content) 1f else 0f)
    setFloatUniform("uDark", if (dark) 1f else 0f)
}

/** The raised lens includes room outside the dock, so a press is never clipped. */
fun Modifier.raisedGlassLens(
    backdrop: GraphicsLayer,
    surface: GraphicsLayer,
    paneOffset: () -> Offset,
    rootOffset: () -> Offset,
    backdropOrigin: () -> Offset,
    lens: () -> Rect,
    lift: () -> Float,
    dark: Boolean,
): Modifier = composed {
    val shader = remember { RuntimeShader(NAV_LENS_AGSL) }
    val work = rememberGraphicsLayer()
    val density = LocalDensity.current
    val outset = with(density) { 40.dp.toPx() }

    drawWithContent {
        val amount = lift().coerceIn(0f, 1f)
        if (amount > 0.001f) {
            val canvas = IntSize(
                ceil(size.width + outset * 2f).toInt(),
                ceil(size.height + outset * 2f).toInt(),
            )
            shader.configureLens(
                lens(), canvas.width.toFloat(), canvas.height.toFloat(),
                outset, density.density, amount, content = false, dark = dark,
            )
            // Capture the current uniform values after configuring the moving lens.
            work.renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
            val offset = paneOffset() - rootOffset() - backdropOrigin()
            work.record(canvas) {
                translate(outset - offset.x, outset - offset.y) { drawLayer(backdrop) }
                translate(outset, outset) { drawLayer(surface) }
            }
            translate(-outset, -outset) { drawLayer(work) }
        }
        drawContent()
    }
}

/** Only the destinations under the lens are blue, magnified, and refracted. */
fun Modifier.glassLensContent(
    lens: () -> Rect,
    lift: () -> Float,
    dark: Boolean,
): Modifier = composed {
    val shader = remember { RuntimeShader(NAV_LENS_AGSL) }
    val work = rememberGraphicsLayer()
    val density = LocalDensity.current
    val outset = with(density) { 40.dp.toPx() }

    drawWithContent {
        val amount = lift().coerceIn(0f, 1f)
        val bounds = lens()
        if (amount <= 0.001f) {
            clipPath(lensPath(bounds)) { this@drawWithContent.drawContent() }
        } else {
            val canvas = IntSize(
                ceil(size.width + outset * 2f).toInt(),
                ceil(size.height + outset * 2f).toInt(),
            )
            shader.configureLens(
                bounds, canvas.width.toFloat(), canvas.height.toFloat(),
                outset, density.density, amount, content = true, dark = dark,
            )
            // Capture the current uniform values after configuring the moving lens.
            work.renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
            work.record(canvas) {
                translate(outset, outset) { this@drawWithContent.drawContent() }
            }
            translate(-outset, -outset) { drawLayer(work) }
        }
    }
}
