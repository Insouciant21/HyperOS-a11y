package com.insou.autoa11y.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize

/**
 * Ultra-quality pill optics, ported from dock (LiquidGlassStyle.pill
 * at LiquidGlassQuality.ultra):
 *  - bar glass: circular-bevel rim refraction + haze frost + tint + rim
 *    highlight + iOS hairline, across the whole bar;
 *  - goo selection: the lens capsule and (while pressed) the finger pill are
 *    smooth-min merged into one liquid blob, tinted with the selection color
 *    and given its own bright rim — the liquid flow between destinations;
 *  - touch glow: gaussian light under the finger.
 * The backdrop is pre-frosted by a RenderEffect on the capture layer; the
 * shader refracts reads of that frosted texture.
 */
private const val LENS_AGSL = """
uniform shader content;
uniform float2 uSize;
uniform float uCorner;
uniform float uBevel;
uniform float uDepth;
uniform float uSat;
uniform float uRim;
uniform float uRimW;
uniform float4 uTint;
uniform float uLine;
uniform float uDrag;
uniform float2 uGlowC;
uniform float uGlowS;
uniform float uGlowR;
// goo selection: lens capsule (cx,cy,halfSeg,radius) + tint, always on
uniform float4 uLens;
uniform float4 uLensTint;
// finger pill while pressed (cx,cy,halfSeg,radius) + presence
uniform float4 uDest;
uniform float uDestP;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// Horizontal capsule: segment half-length hl, radius r.
float sdPill(float2 p, float2 c, float hl, float r) {
    float2 q = abs(p - c) - float2(hl, 0.0);
    q.x = max(q.x, 0.0);
    return length(q) - r;
}

float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

float4 tap(float2 p) {
    return content.eval(clamp(p, float2(0.5), uSize - 0.5));
}

float4 main(float2 fragCoord) {
    float2 uv = fragCoord;
    float2 cs = uSize * 0.5;
    float2 b = cs - 1.0;
    float2 p = uv - cs;
    float dBar = sdRoundBox(p, b, uCorner);
    float barEdge = -dBar;

    float eps = 1.0;
    float dx = sdRoundBox(p + float2(eps, 0.0), b, uCorner) - dBar;
    float dy = sdRoundBox(p + float2(0.0, eps), b, uCorner) - dBar;
    float2 nBar = normalize(float2(dx, dy) + 1e-5);

    // Bar-rim refraction: circular bevel, samples inward.
    float halfMinor = min(cs.x, cs.y);
    float rise = 1.0 - clamp(barEdge / uBevel, 0.0, 1.0);
    float depth = min(uDepth, halfMinor) * (1.0 - sqrt(1.0 - rise * rise));
    float2 src = uv - nBar * depth;

    float4 s = tap(src);
    float3 col = s.rgb;
    if (uDrag > 0.001) {
        float part = (1.0 - smoothstep(0.0, uBevel * 1.6, barEdge)) * uDrag;
        col = float3(
            tap(src + nBar * part * 3.0).r,
            tap(src).g,
            tap(src - nBar * part * 3.0).b
        );
    }

    float l = dot(col, float3(0.299, 0.587, 0.114));
    col = mix(float3(l), col, uSat);
    col = mix(col, uTint.rgb, uTint.a);

    // Goo selection: lens capsule merged (smooth-min) with the finger pill.
    float dLens = sdPill(p, uLens.xy, uLens.z, uLens.w);
    float gooEdge = -dLens;
    if (uDestP > 0.003) {
        float dDest = sdPill(p, uDest.xy, uDest.z, uDest.w);
        float dGoo = smin(dLens, dDest, 60.0);
        gooEdge = -dGoo;
    }
    float gooMask = 1.0 - smoothstep(-1.5, 1.5, gooEdge);
    col = mix(col, uLensTint.rgb, uLensTint.a * gooMask);

    // Bright rim around the goo blob, lit all around (selection pill edge).
    float gband = 1.0 - smoothstep(0.0, 5.0, gooEdge);
    col += (uRim + 0.45) * gband * gband * gooMask;

    // Bar rim highlight, biased to the top edge; boosted while dragging.
    float band = 1.0 - smoothstep(0.0, uRimW, barEdge);
    float topBias = clamp(0.5 - nBar.y * 0.5, 0.0, 1.0);
    float bottomBias = clamp(0.5 + nBar.y * 0.5, 0.0, 1.0) * 0.35;
    col += (uRim + uDrag * 0.5) * band * band * (topBias + bottomBias * 0.5);

    // iOS 27 dark hairline right at the rim.
    col *= 1.0 - 0.298 * (1.0 - smoothstep(0.0, uLine, barEdge));

    // Touch glow: gaussian light under the finger.
    float gd = distance(uv, uGlowC);
    col += uGlowS * exp(-(gd * gd) / (2.0 * uGlowR * uGlowR));

    // Coverage = bar shape (soft AA edge, premultiplied out).
    float alpha = 1.0 - smoothstep(-1.0, 1.0, dBar);
    return float4(col * alpha, alpha);
}
"""

