package com.insou.autoa11y.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize

/**
 *  optics, ported to AGSL:
 *  - circular-bevel refraction (positive branch): depth peaks AT the rim and
 *    dies out a bevel in, sampling INWARD so the rim shows the content
 *    squeezed, as iOS tab bars do;
 *  - no dispersion (the pill style has none);
 *  - haze instead of uniform frost: [haze] of a wide blur blended over the
 *    sharp read (: 0.72 wide / 0.28 sharp);
 *  - rim highlight just inside the hairline, top-biased.
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
// Touch glow: center (pane px), strength 0..1, reach sigma (px).
uniform float2 uGlowC;
uniform float uGlowS;
uniform float uGlowR;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// Clamp samples to the layer's own bounds: reading past the recorded backdrop
// yields transparent black (rim artifacts).
float4 tap(float2 p) {
    return content.eval(clamp(p, float2(0.5), uSize - 0.5));
}

float4 main(float2 fragCoord) {
    float2 uv = fragCoord;
    float2 cs = uSize * 0.5;
    float2 b = cs - 1.0;
    float2 p = uv - cs;
    float d = sdRoundBox(p, b, uCorner);
    float edge = -d;
    float halfMinor = min(cs.x, cs.y);

    float eps = 1.0;
    float dx = sdRoundBox(p + float2(eps, 0.0), b, uCorner) - d;
    float dy = sdRoundBox(p + float2(0.0, eps), b, uCorner) - d;
    float2 n = normalize(float2(dx, dy) + 1e-5);

    // The content texture is the PRE-BLURRED backdrop (RenderEffect on the
    // capture layer). Refraction samples it inward with the circular-bevel
    // sagitta; rim shows the content squeezed, iOS tab bar style.
    float rise = 1.0 - clamp(edge / uBevel, 0.0, 1.0);
    float depth = min(uDepth, halfMinor) * (1.0 - sqrt(1.0 - rise * rise));
    float2 src = uv - n * depth;
    float4 s = tap(src);

    float3 col = s.rgb;
    float l = dot(col, float3(0.299, 0.587, 0.114));
    col = mix(float3(l), col, uSat);
    col = mix(col, uTint.rgb, uTint.a);

    // Touch glow: light washing out from under the finger (UIKit-style).
    float gd = distance(uv, uGlowC);
    col += uGlowS * exp(-(gd * gd) / (2.0 * uGlowR * uGlowR));

    // Rim highlight just inside the line, biased to the top edge.
    float band = 1.0 - smoothstep(0.0, uRimW, edge);
    float topBias = clamp(0.5 - n.y * 0.5, 0.0, 1.0);
    float bottomBias = clamp(0.5 + n.y * 0.5, 0.0, 1.0) * 0.35;
    col += uRim * band * band * (topBias + bottomBias * 0.5);

    // iOS 27 dark hairline right at the rim.
    col *= 1.0 - 0.298 * (1.0 - smoothstep(0.0, uLine, edge));
    return float4(col, 1.0);
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

@Composable
fun Modifier.liquidGlass(
    backdrop: GraphicsLayer,
    paneOffsetInRoot: () -> Offset,
    rootOffsetInRoot: () -> Offset,
    cornerRadius: Dp = 34.dp,
    //  / class defaults
    bevel: Dp = 20.dp,
    depth: Dp = 60.dp,
    // Haze pre-blur sigma applied to the captured backdrop layer.
    // pill: haze 0.72 @ hazeBlur 15.6 — but that sigma is in the
    // DOWNSAMPLED haze texture's pixels (captured at ~1/3 texelsPerDp), i.e.
    // ~5 screen dp. Applying 15.6 at full resolution over-blurs into an
    // opaque-looking wash.
    hazeSigma: Dp = 4.5.dp,
    rim: Float = 0.35f,
    rimWidth: Dp = 4.5.dp,
    saturation: Float = 1.925f,
    tint: Color,
    // Touch glow driven by the tab row (LiquidGlassGlow).
    glowCenter: () -> Offset = { Offset(-1f, -1f) },
    glowStrength: () -> Float = { 0f },
    glowReach: Dp = 120.dp,
): Modifier {
    val shader = remember { RuntimeShader(LENS_AGSL) }
    val work = rememberGraphicsLayer()
    val density = LocalDensity.current
    val tintSpec = remember(tint) {
        floatArrayOf(tint.red, tint.green, tint.blue, tint.alpha)
    }

    return drawWithContent {
        val offset = paneOffsetInRoot() - rootOffsetInRoot()
        if (offset.x.isNaN() || size.isEmpty()) {
            drawContent()
            return@drawWithContent
        }

        val cornerPx = with(density) { cornerRadius.toPx() }
        val bevelPx = with(density) { bevel.toPx() }
        val depthPx = with(density) { depth.toPx() }
        val rimWPx = with(density) { rimWidth.toPx() }

        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uCorner", cornerPx)
        shader.setFloatUniform("uBevel", bevelPx)
        shader.setFloatUniform("uDepth", depthPx)
        shader.setFloatUniform("uSat", saturation)
        shader.setFloatUniform("uRim", rim)
        shader.setFloatUniform("uRimW", rimWPx)
        shader.setFloatUniform("uTint", tintSpec[0], tintSpec[1], tintSpec[2], tintSpec[3])
        shader.setFloatUniform("uLine", with(density) { 0.67.dp.toPx() })
        shader.setFloatUniform("uGlowC", glowCenter().x, glowCenter().y)
        shader.setFloatUniform("uGlowS", glowStrength())
        shader.setFloatUniform("uGlowR", with(density) { glowReach.toPx() })

        //  pill pipeline: the captured backdrop is gaussian-frosted
        // (sigma 15.6 × haze 0.72), then the shader refracts reads of that
        // frosted texture — blur before optics, never tap-sampled in-shader.
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

        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    0f, 0f, size.width, size.height,
                    CornerRadius(cornerPx, cornerPx),
                ),
            )
        }
        clipPath(path) {
            drawLayer(work)
        }
        drawContent()
    }
}
