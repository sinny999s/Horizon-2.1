// Sulkan Realistic sky palette, gradient and celestial environment.
float sulkanDay() { return smoothstep(-0.14, 0.12, SunDirectionAndRainBrightness.y); }
vec3 sulkanCameraOffset() {
    vec4 eye=InverseProjection*vec4(0,0,1,0);
    return mat3(InverseView)*(eye.xyz/eye.w);
}
vec3 sulkanRay(vec2 uv) { return normalize(mat3(InverseView) * (viewPosition(uv, 0.001) - viewPosition(uv, 0.9))); }
vec3 sulkanSky(vec3 ray) {
    float sunY = SunDirectionAndRainBrightness.y;
    float noon = clamp(sunY, 0.0, 1.0), day = sulkanDay(), rain = WorldTimeWeatherDimension.y;
    float towardSun = max(dot(ray, SunDirectionAndRainBrightness.xyz), 0.0);
    vec3 sky = max(SkyColorAndStarBrightness.rgb, vec3(0.265,0.295,0.35) * day);
    vec3 skySqrt = sqrt(max(sky, vec3(0.001)));
    vec3 noonUp = pow(skySqrt,vec3(2.9))*vec3(0.85,0.92,0.81)*0.48;
    vec3 noonMiddle = skySqrt*vec3(1.4,1.3,1.3)*0.58 + noonUp*0.25;
    vec3 sunsetUp = sky*vec3(0.72,0.522,0.47)*0.5;
    vec3 sunsetMiddle = sky*vec3(1.8,1.3,1.2)*0.65;
    float dusk = pow(1.0-noon,2.0);
    vec3 night = 0.9*vec3(0.07,0.14,0.24)*(1.0-0.5*rain);
    vec3 nightUp = pow(night,vec3(0.9))*0.12;
    vec3 nightMiddle = sqrt(nightUp)*0.20;
    vec3 up = mix(nightUp,mix(noonUp,sunsetUp,dusk),day);
    vec3 middle = mix(nightMiddle,mix(noonMiddle,sunsetMiddle,dusk),day);
    float horizon = pow(1.0-max(ray.y,0.0),2.3-0.8*pow(towardSun,4.0));
    vec3 color = mix(up,middle,horizon);
    float sunset = pow(1.0-abs(ray.y),4.0) * dusk * day * (1.0-rain);
    color = mix(color, vec3(1.45,0.86,0.5)*(0.48+0.25*towardSun*towardSun),sunset*0.8);
    float glareDot = max(dot(ray, day>0.5 ? SunDirectionAndRainBrightness.xyz : MoonDirectionAndPhase.xyz),0.0);
    float glare = 0.075/(1.0-0.925*pow(glareDot,6.0))-0.075;
    color += mix(vec3(0.08,0.10,0.18),mix(vec3(1.5,0.7,0.3),vec3(1.5,1.2,0.8),noon),day)*glare*0.45*(1.0-rain);
    vec3 wet = mix(vec3(0.025,0.035,0.055),vec3(0.24,0.28,0.34),day);
    return max(mix(color,wet,rain*0.8),vec3(0.0));
}
float sulkanHash(vec3 p) { p=fract(p*0.1031); p+=dot(p,p.yzx+33.33); return fract((p.x+p.y)*p.z); }
vec3 sulkanCelestials(vec3 ray) {
    float rain=WorldTimeWeatherDimension.y, day=sulkanDay();
    float sun=dot(ray,SunDirectionAndRainBrightness.xyz);
    float radius=0.015*SUN_SIZE;
    float disc=smoothstep(cos(radius*1.06),cos(radius*0.94),sun);
    vec3 color=disc*mix(vec3(12.0,4.5,1.2),vec3(14.0,11.0,6.0),clamp(SunDirectionAndRainBrightness.y*2.0,0.0,1.0));
    vec3 moon=normalize(MoonDirectionAndPhase.xyz+vec3(0.0,1e-5,0.0));
    vec3 right=normalize(cross(moon,abs(moon.y)>0.95?vec3(0,0,1):vec3(0,1,0)));
    vec2 m=vec2(dot(ray,right),dot(ray,cross(right,moon)))/0.018;
    float z=sqrt(max(1.0-dot(m,m),0.0));
    float phase=MoonDirectionAndPhase.w*0.785398163;
    float moonLight=max(z*cos(phase)+m.x*sin(phase),0.0);
    float moonDisc=(1.0-smoothstep(0.94,1.0,length(m)));
    color+=vec3(0.7,0.86,1.15)*moonLight*moonDisc*step(0.0,dot(ray,moon))*1.8;
    vec3 cells=ray*420.0;
    float star=step(0.997,sulkanHash(floor(cells)))*exp(-dot(fract(cells)-0.5,fract(cells)-0.5)*24.0);
    color+=vec3(0.65,0.78,1.0)*star*4.0*(1.0-day)*smoothstep(0.0,0.25,ray.y);
    return color*(1.0-rain)*smoothstep(-0.04,0.02,ray.y);
}
