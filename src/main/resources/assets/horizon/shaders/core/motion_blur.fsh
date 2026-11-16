#version 330
#extension GL_ARB_separate_shader_objects : require

// Camera motion blur. Each pixel is taken back, through the world's depth, to where it was on the screen a shutter's
// time ago, and the frame is averaged along that path (centred on the pixel, a jittered sample spacing). With u_Mask,
// the hand (anything in Sampler2's depth) is left alone and never sampled, so it neither blurs nor smears into the world.

uniform sampler2D Sampler0; // the frame, copied
uniform sampler2D Sampler1; // the world's depth (only read when u_UseDepth is set)
uniform sampler2D Sampler2; // the hand's depth: 0 except where the hand is (only read when u_Mask is set)

layout(std140) uniform UBO {
    mat4 u_Reproject;   // (u, v, depth, 1) -> the earlier camera's clip space in uv units
    vec2 u_Size;        // the frame in pixels
    float u_Scale;      // the exposure over the time back to the earlier camera
    float u_MaxLength;  // the longest blur in pixels
    float u_Samples;    // the most samples per pixel
    float u_Noise;      // this frame's jitter offset
    float u_UseDepth;   // 0: a pure turn, the depth doesn't matter
    float u_Mask;       // 1: the hand is already drawn, leave it out
};

layout(location = 0) in vec2 uv;

layout(location = 0) out vec4 color;

void main() {
    bool mask = u_Mask > 0.5;
    if (mask && texture(Sampler2, uv).r > 0.0) discard;
    float depth = u_UseDepth > 0.5 ? texture(Sampler1, uv).r : 0.5;
    vec4 then = u_Reproject * vec4(uv, depth, 1.0);
    if (then.w <= 1e-6) discard;
    vec2 v = (uv - then.xy / then.w) * u_Scale;
    float len = length(v * u_Size);
    if (len < 0.5) discard;
    if (len > u_MaxLength) {
        v *= u_MaxLength / len;
        len = u_MaxLength;
    }
    // About a sample per pixel of blur, up to u_Samples.
    int n = int(clamp(ceil(len), 2.0, u_Samples));
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))) + u_Noise);
    vec2 lo = 0.5 / u_Size;
    vec2 hi = 1.0 - lo;
    vec3 sum = vec3(0.0);
    float weight = 0.0;
    for (int i = 0; i < n; i++) {
        vec2 p = clamp(uv + v * ((float(i) + jitter) / float(n) - 0.5), lo, hi);
        if (mask && texture(Sampler2, p).r > 0.0) continue;
        sum += texture(Sampler0, p).rgb;
        weight += 1.0;
    }
    if (weight < 1.0) discard;
    color = vec4(sum / weight, texture(Sampler0, uv).a);
}
