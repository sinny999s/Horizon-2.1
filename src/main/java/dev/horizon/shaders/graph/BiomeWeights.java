package dev.horizon.shaders.graph;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * The pack's biome groups (its environment's "biomes"), each a weight from 0 to 1: 1 while the camera stands in one
 * of the group's biomes (and at or above its minimum height), eased toward the new value with the group's half-life,
 * as Iris's smooth() eases a pack's biome uniforms. The first frame in a world takes the value at once.
 */
final class BiomeWeights {
    static final int MAX_GROUPS = 16;
    private final List<Matcher> matchers = new ArrayList<>();
    private final float[] values = new float[MAX_GROUPS];
    private Object world;
    private long last;

    void configure(List<PackGraph.BiomeGroup> groups) {
        this.matchers.clear();
        for (PackGraph.BiomeGroup group : groups) {
            List<ResourceKey<Biome>> keys = new ArrayList<>();
            List<TagKey<Biome>> tags = new ArrayList<>();
            for (String id : group.biomes()) {
                if (id.startsWith("#")) {
                    tags.add(TagKey.create(Registries.BIOME, Identifier.parse(id.substring(1))));
                } else {
                    keys.add(ResourceKey.create(Registries.BIOME, Identifier.parse(id)));
                }
            }

            this.matchers.add(new Matcher(keys, tags, group.minY(), group.halfLife()));
        }

        this.world = null;
    }

    /** Updates the weights for the camera at the given block (biome) and eye height. */
    void update(Level level, BlockPos biomePos, double eyeY) {
        long now = System.nanoTime();
        boolean first = level != this.world;
        float delta = first ? 0.0F : (float)Math.min((now - this.last) * 1.0E-9, 1.0);
        this.world = level;
        this.last = now;
        Holder<Biome> biome = level == null ? null : level.getBiome(biomePos);
        for (int i = 0; i < this.matchers.size(); i++) {
            Matcher matcher = this.matchers.get(i);
            float target = biome != null && eyeY >= matcher.minY && matcher.matches(biome) ? 1.0F : 0.0F;
            if (first || matcher.halfLife <= 0.0F) {
                this.values[i] = target;
            } else {
                float alpha = 1.0F - (float)Math.exp(-Math.log(2.0) / matcher.halfLife * delta);
                this.values[i] += (target - this.values[i]) * alpha;
            }
        }
    }

    float value(int group) {
        return group < this.matchers.size() ? this.values[group] : 0.0F;
    }

    private record Matcher(List<ResourceKey<Biome>> keys, List<TagKey<Biome>> tags, float minY, float halfLife) {
        boolean matches(Holder<Biome> biome) {
            for (ResourceKey<Biome> key : this.keys) {
                if (biome.is(key)) {
                    return true;
                }
            }

            for (TagKey<Biome> tag : this.tags) {
                if (biome.is(tag)) {
                    return true;
                }
            }

            return false;
        }
    }
}
