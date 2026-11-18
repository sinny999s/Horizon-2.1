package dev.horizon.shaders.screen;

import dev.horizon.shaders.graph.PackGraph;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class PackSettingsModel {
    static final List<String> BASIC = List.of(
        "ANTIALIASING",
        "SHADOW_QUALITY",
        "CLOUD_QUALITY",
        "WATER_ENABLED",
        "WATER_REFLECTION_QUALITY",
        "SUN_SHAFTS",
        "ATMOSPHERE",
        "BLOOM_STRENGTH",
        "AMBIENT_OCCLUSION",
        "AUTO_EXPOSURE",
        "COLOR_GRADE",
        "WET_SURFACES",
        "MATERIAL_SHEEN",
        "EMISSIVE_ACCENTS",
        "HELD_LIGHTING"
    );
    final PackGraph graph;
    final Map<String, Double> values = new TreeMap<>();
    private final Map<String, Double> saved = new TreeMap<>();
    private final Map<String, Double> enabledValues = new HashMap<>();

    PackSettingsModel(PackGraph graph) {
        this.graph = graph;
        this.values.putAll(graph.options());
        this.matchPresets(null);
        this.saved.putAll(this.values);
    }

    List<String> keys(boolean advanced) {
        if (advanced) {
            return this.graph.optionDefinitions().keySet().stream().sorted(Comparator.comparing(this::group).thenComparing(this::name)).toList();
        }

        // The pack's own Basic page when it marks options for it, in its order; else Sulkan's usual list.
        List<String> marked = this.graph.optionDefinitions().entrySet().stream().filter(e -> e.getValue().basic()).map(Map.Entry::getKey).toList();
        if (!marked.isEmpty()) {
            return marked;
        }

        List<String> known = BASIC.stream().filter(this.graph.optionDefinitions()::containsKey).toList();
        return known.isEmpty() ? List.copyOf(this.graph.optionDefinitions().keySet()) : known;
    }

    /** Sets an option: a preset value also sets the options its preset names, and presets follow what's set. */
    void set(String key, double value) {
        PackGraph.Option option = this.graph.optionDefinitions().get(key);
        if (option == null || !option.values().contains(value)) {
            return;
        }

        this.values.put(key, value);
        Map<String, Double> preset = option.presets().get(value);
        if (preset != null) {
            this.values.putAll(preset);
        }

        this.matchPresets(key);
    }

    /**
     * Each preset option shows the preset whose settings all hold, or its first value without a preset (Custom) when
     * none does. The option just set by hand is left as it is.
     */
    private void matchPresets(String changed) {
        this.graph.optionDefinitions().forEach((key, option) -> {
            if (option.presets().isEmpty() || key.equals(changed)) {
                return;
            }

            Double match = option.presets()
                .entrySet()
                .stream()
                .filter(preset -> preset.getValue().entrySet().stream().allMatch(s -> s.getValue().equals(this.values.get(s.getKey()))))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseGet(() -> option.values().stream().filter(v -> !option.presets().containsKey(v)).findFirst().orElse(null));
            if (match != null) {
                this.values.put(key, match);
            }
        });
    }

    boolean dirty() {
        return !this.values.equals(this.saved);
    }

    void applied() {
        this.saved.clear();
        this.saved.putAll(this.values);
    }

    void reset() {
        this.graph.optionDefinitions().forEach((key, option) -> this.values.put(key, option.defaultValue()));
        this.enabledValues.clear();
        this.matchPresets(null);
    }

    boolean toggle(String key, boolean advanced) {
        List<Double> choices = this.graph.optionDefinitions().get(key).values();
        return choices.equals(List.of(0.0, 1.0));
    }

    void change(String key, boolean advanced) {
        PackGraph.Option option = this.graph.optionDefinitions().get(key);
        double current = this.values.get(key);
        if (this.toggle(key, advanced)) {
            if (current != 0.0) {
                this.enabledValues.put(key, current);
                this.set(key, 0.0);
            } else {
                this.set(
                    key,
                    this.enabledValues
                        .getOrDefault(
                            key, option.defaultValue() != 0.0 ? option.defaultValue() : option.values().stream().filter(v -> v != 0.0).findFirst().orElse(0.0)
                        )
                );
            }
        } else {
            this.set(key, option.values().get((option.values().indexOf(current) + 1) % option.values().size()));
        }
    }

    String value(String key, boolean advanced) {
        double v = this.values.get(key);
        PackGraph.Option option = this.graph.optionDefinitions().get(key);
        if (!option.names().isEmpty() && option.values().contains(v)) {
            return option.names().get(option.values().indexOf(v));
        } else if (key.equals("COLOR_GRADE") && this.graph.optionDefinitions().get(key).values().equals(List.of(0.0, 0.25, 0.5, 1.0))) {
            return v == 0.0 ? "Off" : (v == 0.25 ? "Natural" : (v == 0.5 ? "Realistic" : "Vibrant"));
        } else if (this.toggle(key, advanced)) {
            return v == 0.0 ? "Off" : "On";
        } else if (key.endsWith("_QUALITY") && v >= 0.0 && v <= 4.0 && v == Math.rint(v)) {
            return List.of("Off", "Low", "Medium", "High", "Ultra").get((int)v);
        } else {
            List<Double> choices = this.graph.optionDefinitions().get(key).values();
            if (!advanced && BASIC.contains(key) && choices.size() == 5 && choices.getFirst() == 0.0) {
                return List.of("Off", "Low", "Medium", "High", "Ultra").get(choices.indexOf(v));
            } else {
                return v == 0.0 && (BASIC.contains(key) || key.equals("WATER_WAVES") || key.equals("WATER_REFLECTION_STRENGTH"))
                    ? "Off"
                    : BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
            }
        }
    }

    String name(String key) {
        return switch (key) {
            case "SHADOW_QUALITY" -> "Shadows";
            case "CLOUD_QUALITY" -> "Clouds";
            case "WATER_ENABLED" -> "Enhanced water";
            case "WATER_REFLECTION_QUALITY" -> "Water reflections";
            case "SUN_SHAFTS" -> "Sun rays";
            case "ATMOSPHERE" -> "Atmospheric fog";
            case "BLOOM_STRENGTH" -> "Bloom";
            case "AMBIENT_OCCLUSION" -> "Contact shadows";
            default -> this.graph.optionDefinitions().get(key).label();
        };
    }

    String group(String key) {
        if (key.startsWith("WATER")) {
            return "Water";
        } else if (!key.startsWith("CLOUD") && !key.startsWith("SUN") && !key.equals("ATMOSPHERE")) {
            return !key.startsWith("SHADOW") && !key.startsWith("LIGHT") && !key.equals("AMBIENT_OCCLUSION") ? "Color & effects" : "Lighting";
        } else {
            return "Sky & atmosphere";
        }
    }

    boolean available(String key) {
        return !key.startsWith("WATER_") || key.equals("WATER_ENABLED") || this.values.getOrDefault("WATER_ENABLED", 1.0) != 0.0;
    }

    String help(String key) {
        String own = this.graph.optionDefinitions().get(key).help();
        String description = own != null ? own : switch (key) {
            case "ANTIALIASING" -> "Smooths jagged edges with a lightweight spatial filter. No motion history or ghosting.";
            case "SHADOW_QUALITY" -> "Shadows from terrain, players and creatures. Higher quality uses more GPU memory.";
            case "CLOUD_QUALITY" -> "Moving volumetric clouds in the sky and water reflections.";
            case "WATER_ENABLED" -> "Clear, tinted water with waves and reflections.";
            case "WATER_REFLECTION_QUALITY" -> "Reflects the sky and visible scenery. Requires enhanced water.";
            case "SUN_SHAFTS" -> "Soft beams of sunlight through clouds and shadows.";
            case "ATMOSPHERE" -> "Patchy low-elevation mist and subtle horizon haze, stronger around dawn and in rain.";
            case "BLOOM_STRENGTH" -> "A gentle glow around bright highlights.";
            case "HELD_LIGHTING" -> "Light from either hand over 12 blocks, with terrain, grass, player and mob shadows.";
            case "AMBIENT_OCCLUSION" -> "Adds depth to corners and nearby surfaces.";
            case "COLOR_GRADE" -> "Off: no extra grading; Natural: neutral and soft; Realistic: warm highlights and cool shadows; Vibrant: richer colors and contrast.";
            case "AUTO_EXPOSURE" -> "Gradually brightens dark areas. Sunlight briefly appears overexposed when leaving darkness, then settles. Exposure remains a manual brightness adjustment.";
            case "DISTANCE_COLOR" -> "Cool distant terrain and warmer sun-facing horizons. Requires atmospheric fog.";
            case "WET_SURFACES" -> "Darker, glossy exposed surfaces during rain. Exposure is estimated from skylight.";
            case "MATERIAL_SHEEN" -> "Distinct highlights on supported metal, stone and wooden blocks.";
            case "EMISSIVE_ACCENTS" -> "Bright accents on luminous blocks, plants and active redstone ore. Bloom adds their halo.";
            default -> "Adjust " + this.name(key).toLowerCase(Locale.ROOT) + ". Click to cycle through available values.";
        };
        return description + " Changes take effect when you press Apply.";
    }
}
