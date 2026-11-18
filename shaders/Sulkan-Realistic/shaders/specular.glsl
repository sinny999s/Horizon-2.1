// Normalized dielectric highlight: rough solids, tighter water glints.
float sulkanSunSpecular(vec3 normal, vec3 toEye, float roughness, float reflectance) {
    vec3 light=normalize(SunDirectionAndRainBrightness.xyz);
    float nl=max(dot(normal,light),0.0), nv=max(dot(normal,toEye),0.0);
    if(nl<=0.0 || nv<=0.0) return 0.0;
    vec3 halfway=light+toEye;
    halfway*=inversesqrt(max(dot(halfway,halfway),1e-8));
    float nh=max(dot(normal,halfway),0.0), vh=max(dot(toEye,halfway),0.0);
    float a=max(roughness*roughness,0.025), a2=a*a;
    float denominator=nh*nh*(a2-1.0)+1.0;
    float distribution=a2/(3.14159265*denominator*denominator);
    float k=(roughness+1.0)*(roughness+1.0)*0.125;
    float geometry=(nv/(nv*(1.0-k)+k))*(nl/(nl*(1.0-k)+k));
    float fresnel=reflectance+(1.0-reflectance)*pow(1.0-vh,5.0);
    return min(distribution*geometry*fresnel/(4.0*max(nv,0.001)),4.0)
        *smoothstep(0.0,0.12,light.y)*(1.0-WorldTimeWeatherDimension.y);
}
