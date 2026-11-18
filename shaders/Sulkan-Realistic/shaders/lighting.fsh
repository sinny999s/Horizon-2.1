#version 450
#include "shaders/common.glsl"
#include "shaders/palette.glsl"
#include "shaders/environment.glsl"
#include "shaders/cloud-field.glsl"
#include "sulkan/shadows.glsl"
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 lighting;
uniform sampler2D Depth;
#include "shaders/surface-normal.glsl"
void main() {
    ivec2 depthSize=textureSize(Depth,0);
    ivec2 center=clamp(ivec2(texCoord*vec2(depthSize)),ivec2(0),depthSize-1);
    vec2 centerUv=(vec2(center)+0.5)/vec2(depthSize);
    float depth=texelFetch(Depth,center,0).r;
    vec3 p=viewPosition(centerUv,max(depth,1e-6));

    float ao=0.0;
#if _SULKAN_ENABLED_AMBIENT_OCCLUSION
    if(depth>1e-7) {
        vec3 n=depthSurfaceNormal(Depth,centerUv);
        float radius=clamp(1.4/max(length(p),0.1)*ViewSizeAndInverse.y*0.6,2.0,28.0);
        for(int i=0;i<8;i++) {
            float angle=float(i)*0.785398163;
            vec2 offsetUv=centerUv+vec2(cos(angle),sin(angle))*radius*ViewSizeAndInverse.zw;
            // Never clamp an off-screen occluder onto the edge of the image.
            if(any(lessThan(offsetUv,vec2(0))) || any(greaterThanEqual(offsetUv,vec2(1)))) continue;
            ivec2 pixel=ivec2(offsetUv*vec2(depthSize));
            vec2 uv=(vec2(pixel)+0.5)/vec2(depthSize);
            float sampleDepth=texelFetch(Depth,pixel,0).r;
            if(sampleDepth<=1e-7) continue;
            vec3 delta=viewPosition(uv,max(sampleDepth,1e-6))-p;
            float distance=length(delta);
            ao+=max(dot(n,delta/max(distance,0.001))-0.15,0.0)*(1.0-smoothstep(0.2,2.8,distance))*step(1e-7,sampleDepth);
        }
    }
#endif
    float shafts=0.0;
#if _SULKAN_ENABLED_SUN_SHAFTS
    if(abs(WorldTimeWeatherDimension.w)<0.5 && CameraPositionHighAndFogType.w<0.5 && SunDirectionAndRainBrightness.y>0.02 && CloudOffsetAndGameTime.z>0.05) {
        vec3 ray=sulkanRay(texCoord);
        vec3 sun=normalize(SunDirectionAndRainBrightness.xyz);
        float reach=depth>1e-7?min(length(p),96.0):96.0;
        float stepLength=reach/12.0;
        vec3 origin=sulkanCloudOrigin();
        // Integrate illuminated air along the world ray. No radial screen blur:
        // cloud occlusion remains attached to the cloud field even outside the view.
        for(int i=0;i<12;i++) {
            float distance=(float(i)+0.5)*stepLength;
            vec3 relative=sulkanCameraOffset()+ray*distance;
            vec3 world=origin+relative;
            float visibility=sulkanVolumeShadow(relative);
#if CLOUD_QUALITY > 0
            float entry=max((cloudBottom()-world.y)/sun.y,0.0);
            float exitDistance=max((cloudBottom()+4.0-world.y)/sun.y,0.0);
            float span=max(exitDistance-entry,0.0);
            float cloudDepth=0.0;
            for(int c=0;c<4;c++) cloudDepth+=cloudDensity(world+sun*(entry+(float(c)+0.5)*span/4.0))*span/4.0;
            visibility*=exp(-cloudDepth);
#endif
            float airDensity=exp(clamp((72.0-world.y)*0.018,-8.0,3.0));
            shafts+=visibility*airDensity*exp(-distance*0.012)*stepLength*0.006;
        }
        float forward=0.08+0.92*pow(max(dot(ray,sun),0.0),8.0);
        shafts*=smoothstep(0.05,0.65,CloudOffsetAndGameTime.z)*forward*(1.0-WorldTimeWeatherDimension.y)*smoothstep(0.02,0.15,sun.y);
    }
#endif
    lighting=vec4(clamp(1.0-ao*0.20*AMBIENT_OCCLUSION,0.45,1.0),shafts,0.0,1.0);
}
