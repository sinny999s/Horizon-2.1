uniform sampler2D WaterReflection;
// Full-resolution pixel to its texel in the packed checkerboard target.
ivec2 waterReflectionTexel(ivec2 pixel) {
#if WATER_REFLECTION_QUALITY < 4
    return ivec2(pixel.x>>1,pixel.y);
#else
    return pixel;
#endif
}
// Rebuild an untraced checkerboard pixel from its four traced neighbours,
// weighted so reflections never bleed across depth or normal discontinuities.
vec4 reconstructWaterReflection(ivec2 pixel,vec3 normal,float distance) {
    ivec2 size=ivec2(ViewSizeAndInverse.xy);
    vec4 sum=vec4(0);
    for(int i=0;i<4;i++) {
        ivec2 p=pixel+(i==0?ivec2(1,0):(i==1?ivec2(-1,0):(i==2?ivec2(0,1):ivec2(0,-1))));
        if(any(lessThan(p,ivec2(0))) || any(greaterThanEqual(p,size))) continue;
        vec4 neighbour=texelFetch(WaterReflection,waterReflectionTexel(p),0);
        if(neighbour.a<=0.0) continue;
        uvec2 data=texelFetch(Surface,p,0).rg;
        vec2 uv=(vec2(p)+0.5)/vec2(size);
        float separation=abs(length(viewPosition(uv,uintBitsToFloat(data.y)))-distance)/max(distance*0.02,0.3);
        float weight=pow(max(dot(surfaceNormal(data.x),normal),0.0),8.0)/(1.0+separation*separation);
        sum+=vec4(neighbour.rgb,1.0)*weight;
    }
    return sum.a>1e-4?vec4(sum.rgb/sum.a,1.0):vec4(0.0);
}
vec3 resolveWaterReflection(vec2 uv,uvec2 data,vec3 position,vec3 local) {
    ivec2 pixel=ivec2(uv*ViewSizeAndInverse.xy);
    vec3 normal=surfaceNormal(data.x);
#if WATER_REFLECTION_QUALITY < 4
    vec4 reflected=((pixel.x+pixel.y)&1)==0
        ?texelFetch(WaterReflection,waterReflectionTexel(pixel),0)
        :reconstructWaterReflection(pixel,normal,length(position));
#else
    vec4 reflected=texelFetch(WaterReflection,pixel,0);
#endif
    if(reflected.a<=0.0) return local;
    vec3 toEye=normalize(sulkanCameraOffset()-position);
    float fresnel=surfaceFresnel(data,normal,toEye);
    return mix(local,reflected.rgb,fresnel*WATER_REFLECTION_STRENGTH*surfaceVisibility(data));
}
