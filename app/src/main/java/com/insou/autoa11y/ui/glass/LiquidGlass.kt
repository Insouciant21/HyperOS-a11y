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
 * -style liquid glass lens: the pane samples the captured backdrop with
 * edge refraction (SDF rounded-box normal), saturation boost, top rim highlight,
 * tint and a hairline inner edge — chained after a light frost blur.
 * Coordinates: [liquidGlass] samples [backdrop] (captured by [glassSource] in
 * root-box coordinates) at the pane's offset within that box.
 */
private const val LENS_AGSL = """
uniform shader content;
uniform float2 uSize;
uniform float uCorner;
uniform float uBevel;
uniform float uDepth;
uniform float uSat;
uniform float uRim;
uniform float4 uTint;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float4 main(float2 fragCoord) {
    float2 uv = fragCoord;
    float2 cs = uSize * 0.5;
    float2 b = cs - 1.0;
    float2 p = uv - cs;
    float d = sdRoundBox(p, b, uCorner);
    float edge = -d;
    float t = 1.0 - smoothstep(0.0, uBevel, edge);

    float eps = 1.0;
    float dx = sdRoundBox(p + float2(eps, 0.0), b, uCorner) - d;
    float dy = sdRoundBox(p + float2(0.0, eps), b, uCorner) - d;
    float2 n = normalize(float2(dx, dy) + 1e-5);

    // Refraction: near the rim, sample outward — the lens pulls its
    // surroundings inward, bending content around the edge.
    float bend = pow(t, 1.5);
    float2 src = uv + n * bend * uDepth;
    float3 col = content.eval(src).rgb;

    float l = dot(col, float3(0.299, 0.587, 0.114));
    col = mix(float3(l), col, uSat);
    col = mix(col, uTint.rgb, uTint.a);

    // Rim highlight: a narrow lit band along the edge, biased to the top.
    float band = 1.0 - smoothstep(0.0, uBevel * 0.45, edge);
    float topBias = clamp(0.5 - n.y * 0.5, 0.0, 1.0);
    float bottomBias = clamp(0.5 + n.y * 0.5, 0.0, 1.0) * 0.35;
    col += uRim * band * band * (topBias + bottomBias * 0.5);

    // Hairline darkening right at the edge for definition.
    col *= 1.0 - 0.10 * smoothstep(2.0, 0.0, edge);
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
    bevel: Dp = 26.dp,
    depth: Dp = 46.dp,
    frost: Dp = 1.5.dp,
    saturation: Float = 1.6f,
    rim: Float = 0.9f,
    tint: Color,
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
        val frostPx = with(density) { frost.toPx() }.coerceAtLeast(0.1f)

        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uCorner", cornerPx)
        shader.setFloatUniform("uBevel", bevelPx)
        shader.setFloatUniform("uDepth", depthPx)
        shader.setFloatUniform("uSat", saturation)
        shader.setFloatUniform("uRim", rim)
        shader.setFloatUniform("uTint", tintSpec[0], tintSpec[1], tintSpec[2], tintSpec[3])

        val runtime = RenderEffect.createRuntimeShaderEffect(shader, "content")
        val blur = RenderEffect.createBlurEffect(frostPx, frostPx, Shader.TileMode.CLAMP)
        val chain = RenderEffect.createChainEffect(runtime, blur)
        work.renderEffect = chain.asComposeRenderEffect()

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
