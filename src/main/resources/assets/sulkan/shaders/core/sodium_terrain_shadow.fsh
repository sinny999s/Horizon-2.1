#version 460 core

#define SULKAN_GLOBALS_BINDING 2
#moj_import <sulkan:sodium_globals.glsl>
#moj_import <sulkan:terrain_material.glsl>

layout(binding = 0) uniform sampler2D u_LightTex;
layout(binding = 1) uniform sampler2D u_BlockTex;
layout(binding = 3) uniform isamplerBuffer u_SectionTimeInfo;
layout(location = 0) in vec2 v_TexCoord;
layout(location = 1) flat in uint v_Material;
layout(location = 2) in float v_Alpha;
layout(location = 4) flat in uint v_PackMaterial;
#ifdef SULKAN_SHADOW_COLOR
layout(location = 3) in vec3 v_Color;
layout(location = 0) out vec4 fragColor;
#endif

// Materials the pack keeps out of the shadow maps (its environment.shadowSkipMaterials).
bool sulkanSkipShadowMaterial(uint material) {
#ifdef SULKAN_SHADOW_SKIP0
	if (material == uint(SULKAN_SHADOW_SKIP0)) return true;
#endif
#ifdef SULKAN_SHADOW_SKIP1
	if (material == uint(SULKAN_SHADOW_SKIP1)) return true;
#endif
#ifdef SULKAN_SHADOW_SKIP2
	if (material == uint(SULKAN_SHADOW_SKIP2)) return true;
#endif
#ifdef SULKAN_SHADOW_SKIP3
	if (material == uint(SULKAN_SHADOW_SKIP3)) return true;
#endif
#ifdef SULKAN_SHADOW_SKIP4
	if (material == uint(SULKAN_SHADOW_SKIP4)) return true;
#endif
#ifdef SULKAN_SHADOW_SKIP5
	if (material == uint(SULKAN_SHADOW_SKIP5)) return true;
#endif
#ifdef SULKAN_SHADOW_SKIP6
	if (material == uint(SULKAN_SHADOW_SKIP6)) return true;
#endif
#ifdef SULKAN_SHADOW_SKIP7
	if (material == uint(SULKAN_SHADOW_SKIP7)) return true;
#endif
	return false;
}

void main() {
	if (sulkanSkipShadowMaterial(v_PackMaterial)) discard;
#ifdef SULKAN_SHADOW_COLOR
	// The light that gets through this caster (transmittance) and how far along the light it sits.
	vec4 albedo = sulkanSampleTerrainMaterial(u_BlockTex, v_TexCoord, v_Material) * vec4(v_Color, v_Alpha);
	if (albedo.a < ALPHA_CUTOUT) discard;
	float cover = 1.0 - pow(1.0 - min(albedo.a, 1.0), SULKAN_SHADOW_ALPHA_POWER);
	vec3 transmit = mix(vec3(1.0), albedo.rgb, cover) * (1.0 - pow(min(albedo.a, 1.0), 96.0));
	fragColor = vec4(transmit, gl_FragCoord.z);
	return;
#endif
#ifdef ALPHA_CUTOUT
	
	
	float alpha = sulkanSampleTerrainMaterial(u_BlockTex, v_TexCoord, v_Material).a * v_Alpha;
	if (alpha < sulkanMaterialAlphaCutoff(v_Material)) discard;
#endif
}
