// Sulkan Realistic lighting and water palette.
vec3 portLightPalette() {
    float noon = clamp(SunDirectionAndRainBrightness.y, 0.0, 1.0);
    float daylight = smoothstep(-0.12, 0.12, SunDirectionAndRainBrightness.y);
    vec3 noonLight = vec3(0.65, 0.55, 0.375) * 2.05;
    vec3 sunsetLight = pow(vec3(0.64, 0.45, 0.3), vec3(2.5 - noon)) * 5.0;
    vec3 nightLight = 0.9 * vec3(0.15, 0.14, 0.20) * 0.3;
    vec3 clearLight = mix(nightLight, mix(sunsetLight, noonLight, noon), daylight);
    vec3 wetLight = mix(vec3(0.012, 0.014, 0.02), vec3(0.21, 0.16, 0.13) * 0.85 + noon * vec3(0.0, 0.02, 0.06), daylight) * 2.5;
    return mix(clearLight, wetLight, WorldTimeWeatherDimension.y);
}
// r5.9 waterFog.glsl's non-volumetric response, driven by native depth reconstruction.
float portWaterFog(float distanceInWater) { return 1.0 - exp(-max(distanceInWater, 0.0) / 32.0); }
