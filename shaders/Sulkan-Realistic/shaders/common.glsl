#include "sulkan/frame.glsl"
vec3 toLinear(vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }
vec3 viewPosition(vec2 uv, float depth, mat4 inverseProjection) {
    vec4 p = inverseProjection * vec4(uv * 2.0 - 1.0, depth, 1.0);
    return p.xyz / (abs(p.w) < 1e-6 ? 1e-6 : p.w);
}
vec3 viewPosition(vec2 uv, float depth) { return viewPosition(uv, depth, InverseProjection); }
vec4 linearSample(sampler2D s, vec2 uv) {
    vec2 size = vec2(textureSize(s, 0));
    vec2 p = uv * size - 0.5, f = fract(p);
    ivec2 i = ivec2(floor(p)), hi = ivec2(size) - 1;
    return mix(mix(texelFetch(s, clamp(i, ivec2(0), hi), 0), texelFetch(s, clamp(i+ivec2(1,0), ivec2(0), hi), 0), f.x),
               mix(texelFetch(s, clamp(i+ivec2(0,1), ivec2(0), hi), 0), texelFetch(s, clamp(i+ivec2(1,1), ivec2(0), hi), 0), f.x), f.y);
}
