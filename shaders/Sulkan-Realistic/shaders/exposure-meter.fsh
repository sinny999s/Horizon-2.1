#version 450
#include "shaders/common.glsl"
uniform sampler2D Source;
uniform sampler2D Depth;
uniform sampler2D HandDepth;
layout(location=0) out vec2 meter;
void main() {
    vec2 cell=floor(gl_FragCoord.xy);
    meter=vec2(0);
    // Fixed stratified samples; no temporal jitter or full-resolution reduction.
    for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
        vec2 uv=(cell+(vec2(x,y)+0.5)*0.5)/vec2(32,18);
        if(texture(HandDepth,uv).r>1e-7) continue;
        vec3 scene=texture(Source,uv).rgb;
        if(any(isnan(scene)) || any(isinf(scene))) continue;
        float luminance=clamp(dot(max(scene,vec3(0)),vec3(0.2126,0.7152,0.0722)),0.0001,16.0);
        vec2 center=(uv-0.5)*2.0;
        float weight=0.25+0.75*exp(-2.0*dot(center,center));
        if(texture(Depth,uv).r<=1e-7) weight*=0.25;
        meter+=vec2(log2(luminance)*weight,weight);
    }
}
