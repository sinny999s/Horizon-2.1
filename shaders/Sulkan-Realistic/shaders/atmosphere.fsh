#version 450
#include "shaders/common.glsl"
#include "shaders/palette.glsl"
#include "shaders/environment.glsl"
#include "shaders/ground-mist.glsl"
#include "shaders/surface-normal.glsl"
#include "shaders/surface-data.glsl"
#include "shaders/specular.glsl"
#include "shaders/held-light.glsl"
#include "sulkan/shadows.glsl"
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 hdr;
uniform sampler2D Scene;
uniform usampler2D Surface;
uniform sampler2D Weather;
uniform sampler2D Depth;
uniform sampler2D HandDepth;
#if CLOUD_QUALITY > 0
uniform sampler2D Clouds;
uniform sampler2D CloudDistance;
#endif
#if _SULKAN_ENABLED_AMBIENT_OCCLUSION || _SULKAN_ENABLED_SUN_SHAFTS
uniform sampler2D Lighting;
#endif
#if WATER_ENABLED == 1 && WATER_REFLECTION_QUALITY > 0 && _SULKAN_ENABLED_WATER_REFLECTION_STRENGTH
#include "shaders/water-resolve.glsl"
#endif
void main() {
    vec3 c=toLinear(texture(Scene,texCoord).rgb);
    float depth=texture(Depth,texCoord).r;
    float handDepth=texture(HandDepth,texCoord).r;
    if(handDepth>1e-7) {
        // Hand depth has its own FOV; bob and swing are already in the geometry.
        if(HandProjectionValid.x>0.5 && abs(WorldTimeWeatherDimension.w)<0.5 && CameraPositionHighAndFogType.w<0.5) {
            vec3 position=mat3(InverseView)*viewPosition(texCoord,handDepth,InverseHandProjection);
            vec3 normal=mat3(InverseView)*depthSurfaceNormal(HandDepth,texCoord,InverseHandProjection);
            if(dot(normal,normal)>0.5) {
                c+=sqrt(max(c,vec3(0)))*sulkanHeldLight(position,normal)*0.5;
                float specular=sulkanSunSpecular(normal,normalize(-position),0.55,0.035);
                if(specular>0.0) c+=portLightPalette()*specular*sulkanDirectShadow(position,normal)*smoothstep(0.05,0.65,CloudOffsetAndGameTime.z);
            }
        }
        hdr=vec4(c,1.0); return;
    }
    uvec2 surface=texture(Surface,texCoord).rg;
    bool nativeSurface=surfaceMatches(surface,depth);
    bool sky=depth<=1e-7;
    vec3 ray=sulkanRay(texCoord);
    vec3 viewSurface=sky?vec3(0):viewPosition(texCoord,depth);
    float distanceToScene=sky?60000.0:min(length(viewSurface),60000.0);
    bool overworld=abs(WorldTimeWeatherDimension.w)<0.5;
    bool air=CameraPositionHighAndFogType.w<0.5;
    // Depth-aware upsampling prevents half-resolution lighting/clouds leaking over silhouettes.
    vec2 size=ceil(ViewSizeAndInverse.xy*0.5), q=texCoord*size-0.5, f=fract(q);
    ivec2 origin=ivec2(floor(q));
    vec2 light=vec2(0.0); vec4 cloud=vec4(0.0); float total=0.0;
#if _SULKAN_ENABLED_AMBIENT_OCCLUSION || _SULKAN_ENABLED_SUN_SHAFTS || CLOUD_QUALITY > 0
    for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
        ivec2 pixel=clamp(origin+ivec2(x,y),ivec2(0),ivec2(size)-1);
        vec2 uv=(vec2(pixel)+0.5)/size;
        float d=texture(Depth,uv).r;
        float dist=d<=1e-7?60000.0:length(viewPosition(uv,d));
        float weight=(x==0?1.0-f.x:f.x)*(y==0?1.0-f.y:f.y);
        float separation=abs(dist-distanceToScene)/max(distanceToScene*0.02,0.3);
        weight*=1.0/(1.0+separation*separation);
#if _SULKAN_ENABLED_AMBIENT_OCCLUSION || _SULKAN_ENABLED_SUN_SHAFTS
        light+=texelFetch(Lighting,pixel,0).rg*weight;
#else
        light+=vec2(1,0)*weight;
#endif
#if CLOUD_QUALITY > 0
        vec4 sampleCloud=texelFetch(Clouds,pixel,0);
        if(texture(CloudDistance,uv).r>distanceToScene) sampleCloud=vec4(0.0);
        cloud+=sampleCloud*weight;
#endif
        total+=weight;
    }
