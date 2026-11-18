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
layout(location = 0) out vec4 fragColor;

void main() {
#ifdef ALPHA_CUTOUT
	// Combined batches contain opaque and masked primitives. A zero cutoff keeps
	// solid geometry opaque even when its atlas texels contain transparent pixels.
	float alpha = sulkanSampleTerrainMaterial(u_BlockTex, v_TexCoord, v_Material).a * v_Alpha;
	if (alpha < sulkanMaterialAlphaCutoff(v_Material)) discard;
#endif
	fragColor = vec4(0.0, 0.0, 0.0, 1.0);
}
