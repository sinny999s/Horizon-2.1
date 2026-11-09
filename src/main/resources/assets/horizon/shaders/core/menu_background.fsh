#version 330
#extension GL_ARB_separate_shader_objects : require

// The menu background, in black through white only (no hue, whatever the theme). Near-black; a
// horizon just under the middle with a soft white light rising from it, brightest to the right of the menu's column, a
// thin bright line along it and a faint reflection below. Fog at two depths drifts sideways along the horizon, grey in
// the dark and lit where the light reaches it. Dust hangs in the air at two depths, catching the light near the horizon,
// and a few out-of-focus motes float right in front of the lens. A slow camera drift gives parallax. Graded with a filmic
// curve, a vignette and film grain.

layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

layout(location = 0) in vec2 params;  // x: seconds, y: 0 for the title screen, 1 for the other menus (calmer, darker)
layout(location = 1) in vec4 accent;  // the theme's colour (unused: the backdrop is grey); alpha is the opacity

layout(location = 0) out vec4 fragColor;

// ---- noise ----

vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy) * 2.0 - 1.0;
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Gradient noise with a quintic fade: smooth, no blobs; about -0.7..0.7.
float gnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = dot(hash22(i), f);
    float b = dot(hash22(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0));
    float c = dot(hash22(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0));
    float d = dot(hash22(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Five octaves, each turned and shifted so their grids never line up.
const mat2 OCTAVE = mat2(1.6, 1.2, -1.2, 1.6);

float fbm(vec2 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 5; i++) {
        sum += amp * gnoise(p);
        p = OCTAVE * p + vec2(1.7, 9.2);
        amp *= 0.5;
    }
    return sum;
}

// Interleaved gradient noise: an even, pattern-free dither.
float dither(vec2 frag) {
    return fract(52.9829189 * fract(dot(frag, vec2(0.06711056, 0.00583715))));
}

// ---- fog ----

// One depth of fog, stretched along the horizon and drifting sideways at `speed`, curled by a slow noise field. Returns
// its soft body and the thin brighter wisps along one of its folds.
vec2 fog(vec2 p, float t, float scale, float speed, float seed) {
    vec2 q = p * vec2(scale * 0.55, scale * 1.5) + vec2(seed + t * speed, seed * 0.37);
    vec2 w = vec2(fbm(q * 0.6 + vec2(0.0, t * 0.02)), fbm(q * 0.6 + vec2(5.2, 1.3 - t * 0.015)));
    float n = fbm(q + w * 1.4);
    float body = smoothstep(-0.1, 0.5, n);
    float wisps = exp(-pow((n - 0.18) / 0.03, 2.0)) * smoothstep(-0.25, 0.2, w.x);
    return vec2(body, wisps);
}

// ---- dust ----

// One depth of dust: a grid of cells drifting up at `speed` and swaying; `share` of the cells hold a mote somewhere near
// their middle, a tiny core inside a soft glow, slowly brightening and dimming as it turns; faded out before its cell's
// edge so it is never cut off. Returns (core, glow).
vec2 dust(vec2 p, float scale, float speed, float share, float t, float seed) {
    vec2 q = p * scale;
    q.y -= t * speed;
    q.x += sin(q.y * 0.35 + seed + t * 0.12) * 0.4 + t * speed * 0.6;
    vec2 cell = floor(q);
    float h = hash12(cell + seed);
    if (h < 1.0 - share) return vec2(0.0);
    vec2 local = fract(q) - 0.5;
    vec2 at = (vec2(hash12(cell + seed + 1.3), hash12(cell + seed + 2.7)) - 0.5) * 0.4;
    vec2 d = local - at;
    float r2 = dot(d, d);
    float size = mix(0.5, 1.3, hash12(cell + seed + 5.9));
    float turn = 0.45 + 0.55 * (0.5 + 0.5 * sin(t * (0.6 + 1.2 * h) + h * 60.0));
    float inside = smoothstep(0.5, 0.36, max(abs(local.x), abs(local.y)));
    float k = turn * inside;
    return vec2(exp(-r2 / (0.00030 * size)), exp(-r2 / (0.005 * size)) * 0.3) * k;
}

// Out-of-focus motes right in front of the lens: soft discs with a slightly brighter rim.
float bokeh(vec2 p, float scale, float speed, float share, float t, float seed) {
    vec2 q = p * scale;
    q.y -= t * speed;
    q.x += sin(q.y * 0.3 + seed + t * 0.1) * 0.3;
    vec2 cell = floor(q);
    float h = hash12(cell + seed);
    if (h < 1.0 - share) return 0.0;
    vec2 at = (vec2(hash12(cell + seed + 1.3), hash12(cell + seed + 2.7)) - 0.5) * 0.25;
    float r = mix(0.10, 0.17, hash12(cell + seed + 3.3));
    float d = length(fract(q) - 0.5 - at);
    float disc = smoothstep(r, r * 0.82, d) * (0.75 + 0.25 * smoothstep(r * 0.4, r * 0.9, d));
    return disc * (0.6 + 0.4 * sin(t * 0.6 + h * 30.0));
}

void main() {
    vec2 res = max(ScreenSize, vec2(1.0));
    vec2 frag = gl_FragCoord.xy;
    vec2 uv = frag / res;
    float aspect = res.x / res.y;
    vec2 p = (uv - 0.5) * vec2(aspect, 1.0);  // screen heights, centred; y up
    float t = params.x;
    float calm = params.y;

    // A slow drift of the camera; the nearer a layer, the more it moves.
    vec2 cam = vec2(sin(t * 0.06), cos(t * 0.045)) * 0.02;

    // The horizon, just under the middle, and its light: centred right of the middle (clear of the menu on the left),
    // breathing very slowly.
    float hy = -0.1 + cam.y * 0.2;
    float dy = p.y - hy;
    float sx = 0.18 * aspect;
    float side = exp(-pow((p.x - sx) / (0.55 * aspect), 2.0));
    float breathe = 0.93 + 0.07 * sin(t * 0.21) + 0.03 * gnoise(vec2(t * 0.15, 4.2));

    // Black at the top lifting to a dim grey over the horizon; under it, darker ground with a faint reflection.
    vec3 col = vec3(0.006);
    float sky = exp(-max(dy, 0.0) * 3.4);
    float ground = exp(-max(-dy, 0.0) * 11.0);
    col += vec3(0.075) * (dy >= 0.0 ? sky : ground * 0.7) * (0.3 + 0.7 * side) * breathe;
    // The heart of the light: a wide soft glow and a tighter bright one, flattened along the horizon.
    float d = length((p - vec2(sx, hy)) * vec2(0.45, 1.7));
    col += vec3(0.16) * exp(-d * 3.2) * breathe + vec3(0.30) * exp(-d * 13.0) * breathe;
    // The horizon line itself: a crisp hairline, brightest under the light, fading out towards the sides and gone
    // before the menu's column on the left.
    float line = exp(-pow(dy / 0.0016, 2.0)) + 0.35 * exp(-pow(dy / 0.006, 2.0));
    col += vec3(0.75) * line * (0.08 + 0.92 * side) * smoothstep(0.24, 0.5, uv.x) * breathe;

    // Fog at two depths: far (broad, slow) and near (finer, quicker), thickest along the horizon and lit there.
    float lit = exp(-abs(dy) * 4.5) * (0.35 + 0.65 * side) * breathe;
    vec2 far = fog(p + cam * 0.3, t, 1.2, 0.018, 3.0);
    vec2 near = fog(p + cam, t, 2.2, 0.04, 17.0);
    float bandFar = exp(-pow(dy / 0.45, 2.0));
    float bandNear = exp(-pow((dy + 0.08) / 0.32, 2.0));
    col += vec3(1.0) * (far.x * (0.018 + 0.16 * lit) + far.y * 0.10 * lit) * bandFar;
    col += vec3(1.0) * (near.x * (0.014 + 0.20 * lit) + near.y * 0.18 * lit) * bandNear;

    // Dust: tiny far motes and brighter mid ones, catching the light near the horizon; a few blurred ones in front.
    float catchLight = 0.25 + 0.75 * exp(-abs(dy) * 2.5) * (0.4 + 0.6 * side);
    vec2 d1 = dust(p + cam * 0.4, 26.0, 0.020, 0.06, t, 3.0);
    vec2 d2 = dust(p + cam * 0.8, 12.0, 0.014, 0.07, t, 11.0);
    float b = bokeh(p + cam * 1.6, 3.0, 0.035, 0.10, t, 23.0);
    col += vec3(1.0) * ((d1.x * 0.35 + d2.x * 0.9) + (d1.y * 0.3 + d2.y * 0.6)) * catchLight;
    col += vec3(1.0) * b * 0.035 * catchLight;

    // Grade: the pages' calmer version, a filmic curve, a vignette, then film grain (stronger in the dark) and dither
    // against banding. Everything above is grey, so it stays grey.
    col *= mix(1.0, 0.6, calm);
    col = col * (2.51 * col + 0.03) / (col * (2.43 * col + 0.59) + 0.14);
    float luma = col.g;
    vec2 v = (uv - 0.5) * vec2(aspect * 0.75, 1.0);
    col *= 1.0 - 0.6 * smoothstep(0.25, 1.05, length(v));
    float grain = (hash12(frag + fract(t * 7.13) * 97.0) - 0.5) * 0.022 * (1.0 - 0.6 * luma) + (dither(frag) - 0.5) / 255.0;
    col += vec3(grain);

    fragColor = vec4(max(col, 0.0), accent.a) * ColorModulator;
}
