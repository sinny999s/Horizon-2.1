#ifndef SULKAN_NATIVE_SHADOWS
#define SULKAN_NATIVE_SHADOWS
#ifndef SHADOW_QUALITY
#define SHADOW_QUALITY 2
#endif
#if SHADOW_QUALITY > 0
#define SULKAN_SHADOW_FILTER_TIER SHADOW_QUALITY
uniform sampler2D SulkanShadowMap0;
uniform sampler2D SulkanShadowMap1;
uniform sampler2D SulkanShadowMap2;
uniform sampler2D SulkanShadowMap3;

uniform sampler2D SulkanEntityShadowMap0;
uniform sampler2D SulkanEntityShadowMap1;
#ifdef SULKAN_TRANSLUCENT_SHADOWS
// Colored shadow maps (the pack's environment.translucentShadows): per cascade, the light's transmittance through
// translucent terrain (rgb, 1 where there's none) and the nearest translucent caster's depth (a).
uniform sampler2D SulkanShadowColor0;
uniform sampler2D SulkanShadowColor1;
uniform sampler2D SulkanShadowColor2;
uniform sampler2D SulkanShadowColor3;
#endif
layout(std140) uniform SulkanShadowData {
    mat4 ShadowViewProjection[4];
    vec4 CascadeParams[4];
    vec4 LightDirection;
    vec4 ShadowParams;
};
#include "sulkan/shadow-filter.glsl"
float sulkanTerrainShadow(vec3 position, vec3 normal) {
    return mix(1.0, sulkanDirectionalVisibility(position, normal), ShadowParams.x);
}

float sulkanDirectShadow(vec3 position, vec3 normal) {
    return sulkanCastShadowVisibility(position,normal);
}
float sulkanGrassShadow(vec3 position, vec3 normal) {
    
    
    float visibility=sulkanCastShadowVisibility(position,normal,true);
    
    
    return mix(1.0,mix(ShadowParams.z,1.0,visibility),ShadowParams.x);
}

float sulkanTerrainShadows(vec3 position, vec3 normal, bool grass, out float direct) {
    direct=sulkanCastShadowVisibility(position,normal,grass);
    if(grass) return mix(1.0,mix(ShadowParams.z,1.0,direct),ShadowParams.x);
    float normalLight=dot(normal,LightDirection.xyz);
    float visibility=ShadowParams.z;
    if(normalLight>0.0) {
        float faceVisibility=mix(ShadowParams.z,1.0,smoothstep(0.0,0.08,normalLight));
        visibility=min(faceVisibility,mix(ShadowParams.z,1.0,direct));
    }
    return mix(1.0,visibility,ShadowParams.x);
}

float sulkanVolumeShadow(vec3 position) {
    int cascade=sulkanSelectCascade(length(position));
    int last=sulkanLastCascade();
    if(length(position)>=CascadeParams[last].z) return 1.0;
    vec3 coord=sulkanShadowCoord(cascade,position);
    if(any(lessThan(coord,vec3(0))) || any(greaterThan(coord,vec3(1)))) return 1.0;
    float visible=sulkanShadowBilinearVisibility(cascade,coord.xy,coord.xy,coord.z,vec2(0));
    float coverage=1.0-smoothstep(mix(CascadeParams[last].y,CascadeParams[last].z,0.88),CascadeParams[last].z,length(position));
    return mix(1.0,visible,coverage);
}

#ifdef SULKAN_TRANSLUCENT_SHADOWS
vec4 sulkanShadowColorTexel(int cascade, vec2 uv) {
    if (cascade == 0) return textureLod(SulkanShadowColor0, uv, 0.0);
    if (cascade == 1) return textureLod(SulkanShadowColor1, uv, 0.0);
    if (cascade == 2) return textureLod(SulkanShadowColor2, uv, 0.0);
    return textureLod(SulkanShadowColor3, uv, 0.0);
}

// The four nearest texels' caster depths (one fetch).
vec4 sulkanShadowColorDepths(int cascade, vec2 uv) {
    if (cascade == 0) return textureGather(SulkanShadowColor0, uv, 3);
    if (cascade == 1) return textureGather(SulkanShadowColor1, uv, 3);
    if (cascade == 2) return textureGather(SulkanShadowColor2, uv, 3);
    return textureGather(SulkanShadowColor3, uv, 3);
}

