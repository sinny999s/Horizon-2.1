<div align="center">

# Horizon 2.0

**Next-generation Fabric utility client for Minecraft 26.3**

*Engineered for anarchy PvP, high-speed highway navigation, and modern glassmorphism ergonomics.*

[![Minecraft](https://img.shields.io/badge/Minecraft-26.3-FF2A4A?style=for-the-badge&logo=minecraft&logoColor=white)](https://www.minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric_Loader-0.20+-black?style=for-the-badge&logo=fabric&logoColor=white)](https://fabricmc.net)
[![Java](https://img.shields.io/badge/Java-25-orange?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](LICENSE)

[Features](#-features) · [Installation](#-installation) · [Commands](#-commands) · [Building](#-building-from-source) · [License](#-license)

</div>

---

## ✨ Features

### 🖥️ Modern Glassmorphism GUI & HUD
- **Floating Windows**: Clean multi-window desktop interface inspired by modern glass OS designs.
- **Visual HUD Editor**: Drag-and-drop HUD elements, real-time widget snapping, custom margins, and persistent layouts.
- **Profile & Config Engine**: Switch between module configurations and HUD layouts with instant hot-swapping and smooth audio feedback.
- **Customizable Theming**: High-contrast Crimson Red accents, subtle glass blurs, and crisp typography.

---

### ⚔️ Combat & Crystal PvP
- **AutoCrystal**: High-frequency crystal placement and detonation in the same tick. Includes entity ID prediction, damage calculation, face-placing, and anti-surround break modes.
- **KillAura**: Advanced rotation engine supporting Snap, Grim Silent, Vulcan, HvH, and Polar bypasses with weapon attack cooldown synchronization and shield disabling.
- **Surround**: Instant obsidian placement surrounding the player's feet, with AirPlace, Grim strict support, and auto-centering.
- **Support Modules**: Offhand (totem switching), AutoTrap, SelfTrap, HoleAnchor, AutoWeb, Blocker, and AutoMine.

---

### 🗺️ World Exploration & Stash Hunting
- **Hunt Map**: Interactive world and Nether map with region-level caching, seed-based procedural terrain prediction (supporting 2b2t and 6b6t seeds), and waypoint tracing.
- **BaseFinder**: Rapid chunk scanning for player activities, storage containers, non-natural block clusters, and player footprint heuristics.
- **PortalESP & StashESP**: Custom palette-based chunk section scanner rendering Nether portals, chests, shulker boxes, and spawners through walls with shader bloom.
- **SpeedMine & PacketMine**: Fast packet-based block destruction with multi-break queues.
- **AutoCraft & AutoSmelt**: Automatic container operations and bulk crafting.

---

### 🚀 Movement & Highway Navigation
- **Elytra Autopilot & ElytraPlus**: High-speed Nether highway transit, automatic bounce oscillation, obstacle clearance, and path tracking.
- **Scaffold & GodBridge**: Block bridging with auto-jump resets and edge detection.
- **Mobility**: SafeWalk, Step, ReverseStep, NoSlow, LongJump, and MoveFix (decouples camera view from packet movement).

---

### 👻 Legit & Ghost Toolkit
- **AimAssist & Trigger**: Human-like mouse curve acceleration, target tracking, and weapon charge timing.
- **AutoClicker**: Randomized click intervals with authentic human CPS variance and fatigue simulation.
- **AxeSwap & LegitTotem**: Inventory-assisted weapon swapping and totem refilling mimicking physical key presses.
- **JumpReset & WTap**: Optimized knockback mitigation and sprint reset rhythm.

---

## 📦 Installation

**Easiest (Windows):** the **[Horizon Launcher](https://github.com/sinny999s/Horizon-Launcher)** installs Minecraft 26.3
with Fabric, puts Horizon, Fabric API and Fabric Language Kotlin in every instance, and updates Horizon to the newest
release before every launch. Get it from its [latest release](https://github.com/sinny999s/Horizon-Launcher/releases/latest).

**By hand:**

1. Install **[Fabric Loader](https://fabricmc.net/) 0.20+** for **Minecraft 26.3**.
2. Download and install **[Fabric API](https://modrinth.com/mod/fabric-api)**.
3. Drop `horizon-26.3-2.0.0.jar` into your `.minecraft/mods` folder.
4. Launch the game.

> [!TIP]
> Compatible with **ViaFabricPlus** for multi-version protocol support and **Baritone** for automated pathfinding.

---

## ⌨️ Controls & Keybindings

| Key / Action | Function |
| :--- | :--- |
| <kbd>Right Shift</kbd> or <kbd>I</kbd> | Open the Horizon Modern GUI |
| <kbd>T</kbd> | Toggle Freecam |
| <kbd>H</kbd> | Toggle Scaffold |
| <kbd>U</kbd> | Toggle Freelook |
| <kbd>Y</kbd> or <kbd>.</kbd> | Open chat to type client commands (e.g. `.help`, `.toggle <module>`) |
| <kbd>Esc</kbd> | Close GUI windows |

---

## 🛠️ Building from Source

### Prerequisites
* **Java Development Kit (JDK) 25** or newer
* Git

### Build Instructions
Clone the repository and build using Gradle:

```bash
git clone https://github.com/sinny999s/Horizon-2.0.git
cd Horizon-2.0

# On Linux / macOS
./gradlew build

# On Windows
.\gradlew.bat build
```

The compiled mod will be generated in `build/libs/horizon-26.3-2.0.0.jar`.

---

## 📄 License

Horizon 2.0 is open-source software licensed under the **[MIT License](LICENSE)**.

### Third-Party Notices
- **Baritone**: Pathfinding functionality is licensed under the [LGPL-3.0](https://github.com/cabaletta/baritone/blob/master/LICENSE).
- **Fabric**: Fabric Loader and Fabric API are developed by the FabricMC project under the Apache-2.0 license.