#endif
    if(total>1e-5) { light/=total; cloud/=total; } else { light=vec2(1.0,0.0); cloud=vec4(0.0); }
#if WATER_ENABLED == 1 && WATER_REFLECTION_QUALITY > 0 && _SULKAN_ENABLED_WATER_REFLECTION_STRENGTH
    if(nativeSurface && surfaceReflective(surface) && air) {
        vec3 position=mat3(InverseView)*viewSurface;
        c=toLinear(resolveWaterReflection(texCoord,surface,position,pow(max(c,vec3(0)),vec3(1.0/2.2))));
    }
#endif
    if(!sky && !(nativeSurface && surfaceTransparent(surface))) c*=light.x;
    // Native terrain is already lit. Depth matching rejects metadata behind entities.
    if(!sky && !nativeSurface) {
        // Entities and other non-terrain surfaces: reconstruct the depth normal once
        // for both the sun highlight and the held light.
        vec3 position=mat3(InverseView)*viewSurface;
        vec3 normal=normalize(mat3(InverseView)*depthSurfaceNormal(Depth,texCoord)+vec3(0,1e-8,0));
        if(overworld && air) {
            vec3 toEye=normalize(sulkanCameraOffset()-position);
            float specular=sulkanSunSpecular(normal,toEye,0.55,0.035);
            if(specular>0.0) c+=portLightPalette()*specular*sulkanDirectShadow(position,normal)*smoothstep(0.05,0.65,CloudOffsetAndGameTime.z);
        }
        c+=sqrt(max(c,vec3(0)))*sulkanHeldLight(position,normal)*0.5;
    }
    if(overworld && air) {
        vec3 skyColor=sulkanSky(ray);
        if(sky) c=skyColor+sulkanCelestials(ray);
        else {
            float distance=min(distanceToScene,1500.0);
            float skyAccess=smoothstep(0.05,0.65,CloudOffsetAndGameTime.z);
            if(nativeSurface) skyAccess=min(skyAccess,smoothstep(0.01,0.5,surfaceSky(surface)));
            float fog=sulkanGroundMist(ray,distance)*skyAccess;
            // Color the existing aerial perspective rather than adding another fog layer.
            float towardSun=max(dot(ray,SunDirectionAndRainBrightness.xyz),0.0);
            float horizon=1.0-smoothstep(0.0,0.5,abs(ray.y));
            vec3 distanceTint=mix(vec3(0.88,0.97,1.10),vec3(1.12,1.01,0.88),
                towardSun*towardSun*horizon);
            float distanceGrade=DISTANCE_COLOR*sulkanDay()*(1.0-WorldTimeWeatherDimension.y);
            // Broad sky color without the sun-facing glare hotspot avoids a white veil.
            vec3 mistColor=sulkanSky(normalize(vec3(ray.x,0.35,ray.z)))*0.65;
            c=mix(c,mistColor*mix(vec3(1.0),distanceTint,distanceGrade*0.5),fog);
        }
        c=c*(1.0-cloud.a)+cloud.rgb;
        c+=portLightPalette()*light.y*SUN_SHAFTS*0.8;
    }
    // Weather is depth-tested against the world in its own transparent layer.
    // Unpremultiply before converting its display-space color to linear light.
    vec4 weather=texture(Weather,texCoord);
    float weatherAlpha=clamp(weather.a,0.0,1.0);
    vec3 weatherColor=toLinear(weather.rgb/max(weatherAlpha,1e-5));
    c=c*(1.0-weatherAlpha)+weatherColor*weatherAlpha;
    hdr=vec4(max(c,vec3(0.0)),1.0);
}
