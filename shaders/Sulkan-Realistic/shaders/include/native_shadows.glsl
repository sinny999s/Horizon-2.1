// Native shadow ABI v1. Position and normal are camera-relative world axes.
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
// Legacy slots: map 0 aliases the composited cascade 0, map 1 is unused.
uniform sampler2D SulkanEntityShadowMap0;
uniform sampler2D SulkanEntityShadowMap1;
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
// Direct light must reach zero behind an occluder; ambient belongs elsewhere.
float sulkanDirectShadow(vec3 position, vec3 normal) {
    return sulkanCastShadowVisibility(position,normal);
}
float sulkanGrassShadow(vec3 position, vec3 normal) {
    // Apply the grass allowance independently in each cascade. Keep the original
    // receiver position for cascade selection and entity shadows.
    float visibility=sulkanCastShadowVisibility(position,normal,true);
    // Terrain lighting already applies N.L. Do not shade the same face a second
    // time as its wind-deformed normal crosses the light's grazing threshold.
    return mix(1.0,mix(ShadowParams.z,1.0,visibility),ShadowParams.x);
}
// Both terrain lighting terms from one cast lookup. Returns sulkanTerrainShadow
// (or sulkanGrassShadow for grass) and writes the sulkanDirectShadow term to
// direct, so receivers never run the cascade filter twice per fragment.
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
// Air samples have no receiver surface or normal bias. One bilinear comparison
// is sufficient inside the low-resolution volume integration; cascade 0 already
// contains entity casters.
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

#else
float sulkanTerrainShadow(vec3 position, vec3 normal) { return 1.0; }
float sulkanDirectShadow(vec3 position, vec3 normal) { return 1.0; }
float sulkanGrassShadow(vec3 position, vec3 normal) { return 1.0; }
float sulkanVolumeShadow(vec3 position) { return 1.0; }
float sulkanTerrainShadows(vec3 position, vec3 normal, bool grass, out float direct) { direct=1.0; return 1.0; }
#endif
#endif