/** Captures the modified content into [layer] (root-box coordinates). */
fun Modifier.glassSource(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record(size.toIntSize()) {
        this@drawWithContent.drawContent()
    }
    drawLayer(layer)
}

@Composable
fun rememberGlassBackdrop(): GraphicsLayer = rememberGraphicsLayer()

/** Selection goo capsule, in pane-local px. */
class GooPill(val cx: Float, val cy: Float, val halfSeg: Float, val radius: Float, val tint: Color)

fun Modifier.liquidGlass(
    backdrop: GraphicsLayer,
    paneOffsetInRoot: () -> Offset,
    rootOffsetInRoot: () -> Offset,
    cornerRadius: Dp = 34.dp,
    bevel: Dp = 20.dp,
    depth: Dp = 60.dp,
    hazeSigma: Dp = 4.5.dp,
    rim: Float = 0.35f,
    rimWidth: Dp = 4.5.dp,
    saturation: Float = 1.925f,
    tint: Color,
    glowCenter: () -> Offset = { Offset(-1f, -1f) },
    glowStrength: () -> Float = { 0f },
    glowReach: Dp = 120.dp,
    drag: () -> Float = { 0f },
    gooLens: (() -> GooPill)? = null,
    gooDest: (() -> GooPill?)? = null,
): Modifier = composed {
    val shader = remember { RuntimeShader(LENS_AGSL) }
    val work = rememberGraphicsLayer()
    val density = LocalDensity.current
    val tintSpec = remember(tint) {
        floatArrayOf(tint.red, tint.green, tint.blue, tint.alpha)
    }

    this.then(
        Modifier.drawWithContent {
        val offset = paneOffsetInRoot() - rootOffsetInRoot()
        if (offset.x.isNaN() || size.isEmpty()) {
            drawContent()
            return@drawWithContent
        }

        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uCorner", with(density) { cornerRadius.toPx() })
        shader.setFloatUniform("uBevel", with(density) { bevel.toPx() })
        shader.setFloatUniform("uDepth", with(density) { depth.toPx() })
        shader.setFloatUniform("uSat", saturation)
        shader.setFloatUniform("uRim", rim)
        shader.setFloatUniform("uRimW", with(density) { rimWidth.toPx() })
        shader.setFloatUniform("uTint", tintSpec[0], tintSpec[1], tintSpec[2], tintSpec[3])
        shader.setFloatUniform("uLine", with(density) { 0.67.dp.toPx() })
        shader.setFloatUniform("uDrag", drag())
        shader.setFloatUniform("uGlowC", glowCenter().x, glowCenter().y)
        shader.setFloatUniform("uGlowS", glowStrength())
        shader.setFloatUniform("uGlowR", with(density) { glowReach.toPx() })

        val lensPill = gooLens?.invoke()
        if (lensPill != null) {
            shader.setFloatUniform("uLens", lensPill.cx, lensPill.cy, lensPill.halfSeg, lensPill.radius)
            shader.setFloatUniform(
                "uLensTint",
                lensPill.tint.red, lensPill.tint.green, lensPill.tint.blue, lensPill.tint.alpha,
            )
            val destPill = gooDest?.invoke()
            if (destPill != null) {
                shader.setFloatUniform("uDest", destPill.cx, destPill.cy, destPill.halfSeg, destPill.radius)
                shader.setFloatUniform("uDestP", 1f)
            } else {
                shader.setFloatUniform("uDestP", 0f)
            }
        } else {
            shader.setFloatUniform("uDestP", 0f)
        }

        //  pill pipeline: frosted backdrop first (gaussian), then the
        // optics shader refracts reads of the frosted texture.
        val frostPx = with(density) { hazeSigma.toPx() }.coerceAtLeast(0.1f)
        val runtime = RenderEffect.createRuntimeShaderEffect(shader, "content")
        val blur = RenderEffect.createBlurEffect(frostPx, frostPx, Shader.TileMode.CLAMP)
        work.renderEffect = RenderEffect.createChainEffect(runtime, blur)
            .asComposeRenderEffect()

        work.record(size.toIntSize()) {
            // Shift the root-space backdrop so the pane's area lands at the
            // work layer's origin: root-point (offset + u) -> layer point u.
            translate(-offset.x, -offset.y) {
                drawLayer(backdrop)
            }
        }

            drawLayer(work)
            drawContent()
        }
    )

}
