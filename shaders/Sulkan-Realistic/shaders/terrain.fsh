#version 450
#include "shaders/common.glsl"
#include "shaders/terrain-globals.glsl"
#include "shaders/include/terrain_material.glsl"
#include "shaders/palette.glsl"
#include "sulkan/shadows.glsl"
#include "shaders/environment.glsl"
#include "shaders/specular.glsl"
#include "shaders/held-light.glsl"
uniform sampler2D u_BlockTex;
uniform sampler2D u_LightTex;
uniform sampler2D Opaque;
uniform sampler2D OpaqueDepth;
#include "shaders/surface-data.glsl"
#include "shaders/ice.glsl"
layout(location=0) in vec4 tint;
layout(location=1) in vec2 uv;
layout(location=2) in vec3 relativePosition;
layout(location=3) in vec2 lightLevel;
layout(location=4) in float fade;
layout(location=5) flat in uint materialId;
layout(location=6) flat in uint terrainMaterial;
layout(location=0) out vec4 color;
layout(location=1) out uvec2 surfaceData;
void main() {
    vec3 eyeDirection=normalize(sulkanCameraOffset()-relativePosition);
    // Derivatives precede every discard and nonuniform material branch.
    vec3 crossNormal = cross(dFdx(relativePosition), dFdy(relativePosition));
    vec3 normal = crossNormal * inversesqrt(max(dot(crossNormal,crossNormal), 1e-12));
    if (dot(normal, eyeDirection) < 0.0) normal = -normal;
    bool ice=materialId>=12u && materialId<=14u;
    float iceRoughness=0.0;
    if(ice) normal=sulkanIceDetail(relativePosition,normal,iceRoughness);
    vec3 storedNormal=normal;
    bool enhancedWater=false;
    vec4 base = sulkanSampleTerrainMaterial(u_BlockTex, uv, terrainMaterial) * tint;
    vec3 albedo=toLinear(base.rgb);
    if(materialId==12u) {
        // Only clear ice replaces its atlas color with transmission tint.
        // Packed and blue ice retain their original texture and color beneath
        // the rough surface lighting and screen-space reflections.
        albedo=vec3(0.72,0.86,0.92);
    }
#ifdef SULKAN_TRANSLUCENT
    if(base.a<=1.0/255.0) discard;
#endif
#ifdef ALPHA_CUTOUT
    if (base.a < sulkanMaterialAlphaCutoff(terrainMaterial)) discard;
#endif
    float worldDistance = length(relativePosition);
    float overworld = 1.0-step(0.5,abs(WorldTimeWeatherDimension.w));
    float daylight = sulkanDay();
    vec3 sunDirection = daylight>0.5?SunDirectionAndRainBrightness.xyz:MoonDirectionAndPhase.xyz;
    bool plant = materialId >= 2u && materialId <= 5u;
    bool cutoutSurface = plant || sulkanMaterialAlphaCutoff(terrainMaterial)>0.0;
    float sunDot = dot(normal,normalize(sunDirection+vec3(0,1e-5,0)));
    float sunFacing = max(sunDot,0.0);
    // Keep receiver bias toward the light for thin cutouts, but shade the actual
    // visible face above. Bias orientation must not light the back of the plane.
    vec3 shadowNormal = cutoutSurface && dot(normal,sunDirection)<0.0 ? -normal : normal;
    bool grass = materialId>=2u && materialId<=4u;
    float skyLight = pow(lightLevel.y,2.0);
    // Sodium encodes sky light 0 as 1/30. A surface sealed from the sky at every
    // vertex cannot see the sun, so skip the cascade filter and use the fully
    // shadowed result the map reports for enclosed surfaces.
#if SHADOW_QUALITY > 0
    float shadow = mix(1.0,ShadowParams.z,ShadowParams.x);
    float sunVisibility = 0.0;
#else
    float shadow = 1.0;
    float sunVisibility = 1.0;
#endif
    if(lightLevel.y > 1.0/30.0+1e-4)
        shadow = sulkanTerrainShadows(relativePosition,shadowNormal,grass,sunVisibility);
    float blockLight = pow(lightLevel.x,2.4);
    // Separate warm block emission, cool sky fill and directional sunlight.
    vec3 skyFill = mix(vec3(0.012,0.018,0.035),vec3(0.36,0.48,0.66),daylight);
    skyFill = mix(skyFill,vec3(dot(skyFill,vec3(0.299,0.587,0.114))),WorldTimeWeatherDimension.y*0.65);
    float directFacing = cutoutSurface ? sunFacing : (0.35+0.65*sunFacing);
    vec3 direct = portLightPalette()*directFacing*shadow*(1.0-WorldTimeWeatherDimension.y*0.65);
    vec3 lighting = vec3(0.035)+skyLight*(skyFill*0.38+direct*0.85);
    if (materialId == 5u) {
        // Skylight falls as it passes through the canopy. Use the interpolated
        // light, rather than block coordinates, to keep adjacent leaves blended.
        float exposure = exp2(-5.0*(1.0-clamp(lightLevel.y,0.0,1.0)));
        float skyFacing = 0.75+0.25*max(normal.y,0.0);
        vec3 leafSky = skyFill*0.38*mix(0.45,1.0,exposure)*skyFacing;
        vec3 leafDirect = direct*mix(0.5,1.25,exposure);
        // A small forward-scattering glow makes sunlit leaves feel translucent;
        // shadow visibility and canopy depth prevent buried leaves from glowing.
        vec3 sun = normalize(SunDirectionAndRainBrightness.xyz+vec3(0,1e-5,0));
        float throughLeaf = pow(max(dot(-eyeDirection,sun),0.0),4.0);
        float transmission = throughLeaf*exposure*shadow
            *smoothstep(0.0,0.15,sun.y)*(1.0-WorldTimeWeatherDimension.y)*0.25;
        lighting = vec3(0.035)+skyLight*(leafSky+leafDirect*0.85
            +portLightPalette()*transmission);
    }
    // Deepen clear-day cast shadows without crushing torchlight or night detail.
    // Reuse visibility so this adds no shadow samples or rendering passes.
    float shadowContrast = (1.0-shadow)*daylight*(1.0-WorldTimeWeatherDimension.y);
    // Back-facing cutouts receive ambient skylight, not a second cast-shadow
    // darkening on top of their already absent direct sunlight.
    if(cutoutSurface) shadowContrast*=sunFacing;
    lighting *= 1.0-0.28*shadowContrast*skyLight;
    vec3 warmBlock = mix(vec3(1.0),vec3(1.35,0.76,0.34),clamp(LIGHT_WARMTH,0.0,1.0));
    lighting += warmBlock*blockLight*1.2;
    // Keep the vanilla lightmap for dimension-specific lighting outside the Overworld.
    vec3 vanillaLight = texture(u_LightTex,lightLevel*(15.0/16.0)).rgb;
    // Minecraft's scene target stores display-space color; encode the linear light factor once.
    vec3 heldLight=sulkanHeldLight(relativePosition,normal);
    vec3 surface=albedo*(mix(toLinear(vanillaLight),max(lighting,vec3(0)),overworld)+heldLight);
    if(materialId!=1u && overworld>0.5 && CameraPositionHighAndFogType.w<0.5) {
        // Skylight approximates rain exposure; upward faces collect most moisture.
        float wet=WET_SURFACES*WorldTimeWeatherDimension.y
            *smoothstep(0.88,1.0,lightLevel.y)*smoothstep(0.0,0.7,normal.y);
        if(materialId>=9u) wet=0.0;
        surface*=1.0-0.22*wet;
        float materialWeight=(materialId>=6u && materialId<=8u)?MATERIAL_SHEEN:0.0;
        float roughness=mix(0.55,materialId==6u?0.27:materialId==8u?0.7:0.48,materialWeight);
        float reflectance=mix(0.035,0.18,materialId==6u?materialWeight:0.0);
        if(ice) { roughness=mix(0.24,0.46,iceRoughness); reflectance=0.025; }
        {
            float highlight=sulkanSunSpecular(normal,eyeDirection,mix(roughness,0.22,wet),reflectance);
            surface+=portLightPalette()*highlight*sunVisibility*skyLight;
        }
        if(wet>0.0) {
            // Analytic sky sheen stays visible under overcast skies. No reflection tracing.
            float fresnel=0.02+0.18*pow(1.0-max(dot(normal,eyeDirection),0.0),5.0);
            surface+=sulkanSky(reflect(-eyeDirection,normal))*wet*fresnel*skyLight;
        }
    }
    // Bright texture regions emit; unlit redstone and berry-free vines are unmapped.
    float emission=0.0;
    if(materialId==9u) emission=smoothstep(0.12,0.65,max(albedo.r,max(albedo.g,albedo.b)))*1.2;
    if(materialId==10u) emission=smoothstep(0.12,0.5,albedo.r)*0.85;
    if(materialId==11u) emission=smoothstep(0.08,0.4,albedo.r-max(albedo.g,albedo.b))*1.2;
    surface+=albedo*emission*EMISSIVE_ACCENTS;
    base.rgb=pow(max(surface,vec3(0.0)),vec3(1.0/2.2));
#if WATER_ENABLED == 1
    if (materialId == 1u && CameraPositionHighAndFogType.w < 0.5) {
        vec2 screenUv = gl_FragCoord.xy * ViewSizeAndInverse.zw;
        vec2 worldXZ = relativePosition.xz + mod(CameraPositionHighAndFogType.xz, vec2(4096.0)) + CameraPositionLowAndFarPlane.xz;
        float t = TimeDeltaFrame.x * 0.25;
        // Short, gentle ripples: quarter the wavelength without speeding up animation.
        vec2 wave = vec2(cos(dot(worldXZ, vec2(2.2,1.24)) + t*1.2), sin(dot(worldXZ,vec2(-1.12,2.48)) - t*0.9));
        vec2 refractedUv = clamp(screenUv + wave * WATER_WAVES * 0.0008 / (1.0 + worldDistance * 0.025), ViewSizeAndInverse.zw, vec2(1.0)-ViewSizeAndInverse.zw);
        float surfaceDistance = length(viewPosition(screenUv, gl_FragCoord.z));
        float backDistance = length(viewPosition(refractedUv, texture(OpaqueDepth, refractedUv).r));
        if (backDistance < surfaceDistance) refractedUv = screenUv;
        backDistance = length(viewPosition(refractedUv, texture(OpaqueDepth, refractedUv).r));
        float thickness = clamp(backDistance - surfaceDistance, 0.0, 160.0);
        vec3 waterNormal = normalize(normal + vec3(wave.x,0.0,wave.y) * (0.018 * WATER_WAVES));
        vec3 waterTint = mix(vec3(0.035,0.21,0.28), tint.rgb * vec3(0.2,0.65,0.75), 0.25);
        vec4 background = linearSample(Opaque, refractedUv);
        if (background.a > 0.0) {
            vec3 absorption=exp(-thickness*WATER_DENSITY*vec3(0.16,0.065,0.035));
            // Absorbed light is replaced by locally lit scattering, never an
            // unlit cyan tint. The background already contains its own lighting.
            vec3 waterLighting=mix(toLinear(vanillaLight),max(lighting,vec3(0)),overworld)+heldLight;
            vec3 scattering=toLinear(waterTint)*waterLighting;
            vec3 transmission=toLinear(background.rgb)*absorption+scattering*(1.0-absorption);
            storedNormal=waterNormal;
            enhancedWater=true;
            vec3 waterColor=transmission;
            waterColor+=portLightPalette()*sulkanSunSpecular(waterNormal,eyeDirection,0.16,0.02)*sunVisibility*skyLight*overworld;
            base=vec4(pow(max(waterColor,vec3(0)),vec3(1.0/2.2)),1.0);
        }
    }
#endif
    // Only naturally translucent ice can sample the scene behind it. Packed and
    // blue ice keep their lit opaque surface and still receive ice SSR below.
#ifdef SULKAN_TRANSLUCENT
    if(materialId==12u) {
        vec2 screenUv=gl_FragCoord.xy*ViewSizeAndInverse.zw;
        vec2 distortion=(mat3(View)*normal).xy*0.0007/(1.0+worldDistance*0.025);
        vec2 refractedUv=clamp(screenUv+distortion,ViewSizeAndInverse.zw,vec2(1)-ViewSizeAndInverse.zw);
        float front=length(viewPosition(screenUv,gl_FragCoord.z));
        float backDepth=texture(OpaqueDepth,refractedUv).r;
        if(backDepth>1e-7 && length(viewPosition(refractedUv,backDepth))<front) refractedUv=screenUv;
        backDepth=texture(OpaqueDepth,refractedUv).r;
        float thickness=backDepth>1e-7?clamp(length(viewPosition(refractedUv,backDepth))-front,0.0,12.0):1.0;
        vec4 background=linearSample(Opaque,refractedUv);
        vec3 transmission=background.a>0.0?toLinear(background.rgb):sulkanSky(normalize(relativePosition));
        vec3 absorption=exp(-thickness*vec3(0.04,0.016,0.008));
        vec3 iceColor=transmission*absorption+albedo*(vec3(1)-absorption)*0.25;
        // Uneven internal frost scatters light and reduces clarity in rough patches.
        float haze=mix(0.025,0.085,smoothstep(0.2,0.85,iceRoughness));
        haze=clamp(haze+0.015*(1.0-exp(-thickness*0.3)),0.0,0.10);
        iceColor=mix(iceColor,albedo*(lighting+heldLight)*0.65,haze);
        iceColor+=portLightPalette()*sulkanSunSpecular(normal,eyeDirection,mix(0.24,0.46,iceRoughness),0.025)*sunVisibility*skyLight*overworld;
        base=vec4(pow(max(iceColor,vec3(0)),vec3(1.0/2.2)),1.0);
    }
#endif
    float sphericalFog = clamp((worldDistance-u_EnvironmentFog.x)/max(u_EnvironmentFog.y-u_EnvironmentFog.x,0.001),0.0,1.0);
    // Outdoor atmosphere is handled once in the post pass. Keep render-distance
    // fading below, and vanilla environment fog for water/lava/other dimensions.
    if(overworld>0.5 && CameraPositionHighAndFogType.w<0.5) sphericalFog=0.0;
    float cylinder = max(length(relativePosition.xz), abs(relativePosition.y));
    float renderFog = clamp((cylinder-u_RenderFog.x)/max(u_RenderFog.y-u_RenderFog.x,0.001),0.0,1.0);
    float fog = max(1.0-fade, max(materialId==1u && CameraPositionHighAndFogType.w<0.5?0.0:sphericalFog,renderFog)) * u_FogColor.a;
    vec3 fogColor = u_FogColor.rgb;
    if(overworld>0.5 && CameraPositionHighAndFogType.w<0.5) {
        float skyAccess=min(smoothstep(0.05,0.65,CloudOffsetAndGameTime.z),smoothstep(0.01,0.5,skyLight));
        // Keep vanilla distance-fade color under cover; do not force it toward
        // black with a cave-specific multiplier. Mist is handled in the post pass.
        fogColor=mix(fogColor,pow(sulkanSky(normalize(relativePosition)),vec3(1.0/2.2)),skyAccess);
    }
    color = vec4(mix(base.rgb,fogColor,fog),base.a);
    surfaceData=uvec2(encodeSurface(storedNormal,skyLight,1.0-fog,enhancedWater),floatBitsToUint(gl_FragCoord.z));
    if(ice) surfaceData.x |= 0x20000000u;
#ifdef SULKAN_TRANSLUCENT
    if(!enhancedWater) surfaceData.x |= 0xc0000000u;
#endif
}
