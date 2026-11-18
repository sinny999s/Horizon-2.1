// Broad, stationary patches anchored to the world, not to the camera.
float mistCell(vec2 cell) {
    cell=mod(cell,128.0);
    vec3 p=fract(vec3(cell.xyx)*0.1031);
    p+=dot(p,p.yzx+33.33);
    return fract((p.x+p.y)*p.z);
}
float mistPatch(vec2 position) {
    vec2 p=position/64.0, cell=floor(p), f=fract(p);
    f=f*f*(3.0-2.0*f);
    return mix(mix(mistCell(cell),mistCell(cell+vec2(1,0)),f.x),
        mix(mistCell(cell+vec2(0,1)),mistCell(cell+vec2(1)),f.x),f.y);
}
float sulkanGroundMist(vec3 ray,float distance) {
    if(ATMOSPHERE<=0.0 || distance<=32.0) return 0.0;
    float rain=WorldTimeWeatherDimension.y;
    float dawn=1.0-smoothstep(0.02,0.35,abs(SunDirectionAndRainBrightness.y));
    vec3 origin=vec3(mod(CameraPositionHighAndFogType.x,8192.0),CameraPositionHighAndFogType.y,
        mod(CameraPositionHighAndFogType.z,8192.0))+CameraPositionLowAndFarPlane.xyz;
    float reach=min(distance,384.0), stepLength=(reach-32.0)/6.0;
    float opticalDepth=0.0;
    for(int i=0;i<6;i++) {
        float along=32.0+(float(i)+0.5)*stepLength;
        vec3 p=origin+ray*along;
        float patchDensity=smoothstep(0.45,0.8,mistPatch(p.xz));
        // Low-elevation mist; no terrain-height texture is required.
        float height=exp(-max(p.y-64.0,0.0)/18.0);
        float nearFade=smoothstep(32.0,96.0,along);
        opticalDepth+=patchDensity*height*nearFade*stepLength*(0.0007+dawn*0.0014+rain*0.002);
    }
    // Very light distant aerial perspective, concentrated near the horizon.
    float horizon=1.0-smoothstep(0.05,0.38,abs(ray.y));
    opticalDepth+=max(distance-192.0,0.0)*horizon*(0.00010+rain*0.0003);
    float fog=1.0-exp(-opticalDepth*ATMOSPHERE);
    return min(fog,mix(0.30,0.55,rain));
}
