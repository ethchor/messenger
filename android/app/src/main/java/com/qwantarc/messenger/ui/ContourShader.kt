package com.qwantarc.messenger.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * The Contours paper on the GPU (Android 13 and later): the terrain of
 * ethchor.qwantarc.com/vimu, drawn per pixel as the site's WebGL shader
 * does. Older phones trace it on the CPU instead.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class ContourShader {
    private val shader = RuntimeShader(SOURCE)
    private val brush = ShaderBrush(shader)

    fun DrawScope.draw(hill: FloatArray, line: Color, index: Color) {
        shader.setFloatUniform("hill", hill[0], hill[1], hill[2])
        shader.setFloatUniform("lineColor", line.red * line.alpha, line.green * line.alpha, line.blue * line.alpha, line.alpha)
        shader.setFloatUniform("indexColor", index.red * index.alpha, index.green * index.alpha, index.blue * index.alpha, index.alpha)
        shader.setFloatUniform("scale", density)
        drawRect(brush)
    }

    private companion object {
        const val SOURCE = """
uniform float3 hill;
uniform float4 lineColor;
uniform float4 indexColor;
uniform float scale;

float3 mod289(float3 x) { return x - 289.0 * floor(x / 289.0); }
float2 mod289(float2 x) { return x - 289.0 * floor(x / 289.0); }
float3 permute(float3 x) { return mod289((x * 34.0 + 1.0) * x); }

// Ashima Arts' 2D simplex noise, as on the site.
float snoise(float2 v) {
    const float4 C = float4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
    float2 i = floor(v + dot(v, C.yy));
    float2 x0 = v - i + dot(i, C.xx);
    float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod289(i);
    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));
    float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m;
    m = m * m;
    float3 x = 2.0 * fract(p * C.www) - 1.0;
    float3 h = abs(x) - 0.5;
    float3 ox = floor(x + 0.5);
    float3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    float3 g = float3(a0.x * x0.x + h.x * x0.y, a0.yz * x12.xz + h.yz * x12.yw);
    return 130.0 * dot(m, g);
}

// The height at a point (in dp), in contour steps.
float steps(float2 px) {
    float country = snoise(px / 560.0) * 0.62 + snoise(px / 240.0 + float2(17.0, 0.0)) * 0.27
        + snoise(px / 110.0 + float2(0.0, 31.0)) * 0.11;
    float2 d = px - hill.xy;
    return (country + hill.z * 0.5 * exp(-dot(d, d) / 33800.0)) * 13.3333;
}

half4 main(float2 fragCoord) {
    float2 px = fragCoord / scale;
    float k = steps(px);
    // How fast the height changes per pixel, for lines of an even width.
    float step = 1.0 / scale;
    float2 slope = float2(steps(px + float2(step, 0.0)) - k, steps(px + float2(0.0, step)) - k);
    float dist = abs(fract(k + 0.5) - 0.5) / max(length(slope), 0.0001);
    float n = floor(k + 0.5);
    bool strong = n - 5.0 * floor(n / 5.0) < 0.5;
    float a = clamp((strong ? 1.4 : 0.9) * scale * 0.5 + 0.5 - dist, 0.0, 1.0) * (strong ? 0.85 : 0.6);
    return half4((strong ? indexColor : lineColor) * a);
}
"""
    }
}
