// Reflected view ray, coarse march/refinement,
// edge confidence, geometric rejection and shared cloud/sky background.
// Native implementation uses Vulkan [0,1] reversed depth, and current-frame opaque color.
#include "shaders/cloud-volume.glsl"
bool reflectionProject(vec3 position, out vec2 screen) {
    vec4 clip=Projection*vec4(position,1.0);
    if(clip.w<=0.001) return false;
    screen=clip.xy/clip.w*0.5+0.5;
    return all(greaterThan(screen,vec2(0.0))) && all(lessThan(screen,vec2(1.0))) && clip.z/clip.w>=0.0 && clip.z/clip.w<=1.0;
}
// Repair small screen-space holes only with nearby geometry that also lies near
// the reflected ray. This is a bounded spatial approximation, not hidden geometry.
vec4 reflectionRepair(vec2 screen, vec3 origin, vec3 direction, vec3 worldPosition, vec3 worldNormal, int surfaces) {
    ivec2 size=textureSize(OpaqueDepth,0);
    ivec2 center=ivec2(screen*vec2(size));
    float best=1e20;
    vec4 result=vec4(0.0);
    int radius=surfaces>=2?4:2;
    int support=0;
    for(int y=-radius;y<=radius;y++) for(int x=-radius;x<=radius;x++) {
        ivec2 pixel=center+ivec2(x,y);
        if(any(lessThan(pixel,ivec2(0))) || any(greaterThanEqual(pixel,size))) continue;
        float depth=texelFetch(OpaqueDepth,pixel,0).r;
        if(depth<=1e-7) continue;
        vec2 uv=(vec2(pixel)+0.5)/vec2(size);
        vec3 surface=viewPosition(uv,depth);
        float along=dot(surface-origin,direction);
        if(along<0.15 || along>192.0) continue;
        vec3 worldHit=mat3(InverseView)*surface;
        if(dot(worldHit-worldPosition,worldNormal)<=0.03 || length(worldHit-worldPosition)<=0.12) continue;
        // Reconstruct a pixel footprint rather than using a fixed world-space
        // thickness that would swallow entire nearby objects or distant sky gaps.
        float footprint=max(length(viewPosition(uv+vec2(1.0/float(size.x),0),depth)-surface),
                            length(viewPosition(uv+vec2(0,1.0/float(size.y)),depth)-surface));
        float error=length(surface-(origin+direction*along))/max(footprint,1e-5);
        if(error>float(radius)+0.5) continue;
        vec4 color=texelFetch(Opaque,pixel,0);
        if(color.a<=0.001) continue;
        support++;
        float score=error+0.15*length(vec2(x,y));
        if(score>=best) continue;
        float edge=min(min(uv.x,uv.y),min(1.0-uv.x,1.0-uv.y));
        float confidence=1.0-smoothstep(float(radius)-0.5,float(radius)+0.5,error);
        confidence*=smoothstep(0.0,max(ViewSizeAndInverse.z,ViewSizeAndInverse.w),edge)*(1.0-smoothstep(144.0,192.0,along));
        if(confidence<=0.0) continue;
        best=score;
        result=vec4(color.rgb,confidence);
    }
    // Multiple nearby geometry samples can close a foliage gap completely.
    // Sparse evidence keeps a conservative blend; zero-depth sky is never sampled.
    if(support<4) result.a*=0.8;
    return result;
}
vec4 sulkanSceneReflection(vec3 worldPosition, vec3 worldNormal, vec3 reflectedRay) {
    vec3 origin=mat3(View)*(worldPosition+worldNormal*0.06);
    vec3 direction=normalize(mat3(View)*reflectedRay);
    float previous=0.0, distance=0.15;
    vec2 repairScreen=vec2(0.0);
    float repairError=1e20;
    int surfaces=0;
    float lastSurface=-1.0;
    float previousDelta=-1e20;
    int steps=WATER_REFLECTION_QUALITY==1?48:(WATER_REFLECTION_QUALITY==2?72:(WATER_REFLECTION_QUALITY==3?96:128));
    float growth=WATER_REFLECTION_QUALITY==1?0.12:(WATER_REFLECTION_QUALITY==2?0.10:(WATER_REFLECTION_QUALITY==3?0.08:0.06));
    for(int i=0;i<128;i++) {
        if(i>=steps || distance>192.0) break;
        vec3 point=origin+direction*distance;
        vec2 screen;
        if(!reflectionProject(point,screen)) break;
        float depth=texture(OpaqueDepth,screen).r;
        float coarseDelta=-1e20;
        if(depth>1e-7) {
            vec3 surface=viewPosition(screen,depth);
            float delta=surface.z-point.z;
            coarseDelta=delta;
            // Refine only a new crossing, not every subsequent step behind it.
            if(delta>=0.0 && (previousDelta<0.0 || delta<previousDelta)) {
                float lo=previous, hi=distance;
                for(int refine=0;refine<6;refine++) {
                    float mid=(lo+hi)*0.5;
                    vec3 candidate=origin+direction*mid;
                    vec2 coord;
                    if(!reflectionProject(candidate,coord)) { hi=mid; continue; }
                    float d=texture(OpaqueDepth,coord).r;
                    float error=d>1e-7?viewPosition(coord,d).z-candidate.z:-1.0;
                    if(error>=0.0) hi=mid; else lo=mid;
                }
                point=origin+direction*hi;
                if(!reflectionProject(point,screen)) break;
                depth=texture(OpaqueDepth,screen).r;
                surface=viewPosition(screen,max(depth,1e-7));
                float thickness=0.10+length(surface)*0.003;
                vec3 worldHit=mat3(InverseView)*surface;
                float error=abs(surface.z-point.z)/max(length(surface),0.1);
                if(depth>1e-7 && error<repairError && dot(worldHit-worldPosition,worldNormal)>0.03) {
                    repairScreen=screen;
                    repairError=error;
                }
                if(depth>1e-7 && abs(surface.z-point.z)<thickness && dot(worldHit-worldPosition,worldNormal)>0.03 && length(worldHit-worldPosition)>0.12) {
                    // Match the nearest depth sample's color texel. Bilinear color
                    // filtering here blends foliage into the background through its
                    // alpha-tested holes, even when the depth hit is on that background.
                    ivec2 size=textureSize(Opaque,0);
                    ivec2 pixel=clamp(ivec2(screen*vec2(size)),ivec2(0),size-1);
                    vec4 sampleColor=texelFetch(Opaque,pixel,0);
                    if(sampleColor.a>0.001) {
                        float edge=min(min(screen.x,screen.y),min(1.0-screen.x,1.0-screen.y));
                        float confidence=smoothstep(0.0,max(ViewSizeAndInverse.z,ViewSizeAndInverse.w),edge)*(1.0-smoothstep(144.0,192.0,hi));
                        return vec4(sampleColor.rgb,confidence);
                    }
                }
                // A rejected crossing is not the end of the ray. Cutout silhouettes
                // jump between foreground and background depth, so refinement can
                // land on an edge that fails the thickness test. Keep marching from
                // the coarse distance to find a later, valid background intersection.
                if(depth>1e-7 && abs(length(surface)-lastSurface)>0.5) {
                    surfaces++;
                    lastSurface=length(surface);
                }
            }
        }
        previousDelta=coarseDelta;
        previous=distance;
        distance+=0.15+distance*growth;
    }
    // An exact background hit always wins. Search one small neighborhood only
    // after tracing has failed, never blur the complete reflection or the sky.
    return repairError<1e19?reflectionRepair(repairScreen,origin,direction,worldPosition,worldNormal,surfaces):vec4(0.0);
}
// Sky/background seen where tracing misses, or alone where tracing is skipped.
vec3 sulkanReflectionBackground(vec3 position, vec3 direction, float skyLight, vec3 localColor, bool clouds) {
    vec3 background=localColor*0.35;
    // Keep actual reflected scene/torch light; suppress only the sky fallback
    // under cover, where off-screen SSR misses must not turn into daylight.
    float skyAccess=min(smoothstep(0.02,0.65,skyLight),smoothstep(0.05,0.65,CloudOffsetAndGameTime.z));
    if(abs(WorldTimeWeatherDimension.w)<0.5 && skyAccess>0.001) {
        vec3 environment=sulkanSky(direction)+sulkanCelestials(direction);
        if(clouds) {
            // Reflected clouds sit behind ripples at a few percent fresnel; few steps suffice.
            float firstDistance;
            int steps=WATER_REFLECTION_QUALITY==1?6:(WATER_REFLECTION_QUALITY==2?10:16);
            vec4 cloud=sulkanCloudVolume(sulkanCloudOrigin()+position,direction,1600.0,steps,firstDistance);
            environment=environment*(1.0-cloud.a)+cloud.rgb;
        }
        background=mix(background,pow(max(environment,vec3(0)),vec3(1.0/2.2)),skyAccess);
    }
    return background;
}
vec3 sulkanWaterReflection(vec3 position, vec3 normal, vec3 direction, float skyLight, vec3 localColor) {
    vec4 scene=sulkanSceneReflection(position,normal,direction);
    if(scene.a>0.999) return scene.rgb;
    return mix(sulkanReflectionBackground(position,direction,skyLight,localColor,true),scene.rgb,scene.a);
}
