package dev.horizon.shaders.render.shadow;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

public final class SodiumShadowTerrainPasses {
    private static final int CASCADE_COUNT = 4;
    private static final TerrainRenderPass[] SOLID = new TerrainRenderPass[4];
    private static final TerrainRenderPass[] CUTOUT = new TerrainRenderPass[4];
    private static final TerrainRenderPass[] COMBINED = new TerrainRenderPass[4];
    // Translucent terrain for the colored shadow maps.
    private static final TerrainRenderPass[] TRANSLUCENT = new TerrainRenderPass[4];
    private static final TerrainRenderPass[] EMPTY = new TerrainRenderPass[0];

    private SodiumShadowTerrainPasses() {
    }

    public static TerrainRenderPass solid(int cascade) {
        return SOLID[cascade];
    }

    public static TerrainRenderPass cutout(int cascade) {
        return CUTOUT[cascade];
    }

    public static TerrainRenderPass translucent(int cascade) {
        return TRANSLUCENT[cascade];
    }

    public static TerrainRenderPass combined(int cascade) {
        return COMBINED[cascade];
    }

    public static boolean isCombined(TerrainRenderPass pass) {
        for (TerrainRenderPass combined : COMBINED) {
            if (pass == combined) {
                return true;
            }
        }

        return false;
    }

    public static TerrainRenderPass[] terrainPasses(int cascade, int activeCascadeCount) {
        return cascade > 0 && cascade == activeCascadeCount - 1
            ? new TerrainRenderPass[]{COMBINED[cascade]}
            : new TerrainRenderPass[]{SOLID[cascade], CUTOUT[cascade]};
    }

    public static TerrainRenderPass source(TerrainRenderPass pass) {
        for (int cascade = 0; cascade < 4; cascade++) {
            if (pass == SOLID[cascade] || pass == COMBINED[cascade]) {
                return DefaultTerrainRenderPasses.SOLID;
            }

            if (pass == CUTOUT[cascade]) {
                return DefaultTerrainRenderPasses.CUTOUT;
            }

            if (pass == TRANSLUCENT[cascade]) {
                return DefaultTerrainRenderPasses.TRANSLUCENT;
            }
        }

        return pass;
    }

    public static TerrainRenderPass[] passesFor(TerrainRenderPass source) {
        if (source == DefaultTerrainRenderPasses.SOLID) {
            return new TerrainRenderPass[]{SOLID[0], SOLID[1], SOLID[2], SOLID[3], COMBINED[0], COMBINED[1], COMBINED[2], COMBINED[3]};
        } else {
            if (source == DefaultTerrainRenderPasses.TRANSLUCENT) {
                return TRANSLUCENT.clone();
            }

            return source == DefaultTerrainRenderPasses.CUTOUT
                ? new TerrainRenderPass[]{CUTOUT[0], CUTOUT[1], CUTOUT[2], CUTOUT[3], COMBINED[0], COMBINED[1], COMBINED[2], COMBINED[3]}
                : EMPTY;
        }
    }

    static {
        for (int cascade = 0; cascade < 4; cascade++) {
            SOLID[cascade] = new TerrainRenderPass(ChunkSectionLayer.SOLID, false, false);
            CUTOUT[cascade] = new TerrainRenderPass(ChunkSectionLayer.CUTOUT, false, true);
            COMBINED[cascade] = new TerrainRenderPass(ChunkSectionLayer.SOLID, false, true);
            TRANSLUCENT[cascade] = new TerrainRenderPass(ChunkSectionLayer.TRANSLUCENT, true, true);
        }
    }
}
