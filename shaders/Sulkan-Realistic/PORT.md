# Sulkan Realistic

Unofficial native Vulkan adaptation of **the upstream visual reference**, by
[the upstream shader authors](https://www.sulkan.org/). This is not the official
pack and does not claim exact visual or feature parity.

Source-port work uses separate permission reported by the project owner. The adapted visual shaders retain the upstream pack license; the loader license
does not replace it. The shared loader shadow helpers in shaders/core and
shaders/include remain Sulkan code under the repository GPLv3 license. It has not been published to a
shader distribution site.

## Features

- Cascaded terrain and foliage shadows, plus nearby player/entity model casters
  (including the first-person player), with quality and distance controls.
- Rounded volumetric clouds with wind, internal light attenuation, silver lining,
  weather response, and day/night color. Cloud quality, coverage and altitude controls.
- Day/sunset/night sky, bright sun disc, moon phases, stars and atmospheric glare.
- Height-dependent atmospheric fog, weather haze, and world-space sun shafts.
- Cool skylight, directional sun/moon light, warm block light, and contact shading.
- Depth-based water refraction, wave normals, absorption and Fresnel reflections of
  visible opaque geometry, the sky, celestials and volumetric clouds. Reflection quality
  and strength controls include a tracing-off option.
- HDR post-processing, reduced-resolution bloom, and Sulkan Realistic tone/color treatment.

Cloud tracing and contact shading/shafts run at half resolution. Cloud wind follows
vanilla game ticks (including tick-speed changes), with lighter density and coverage. Full-resolution
composition uses depth-aware upsampling and excludes the hand from world effects.
The renderer keeps the shadow producer separate from pack lighting; native environment
ownership suppresses vanilla cloud geometry and celestial discs while the pack is active.

## Provenance

- `tonemap.glsl`: r5.9 `program/composite5.glsl`, DoCompTonemap and DoBSLColorSaturation.
  The modified Lottes curve/BSL saturation use native options. Undefined reversed
  smoothstep edges were replaced with one minus an ascending smoothstep.
- `palette.glsl`: r5.9 `lib/colors/lightAndAmbientColors.glsl` light palettes and
  `lib/atmospherics/fog/waterFog.glsl` non-volumetric water absorption response.
- `environment.glsl`: adapted palette and gradient from `lib/colors/skyColors.glsl`
  and `lib/atmospherics/sky.glsl`, with new native sun/moon/star integration.
- `cloud-field.glsl`: rounded coordinates from `clouds/cloudCoord.glsl`, credited
  upstream to SixthSurge, and the eighth-power vertical profile from
  `clouds/reimaginedClouds.glsl`. Density noise, bounded volume integration and
  self-lighting use a new native implementation rather than the original buffer layout.
- `reflections.glsl`: adapts the reflected-ray march/refinement, edge confidence and
  background approach from r5.9 `lib/materials/materialMethods/reflections.glsl` and
  `reflectionBackground.glsl` to Vulkan reversed depth and native opaque snapshots.
  `cloud-volume.glsl` shares the density/light integration between direct and reflected
  cloud rays, without another scene render or reflection target.
- Native terrain decoding, material integration, water optics, height-fog integration,
  contact shading and screen-space shafts are Sulkan integrations. They are not a
  source-compatible implementation of every upstream lighting feature.

## Install

Replace both the accompanying Sulkan JAR and this pack. Requires Minecraft 26.2,
Sodium 0.9.1 and Vulkan. Put the ZIP or complete directory in `.minecraft/shaders/`,
select **Sulkan Realistic**, and enable shaders. Use Pack Settings for
cloud/shadow quality, fog, shafts, contact shading, lighting, water and color controls.
This is not an Iris `shaderpacks` ZIP.

## Limits

Terrain, foliage and supported entity models cast shadows. Entities are composited
into the nearest cascade; colored translucent shadows, block entities and item
geometry are not integrated. Entities and particles retain vanilla scene lighting and receive the final
color treatment. Sky/fog/cloud replacement is for the Overworld; other dimensions retain
Minecraft's environment. Shafts use bounded world-ray integration with terrain shadows and cloud-column
occlusion, without temporal accumulation. Water scene reflections use screen-space tracing of the opaque snapshot:
geometry outside the camera view, hidden behind other surfaces, or drawn after that
snapshot is unavailable. Misses fade into sky/cloud reflections outdoors. This is not
a second reflected world render. There is no TAA, PBR, colored voxel lighting or
optional-mod compatibility layer.

Terrain uses Minecraft's LDR scene target; HDR post-processing cannot restore clipped
terrain highlights. Translucent edge cases and long-range cloud aliasing need more work.
Cloud quality Off skips tracing but retains the graph's half-resolution intermediate
allocations. The active pack budget is 448 MiB for 4K/high-shadow coverage; actual usage
varies by resolution and quality. Fenced generations may coexist during reload.

## Validation

114 unit tests pass, including shader compilation with water on/off and every shadow
cloud and water-reflection quality setting, disabled shafts/contact shading, manifest validation, and a
4K allocation budget including shadow maps. In-game development checks on the NVIDIA
RTX 4050 Laptop GPU cover day, sunset, night, rain, sun/moon views, a torch-lit room,
resource reloads, shadow quality changes, pack switching and invalid-shader recovery.
The water fixture verifies reflected colored terrain and foliage, cloud on/off response,
wave distortion, reflection quality changes and reload. On/off image regions confirm
that reflected pillar colors are absent with reflection tracing disabled.
Screenshots and logs are retained locally under `build/reports/smoke/`.

AMD/Intel devices, extended play, dimension transitions and comparative frame-time tests
remain unverified. Vulkan alone is not evidence of higher performance or quality than Iris.


Latest corrections (camera bob, world-space shafts, cloud coverage and tick-based wind)
were checked by Java tests and shader compilation only. The earlier in-game validation
above predates these changes; the project owner is handling further in-game testing.
Install the updated loader JAR; the main pack is bundled with it.


Player/entity caster integration was checked by compilation, native shader variants,
shadow binding and allocation tests only. It has not been tested in game. Nearby native
casters are collected outside the camera frustum, with spectator/invisible entities
excluded; vanilla blob shadows are suppressed while native shadow casting is active.


Sulkan Realistic is the main native shader distributed with Sulkan. It ships inside the Sulkan JAR and is selected by default, without a separate
ZIP installation. Existing built-in selections retain their stable identifier and now
resolve to this native pack. The standalone Sulkan-Realistic.zip is optional. Existing
external pack selections remain unchanged. Upstream attribution remains
part of both bundled and standalone copies.
