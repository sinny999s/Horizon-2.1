#version 450
#include "shaders/common.glsl"
#include "shaders/palette.glsl"
#include "shaders/environment.glsl"
#include "shaders/surface-data.glsl"
uniform sampler2D Opaque;
uniform sampler2D OpaqueDepth;
uniform sampler2D Depth;
uniform sampler2D HandDepth;
uniform sampler2D Scene;
uniform usampler2D Surface;
#include "shaders/reflections.glsl"
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 reflection;

void main() {
    reflection=vec4(0);
#if WATER_ENABLED == 1 && WATER_REFLECTION_QUALITY > 0 && _SULKAN_ENABLED_WATER_REFLECTION_STRENGTH
    if(CameraPositionHighAndFogType.w>0.5) return;
    ivec2 size=textureSize(Depth,0);
#if WATER_REFLECTION_QUALITY < 4
    // Packed checkerboard: the left half of the target holds every other pixel of
    // each row, so thread groups trace together and the right half exits at once.
    ivec2 packed=ivec2(gl_FragCoord.xy);
    if(packed.x>=(size.x+1)/2) return;
    ivec2 selected=ivec2(packed.x*2+(packed.y&1),packed.y);
    if(selected.x>=size.x) return;
#else
    ivec2 selected=ivec2(gl_FragCoord.xy);
#endif
    float nearest=texelFetch(Depth,selected,0).r;
    uvec2 data=texelFetch(Surface,selected,0).rg;
    if(!surfaceReflective(data) || !surfaceMatches(data,nearest)
        || texelFetch(HandDepth,selected,0).r>1e-7) return;
    vec2 uv=(vec2(selected)+0.5)/vec2(size);
    vec3 position=mat3(InverseView)*viewPosition(uv,nearest);
    vec3 normal=surfaceNormal(data.x);
    vec3 toEye=normalize(sulkanCameraOffset()-position);
    vec3 ray=reflect(-toEye,normal);
    vec3 local=texelFetch(Scene,selected,0).rgb;
    // Steep views blend in only a few percent of reflection: skip the trace there
    // and keep the sky/background term so the surface tone stays continuous.
    float weight=surfaceFresnel(data,normal,toEye)*WATER_REFLECTION_STRENGTH*surfaceVisibility(data);
    if(weight<0.035) {
        reflection=vec4(sulkanReflectionBackground(position,ray,surfaceSky(data),local,false),1);
        return;
    }
    reflection=vec4(sulkanWaterReflection(position,normal,ray,surfaceSky(data),local),1);
#endif
}
