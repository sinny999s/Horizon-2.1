// Periodic value noise and its analytic gradient keep frost anchored to the world.
float sulkanIceHash(vec2 cell, float period) {
    vec3 p=fract(vec3(mod(cell,vec2(period)).xy,mod(cell.x+cell.y,period))*0.1031);
    p+=dot(p,p.yzx+33.33);
    return fract((p.x+p.y)*p.z);
}
vec3 sulkanIceNoise(vec2 q, float scale) {
    vec2 p=q*scale, cell=floor(p), f=fract(p);
    vec2 u=f*f*(3.0-2.0*f), du=6.0*f*(1.0-f);
    float period=4096.0*scale;
    float a=sulkanIceHash(cell,period), b=sulkanIceHash(cell+vec2(1,0),period);
    float c=sulkanIceHash(cell+vec2(0,1),period), d=sulkanIceHash(cell+vec2(1),period);
    return vec3(mix(mix(a,b,u.x),mix(c,d,u.x),u.y),
        du.x*mix(b-a,d-c,u.y),du.y*mix(c-a,d-b,u.x));
}
vec3 sulkanIceDetail(vec3 position, vec3 normal, out float roughness) {
    vec3 p=position+mod(CameraPositionHighAndFogType.xyz,vec3(4096.0))+CameraPositionLowAndFarPlane.xyz;
    vec3 axis=abs(normal);
    vec2 q=axis.y>max(axis.x,axis.z)?p.xz:(axis.x>axis.z?p.zy:p.xy);
    float footprint=max(length(dFdx(q)),length(dFdy(q)));
    vec3 patches=sulkanIceNoise(q,2.0);
    vec3 grain=sulkanIceNoise(q,9.0);
    vec3 fine=sulkanIceNoise(q,32.0);
    float grainFade=1.0-smoothstep(0.035,0.14,footprint);
    float fineFade=1.0-smoothstep(0.01,0.045,footprint);
    float cloud=mix(0.5,patches.x,1.0-smoothstep(0.15,0.6,footprint));
    roughness=clamp(0.2+cloud*0.65+(grain.x-0.5)*0.3*grainFade,0.15,0.95);
    // Irregular frost patches and finer pitting, without cracks or liquid motion.
    vec2 slope=patches.yz*0.024*(1.0-smoothstep(0.15,0.6,footprint))
        +grain.yz*0.055*grainFade+fine.yz*0.028*fineFade;
    vec3 bump=axis.y>max(axis.x,axis.z)?vec3(slope.x,0,slope.y)
        :(axis.x>axis.z?vec3(0,slope.y,slope.x):vec3(slope,0));
    return normalize(normal+bump);
}
