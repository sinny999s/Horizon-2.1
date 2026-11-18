#version 450
#include "shaders/terrain-globals.glsl"
#include "shaders/include/vegetation_wind.glsl"
layout(location=0) in uvec2 a_Position;
layout(location=1) in vec4 a_Color;
layout(location=2) in uvec2 a_TexCoord;
layout(location=3) in uvec4 a_LightAndData;
layout(location=4) in uint a_SulkanMaterial;
uniform sampler2D u_LightTex;
uniform isamplerBuffer u_SectionTimeInfo;
layout(push_constant) uniform PC { vec3 u_RegionOffset; int u_CurrentTime; uint u_RegionID; };
layout(location=0) out vec4 tint;
layout(location=1) out vec2 uv;
layout(location=2) out vec3 relativePosition;
layout(location=3) out vec2 lightLevel;
layout(location=4) out float fade;
layout(location=5) flat out uint materialId;
layout(location=6) flat out uint terrainMaterial;
void main() {
    // Decode the compact 20-bit XYZ and section index defined by Sodium's mesh ABI.
    vec3 local;
    for (uint axis=0u; axis<3u; ++axis) {
        uint value = (((a_Position.x >> (axis*10u)) & 1023u) << 10u) | ((a_Position.y >> (axis*10u)) & 1023u);
        local[axis] = float(value) / 32768.0 - 8.0;
    }
    uint section = a_LightAndData.w;
    vec3 sectionOffset = vec3((section >> 5u) & 7u, section & 3u, (section >> 2u) & 7u) * 16.0;
    relativePosition = local + sectionOffset + u_RegionOffset - u_CameraOffset;
    relativePosition += sulkanWindOffset(relativePosition, a_SulkanMaterial);
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(relativePosition, 1.0);
    vec2 lightUv = vec2(a_LightAndData.xy) / 256.0;
    tint = a_Color;
    lightLevel = clamp(lightUv * (16.0/15.0), 0.0, 1.0);
    uv = vec2(a_TexCoord & 32767u) / 32768.0 + (vec2(a_TexCoord >> 15u) * 2.0 - 1.0) * u_TexCoordShrink;
    int birth = texelFetch(u_SectionTimeInfo, int(u_RegionID * 256u + section)).r;
    fade = birth < 0 ? 1.0 : clamp(float(u_CurrentTime - birth) * u_FadePeriodInv, 0.0, 1.0);
    materialId = a_SulkanMaterial & 65535u;
    terrainMaterial = a_LightAndData.z;
}
