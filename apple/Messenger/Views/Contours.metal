// The Contours chat paper: the terrain of ethchor.qwantarc.com/vimu, drawn
// per pixel on the GPU. A fixed landscape of three octaves of simplex noise,
// a hill that rises under the finger or the pointer, and a contour line
// every 0.075 of height, every fifth one stronger.

#include <metal_stdlib>
#include <SwiftUI/SwiftUI_Metal.h>
using namespace metal;

// GLSL's mod: never negative for a positive divisor.
static float3 mod289(float3 x) { return x - 289.0 * floor(x / 289.0); }
static float2 mod289(float2 x) { return x - 289.0 * floor(x / 289.0); }
static float3 permute(float3 x) { return mod289((x * 34.0 + 1.0) * x); }

// Ashima Arts' 2D simplex noise, as on the site.
static float snoise(float2 v) {
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
    float3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
}

// The height at a point (in points), in contour steps.
static float steps(float2 px, float3 hill) {
    float country = snoise(px / 560.0) * 0.62 + snoise(px / 240.0 + float2(17.0, 0.0)) * 0.27
        + snoise(px / 110.0 + float2(0.0, 31.0)) * 0.11;
    float2 d = px - hill.xy;
    return (country + hill.z * 0.5 * exp(-dot(d, d) / 33800.0)) * 13.3333;
}

// hill: x, y in points and how far it's risen (0 to 1). scale: pixels per point.
[[ stitchable ]] half4 contours(float2 position, half4 color, float3 hill, half4 line, half4 index, float scale) {
    float k = steps(position, hill);
    // How fast the height changes per pixel, for lines of an even width.
    float step = 1.0 / scale;
    float2 slope = float2(steps(position + float2(step, 0.0), hill) - k, steps(position + float2(0.0, step), hill) - k);
    float dist = abs(fract(k + 0.5) - 0.5) / max(length(slope), 1e-4);
    float n = floor(k + 0.5);
    bool strong = n - 5.0 * floor(n / 5.0) < 0.5;
    float a = clamp((strong ? 1.4 : 0.9) * scale * 0.5 + 0.5 - dist, 0.0, 1.0) * (strong ? 0.85 : 0.6);
    return (strong ? index : line) * half(a);
}