vec2 sulkanShadowColorSize(int cascade) {
    if (cascade == 0) return vec2(textureSize(SulkanShadowColor0, 0));
    if (cascade == 1) return vec2(textureSize(SulkanShadowColor1, 0));
    if (cascade == 2) return vec2(textureSize(SulkanShadowColor2, 0));
    return vec2(textureSize(SulkanShadowColor3, 0));
}

vec4 sulkanTranslucentShadowSample(int cascade, vec3 position, vec3 normal) {
    vec3 coord = sulkanShadowCoord(cascade, position + normal * sulkanShadowNormalOffset(cascade, normal));
    if (any(lessThan(coord, vec3(0.0))) || any(greaterThan(coord, vec3(1.0)))) return vec4(-1.0);
    vec2 texel = 1.0 / sulkanShadowColorSize(cascade);
    // The colored maps are coarser than the depth maps: allow a texel's worth of slope.
    float worldTexel = CascadeParams[cascade].x * texel.x / sulkanShadowTexel(cascade).x;
    float bias = (0.04 + worldTexel * 1.5) / max(CascadeParams[cascade].w, 1.0);
    // Most points have no colored caster above them: one gather of the nearby casters' depths decides.
    if (all(greaterThanEqual(sulkanShadowColorDepths(cascade, coord.xy), vec4(coord.z - bias)))) return vec4(1.0, 1.0, 1.0, 0.0);
    vec3 color = vec3(0.0);
    float covered = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 offset = (vec2(i & 1, i >> 1) - 0.5) * texel;
        vec4 caster = sulkanShadowColorTexel(cascade, coord.xy + offset);
        float behind = step(caster.a, coord.z - bias);
        color += caster.rgb * behind;
        covered += behind;
    }
    return vec4(covered > 0.0 ? color / covered : vec3(1.0), covered * 0.25);
}

// The light's color after it passed through translucent blocks on its way to this point: rgb is the casters'
// transmittance (1 where none), a the share of the point that is behind one (0 to 1). Combine it with the opaque
// shadow, e.g. light *= mix(vec3(1.0), result.rgb, result.a).
vec4 sulkanTranslucentShadow(vec3 position, vec3 normal) {
    float cameraDistance = length(position);
    int cascade = sulkanSelectCascade(cameraDistance);
    int last = sulkanLastCascade();
    if (cameraDistance >= CascadeParams[last].z) return vec4(1.0, 1.0, 1.0, 0.0);
    vec4 result = sulkanTranslucentShadowSample(cascade, position, normal);
    while (result.a < 0.0 && cascade < last) {
        cascade++;
        result = sulkanTranslucentShadowSample(cascade, position, normal);
    }
    if (result.a < 0.0) return vec4(1.0, 1.0, 1.0, 0.0);
    if (cascade == last) {
        float fadeStart = mix(CascadeParams[last].y, CascadeParams[last].z, 0.88);
        result.a *= 1.0 - smoothstep(fadeStart, CascadeParams[last].z, cameraDistance);
    }
    return result;
}

// The same for a point in the air (light shafts): no normal, one tap.
vec4 sulkanVolumeTranslucentShadow(vec3 position) {
    float cameraDistance = length(position);
    int cascade = sulkanSelectCascade(cameraDistance);
    int last = sulkanLastCascade();
    if (cameraDistance >= CascadeParams[last].z) return vec4(1.0, 1.0, 1.0, 0.0);
    vec3 coord = sulkanShadowCoord(cascade, position);
    if (any(lessThan(coord, vec3(0.0))) || any(greaterThan(coord, vec3(1.0)))) return vec4(1.0, 1.0, 1.0, 0.0);
    vec4 caster = sulkanShadowColorTexel(cascade, coord.xy);
    float behind = step(caster.a, coord.z - 0.04 / max(CascadeParams[cascade].w, 1.0));
    return vec4(behind > 0.0 ? caster.rgb : vec3(1.0), behind);
}
#endif

#else
float sulkanTerrainShadow(vec3 position, vec3 normal) { return 1.0; }
float sulkanDirectShadow(vec3 position, vec3 normal) { return 1.0; }
float sulkanGrassShadow(vec3 position, vec3 normal) { return 1.0; }
float sulkanVolumeShadow(vec3 position) { return 1.0; }
float sulkanTerrainShadows(vec3 position, vec3 normal, bool grass, out float direct) { direct=1.0; return 1.0; }
vec4 sulkanTranslucentShadow(vec3 position, vec3 normal) { return vec4(1.0, 1.0, 1.0, 0.0); }
vec4 sulkanVolumeTranslucentShadow(vec3 position) { return vec4(1.0, 1.0, 1.0, 0.0); }
#endif
#endif
