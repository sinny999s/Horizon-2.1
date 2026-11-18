// Shared volume integrator: direct sky and reflected rays sample identical cloud density/light.
#include "shaders/cloud-field.glsl"
vec4 sulkanCloudVolume(vec3 origin, vec3 ray, float maximum, int count, out float firstDistance) {
    vec4 clouds=vec4(0.0); firstDistance=60000.0;
#if CLOUD_QUALITY > 0
    float cameraY=origin.y;
    float a,b;
    if (abs(ray.y)<1e-5) { if (cameraY<cloudBottom() || cameraY>cloudBottom()+4.0) return vec4(0.0); a=0.0; b=1600.0; }
    else { a=(cloudBottom()-cameraY)/ray.y; b=(cloudBottom()+4.0-cameraY)/ray.y; }
    float entry=max(min(a,b),0.0), end=min(max(a,b),1600.0);
    end=min(end,maximum);
    if (end<=entry) return vec4(0.0);


    vec3 light=sulkanDay()>0.5?SunDirectionAndRainBrightness.xyz:MoonDirectionAndPhase.xyz;
    vec3 ambient=sulkanSky(vec3(0,1,0))*0.65;
    vec3 direct=portLightPalette()*1.15;
    vec3 horizonColor=sulkanSky(ray);
    float silver=pow(max(dot(ray,light),0.0),12.0)*0.6;
    float extinction=cloudExtinction();
    if(extinction<=0.0) return clouds;
    float transmission=1.0;
    // Integrate the continuous field independently of texture cell boundaries.
    // Bound the horizontal spacing too, so grazing rays preserve cloud edges.
    float stepSize=max(min((end-entry)/float(count),6.0),(end-entry)/256.0);
    for(int i=0;i<256;i++) {
        if(entry+float(i)*stepSize>=end || transmission<0.005) break;
        float front=entry+float(i)*stepSize;
        float segment=min(stepSize,end-front);
        float t=front+segment*0.5;
        vec3 p=origin+ray*t;
        bool emptyCell;
        vec4 cellColor=cloudField(p,emptyCell);
        if(emptyCell) {
            // All four interpolation corners are empty: skip to this cell's exit.
            // Retain the original march lattice so visible samples never shift.
            vec2 grid=cloudGrid(p)-0.5;
            vec2 boundary=floor(grid)+step(vec2(0),ray.xz);
            vec2 travel=(boundary-grid)*12.0/vec2(abs(ray.x)>1e-6?ray.x:1e-6,abs(ray.z)>1e-6?ray.z:1e-6);
            float exitDistance=min(abs(ray.x)>1e-6?travel.x:1e20,abs(ray.z)>1e-6?travel.y:1e20);
            i+=int(clamp(floor(exitDistance/stepSize)-1.0,0.0,255.0));
            continue;
        }
        float density=cellColor.a*cloudHeightDensity(p)*extinction;
        if(density<0.0001) continue;
        if(firstDistance>59000.0) firstDistance=front;
        float lightExit=abs(light.y)>0.02?max((light.y>0.0?cloudBottom()+4.0-p.y:cloudBottom()-p.y)/light.y,0.0):24.0;
        lightExit=min(lightExit,24.0);
        float lightDepth;
        if(count>12) lightDepth=(cloudDensity(p+light*(lightExit*0.25))+cloudDensity(p+light*(lightExit*0.75)))*lightExit*0.5;
        else lightDepth=cloudDensity(p+light*(lightExit*0.5))*lightExit;
        float height=clamp((p.y-cloudBottom())/4.0,0.0,1.0);

        vec3 sampleColor=ambient*(0.9+height*0.2)+direct*(0.35+0.75*exp(-lightDepth*0.7))*(0.75+silver);
        sampleColor*=cellColor.rgb;
        sampleColor=mix(sampleColor,horizonColor,1.0-exp(-t*0.00065));
        float opacity=1.0-exp(-density*segment);
        clouds.rgb+=sampleColor*opacity*transmission;
        transmission*=1.0-opacity;
    }
    clouds.a=1.0-transmission;
#endif
    return clouds;
}
