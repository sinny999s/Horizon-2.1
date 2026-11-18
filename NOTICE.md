# Notices

Horizon is free software under the GNU General Public License, version 3 only (`LICENSE`). Releases up to and
including 2.1.32 were published under the MIT License; that stays true for those releases.

## Shaders (`src/main/java/dev/horizon/shaders`, `shaders/Sulkan-Realistic`, `src/main/resources/assets/sulkan`)

Horizon's shaders are built from **Sulkan** by mravatin (<https://github.com/mravatins/sulkanShaders>), licensed under
the GNU General Public License, version 3: native shader packs for Minecraft's Vulkan renderer on Sodium. Horizon's copy
is Sulkan 0.6.0 for Minecraft 26.3, moved into the `dev.horizon.shaders` package, with Horizon's changes (the pack
features listed in Sulkan's source README, the Sodium 0.9.3 port, and the module gate `ShadersGate`). The bundled pack
`shaders/Sulkan-Realistic` (listed in the game as Horizon Realistic) and the sun and moon textures under `assets/minecraft/textures/environment/celestial` are
Sulkan's too.

The shaders draw through **Sodium** (CaffeineMC, PolyForm Shield 1.0.0), which Horizon compiles against but does not
contain or distribute: players install Sodium themselves.

## Threaded input (`src/main/java/dev/horizon/input`)

Horizon's threaded input is built from **Ixeris** by decce, with contributions by LitnhJacuzzi
(<https://github.com/decce6/Ixeris>), licensed under the GNU Lesser General Public License, version 3 (its API package
under the BSD Zero Clause License): mouse and keyboard event polling on its own thread and buffered raw input on
Windows. Horizon's copy is Ixeris 4.6.11 for Minecraft 26.3 (Fabric), moved into the `dev.horizon.input` package and
changed: the mod id, the polling thread's name, the settings file (`config/horizon-input.toml`), setters for Horizon's
Performance page, and the Forge and NeoForge parts left out. Under LGPL-3.0's terms it is conveyed here as part of
Horizon under the GPL-3.0.

## Bundled libraries

Inside Horizon's jar: NightConfig core and TOML (LGPL-3.0, threaded input's settings), Hypixel Mod API (MIT), Lunar Client Apollo protos (MIT), Protocol Buffers for Java (BSD-3-Clause),
TwelveMonkeys ImageIO (BSD-3-Clause), JSVG (MIT), JLayer (LGPL-2.1), Netty proxy and SOCKS codecs (Apache-2.0).
