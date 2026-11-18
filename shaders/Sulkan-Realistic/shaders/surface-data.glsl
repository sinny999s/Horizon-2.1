// Two uints: octahedral normal, skylight, fade, material class; exact device depth.
vec2 surfaceSign(vec2 v) { return mix(vec2(-1),vec2(1),greaterThanEqual(v,vec2(0))); }
uint encodeSurface(vec3 normal,float skylight,float visibility,bool water) {
    normal/=max(abs(normal.x)+abs(normal.y)+abs(normal.z),1e-6);
    vec2 oct=normal.xy;
    if(normal.z<0.0) oct=(1.0-abs(oct.yx))*surfaceSign(oct);
    uvec2 packed=uvec2(round(clamp(oct*0.5+0.5,0.0,1.0)*1023.0));
    return packed.x|(packed.y<<10u)|(uint(round(clamp(skylight,0.0,1.0)*15.0))<<20u)
        |(uint(round(clamp(visibility,0.0,1.0)*31.0))<<24u)|(water?0x80000000u:0x40000000u);
}
vec3 surfaceNormal(uint packed) {
    vec2 oct=vec2(packed&1023u,(packed>>10u)&1023u)/1023.0*2.0-1.0;
    vec3 n=vec3(oct,1.0-abs(oct.x)-abs(oct.y));
    if(n.z<0.0) n.xy=(1.0-abs(n.yx))*surfaceSign(n.xy);
    return normalize(n);
}
bool surfaceMatches(uvec2 data,float depth) {
    // D32 is exact; two ULPs tolerate arithmetic roundoff without a world-space threshold.
    uint bits=floatBitsToUint(depth);
    return (data.x&0xc0000000u)!=0u && depth>1e-7 && (max(bits,data.y)-min(bits,data.y))<=2u;
}
bool surfaceWater(uvec2 data) { return (data.x&0xc0000000u)==0x80000000u; }
// Bit 29 is independent of the opaque/water/translucent class in bits 30–31.
bool surfaceIce(uvec2 data) { return (data.x&0x20000000u)!=0u; }
bool surfaceReflective(uvec2 data) { return surfaceWater(data) || surfaceIce(data); }
bool surfaceTransparent(uvec2 data) { return (data.x&0x80000000u)!=0u; }
float surfaceSky(uvec2 data) { return float((data.x>>20u)&15u)/15.0; }
float surfaceVisibility(uvec2 data) { return float((data.x>>24u)&31u)/31.0; }
float surfaceFresnel(uvec2 data,vec3 normal,vec3 toEye) {
    float f0=surfaceIce(data)?0.025:0.02;
    return f0+(1.0-f0)*pow(1.0-abs(dot(normal,toEye)),5.0);
}
