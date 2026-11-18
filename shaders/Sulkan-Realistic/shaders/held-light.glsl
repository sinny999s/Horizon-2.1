vec3 sulkanHeldLight(vec3 position,vec3 normal) {
#if _SULKAN_ENABLED_HELD_LIGHTING
    float radius=HeldLightPositionRadius.w;
    vec3 delta=HeldLightPositionRadius.xyz-position;
    float distanceSquared=dot(delta,delta);
    if(radius<=0.0 || distanceSquared>=radius*radius) return vec3(0);
    float facing=max(dot(normal,delta*inversesqrt(max(distanceSquared,1e-6))),0.0);
    if(facing<=0.0) return vec3(0);
    float edge=1.0-distanceSquared/(radius*radius);
    float attenuation=edge*edge/(1.0+distanceSquared*0.055);
    return HeldLightColor.rgb*(HELD_LIGHTING*attenuation*facing);
#else
    return vec3(0);
#endif
}
