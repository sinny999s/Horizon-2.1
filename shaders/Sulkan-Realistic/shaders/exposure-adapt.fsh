#version 450
#include "shaders/common.glsl"
uniform sampler2D Meter;
uniform sampler2D Previous;
layout(location=0) out float exposure;
void main() {
    vec2 total=vec2(0);
    for(int y=0;y<18;y++) for(int x=0;x<32;x++) total+=texelFetch(Meter,ivec2(x,y),0).rg;
    float previous=texelFetch(Previous,ivec2(0),0).r;
    bool valid=previous>=0.5 && previous<=8.0 && !isnan(previous) && !isinf(previous);
    if(total.y<=0.0001) { exposure=valid?previous:1.0; return; }
    // Let dark adaptation accumulate up to three stops. Carry that exposure
    // into sunlight so highlights wash out before the eye adjusts downward.
    float daylight=smoothstep(-0.14,0.12,SunDirectionAndRainBrightness.y);
    // Preserve the night palette, but let measured scene brightness drive cave
    // adaptation without an additional camera-skylight darkening multiplier.
    float middleGray=0.18;
    if(abs(WorldTimeWeatherDimension.w)<0.5)
        middleGray*=mix(0.25,1.0,daylight);
    float targetEv=clamp((log2(middleGray)-total.x/total.y)*0.9,-1.0,3.0);
    // New history starts neutral rather than snapping to the metered target.
    if(!valid) previous=1.0;
    float previousEv=log2(previous);
    float difference=targetEv-previousEv;
    if(abs(difference)<0.025) { exposure=previous; return; }
    float seconds=clamp(TimeDeltaFrame.y,0.0,0.1);
    float timeConstant=difference<0.0?2.0:4.0;
    exposure=clamp(exp2(mix(previousEv,targetEv,1.0-exp(-seconds/timeConstant))),0.5,8.0);
}
