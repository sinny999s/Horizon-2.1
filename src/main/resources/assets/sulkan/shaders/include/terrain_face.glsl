// Sulkan terrain faces (a pack's sulkan.json "environment": {"terrainNormals": true}). Each chunk vertex carries its
// quad's face code in the top 2 bits of both a_Position words, which Sodium's 3 x 10-bit packing leaves free (decoders
// mask them off). Pass a_Position to sulkanFaceCode in the vertex shader and the code on as a flat uint.
//   bits 0-2: 0 unknown, 1-6 the face's direction (down, up, north, south, west, east), 7 a slanted quad
//   bit 3: Minecraft shades the face as if it pointed up (a model's shade_direction_override "up": plants, crops,
//          torches, ladders, fire...; also a fluid's bottom)
// a_Color still carries Minecraft's face shade (and smooth lighting's occlusion): divide by sulkanFaceShade for the
// colour without it. Slanted quads carry Sodium's shade by their normal; use the pack's own normal there.
#ifndef SULKAN_TERRAIN_FACE
#define SULKAN_TERRAIN_FACE

uint sulkanFaceCode(uvec2 position) { return (position.x >> 30u) | ((position.y >> 30u) << 2u); }
uint sulkanFaceDirection(uint code) { return code & 7u; }
bool sulkanFaceAligned(uint code) { uint d = code & 7u; return d >= 1u && d <= 6u; }

// The face's outward normal (world axes) for codes 1-6.
vec3 sulkanFaceNormal(uint code) {
    uint d = code & 7u;
    if (d == 1u) return vec3(0.0, -1.0, 0.0);
    if (d == 2u) return vec3(0.0, 1.0, 0.0);
    if (d == 3u) return vec3(0.0, 0.0, -1.0);
    if (d == 4u) return vec3(0.0, 0.0, 1.0);
    if (d == 5u) return vec3(-1.0, 0.0, 0.0);
    return vec3(1.0, 0.0, 0.0);
}

// Minecraft's face shade in a_Color for codes 1-6 (CardinalLighting: the Nether's tops and bottoms are 0.9).
float sulkanFaceShade(uint code, bool nether) {
    uint d = (code & 8u) != 0u ? 2u : (code & 7u);
    if (d == 1u) return nether ? 0.9 : 0.5;
    if (d == 2u) return nether ? 0.9 : 1.0;
    if (d == 3u || d == 4u) return 0.8;
    return 0.6;
}
#endif
