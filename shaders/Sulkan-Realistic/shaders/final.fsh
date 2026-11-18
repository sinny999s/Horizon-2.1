#version 450
#include "shaders/common.glsl"
#include "shaders/tonemap.glsl"
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 color;
uniform sampler2D Source;
#if AUTO_EXPOSURE == 1
uniform sampler2D Exposure;
#endif
#if _SULKAN_ENABLED_BLOOM_STRENGTH
uniform sampler2D Bloom;
uniform sampler2D Wide;
#endif
// Edge-directed spatial AA: five contrast probes, extra taps only on edges.
// Work before bloom/tonemapping so their soft gradients need no repeated evaluation.
float aaLuma(vec3 c) { float l=dot(max(c,vec3(0)),vec3(0.2126,0.7152,0.0722)); return l/(1.0+l); }
vec3 antialiasSource(vec2 uv) {
    vec3 center=texture(Source,uv).rgb;
#if ANTIALIASING == 1
    vec2 pixel=1.0/vec2(textureSize(Source,0));
    float nw=aaLuma(texture(Source,uv+vec2(-1,-1)*pixel).rgb);
    float ne=aaLuma(texture(Source,uv+vec2(1,-1)*pixel).rgb);
    float sw=aaLuma(texture(Source,uv+vec2(-1,1)*pixel).rgb);
    float se=aaLuma(texture(Source,uv+vec2(1,1)*pixel).rgb);
    float mid=aaLuma(center), low=min(mid,min(min(nw,ne),min(sw,se)));
    float high=max(mid,max(max(nw,ne),max(sw,se)));
    if(high-low<max(0.025,high*0.12)) return center;
    vec2 tangent=vec2(sw+se-nw-ne,nw+sw-ne-se);
    float damping=max((nw+ne+sw+se)*0.03125,0.0078125);
    tangent=clamp(tangent/(min(abs(tangent.x),abs(tangent.y))+damping),vec2(-4),vec2(4))*pixel;
    vec3 narrow=(texture(Source,uv-tangent/6.0).rgb+texture(Source,uv+tangent/6.0).rgb)*0.5;
    vec3 wide=narrow*0.5+(texture(Source,uv-tangent*0.5).rgb+texture(Source,uv+tangent*0.5).rgb)*0.25;
    float l=aaLuma(wide);
    return l<low || l>high?narrow:wide;
#else
    return center;
#endif
}
void main() {
    vec3 c = antialiasSource(texCoord);
#if _SULKAN_ENABLED_BLOOM_STRENGTH
    c += BLOOM_STRENGTH * (texture(Bloom,texCoord).rgb * 0.65 + texture(Wide,texCoord).rgb * 0.35);
#endif
#if AUTO_EXPOSURE == 1
    c*=texelFetch(Exposure,ivec2(0),0).r;
#endif
    DoCompTonemap(c);
    // Keep the saved COLOR_GRADE key: 0=Off, .25=Natural, .5=Realistic, 1=Vibrant.
    // Off retains required tonemapping but adds no artistic color grading.
    float luminance=dot(c,vec3(0.2126,0.7152,0.0722));
    float day=smoothstep(-0.14,0.12,SunDirectionAndRainBrightness.y);
    float overworld=1.0-step(0.5,abs(WorldTimeWeatherDimension.w));
    if(COLOR_GRADE>0.0 && COLOR_GRADE<0.5) {
        // Neutral palette with restrained saturation; no warm/cool split.
        c=mix(vec3(luminance),c,0.96);
    } else if(COLOR_GRADE>=0.5) {
        DoBSLColorSaturation(c);
        if(COLOR_GRADE>0.5) {
            float luma=dot(c,vec3(0.2126,0.7152,0.0722));
            float chroma=max(c.r,max(c.g,c.b))-min(c.r,min(c.g,c.b));
            c=mix(vec3(luma),c,1.0+0.20*(1.0-clamp(chroma,0.0,1.0)));
            c=c+0.12*c*(1.0-c)*(2.0*c-1.0);
        }
    }
    luminance=clamp(dot(c,vec3(0.2126,0.7152,0.0722)),0.0,1.0);
    float grade=COLOR_GRADE>=0.5?(COLOR_GRADE>0.5?0.65:0.5)*overworld:0.0;
    vec3 tone=mix(vec3(-0.025,0.005,0.035),vec3(0.04,0.012,-0.025)*day,
        smoothstep(0.15,0.8,luminance));
    c+=tone*(4.0*luminance*(1.0-luminance))*grade;
    color = vec4(clamp(c,0.0,1.0),1.0);
}
