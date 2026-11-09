package dev.horizon.client.module

// Enums shared between several modules.

@Suppress("EnumEntryName")
enum class Animation { Fade, Hover, Decrease, Static, Flash, Grow, FILL, TNT, Pull }

enum class ColorMode { CUSTOM, SYNC, RGB, SKY, LIGHT_RGB }

enum class ColorMode2 { SINGLE, RGB, DOUBLE, DOUBLE_UP, TV }

enum class Esp { OFF, COMETS, SELECTION, BLOOM_CIRCLE }

@Suppress("EnumEntryName")
enum class Interact { Vanilla, Strict, AirPlace, GRIM }

enum class Mode { STAR, FIRE_FLY, DOLLAR, HEART, SNOW_FLAKE, DROP, CLOUD }

@Suppress("EnumEntryName")
enum class PlaceMode { Packet, Normal }

enum class Render { SHADER, FILL, LINE, BOTH, NONE }

enum class RenderMode { FILL, OUTLINE, BOTH }

enum class ShaderMode { MIRROR, DEFAULT, BLOOM, DOUBLE, CAMOUFLAGE }

enum class Switch { NORMAL, SILENT, INVENTORY_SILENT, INVENTORY_NORMAL, SILENT_FULL, NORMAL_FULL }

@Suppress("EnumEntryName")
enum class Switch2 { Silent, Normal, Alternative }

@Suppress("EnumEntryName")
enum class Timing { Vanilla, Instant }
