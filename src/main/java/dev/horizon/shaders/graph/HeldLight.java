package dev.horizon.shaders.graph;

import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public final class HeldLight {
    public static final float RADIUS = 12.0F;
    static final int BYTES = 32;
    private Vec3 position = Vec3.ZERO;
    private int emission;
    private boolean cool;

    public boolean active() {
        return this.emission > 0;
    }

    public Vec3 position() {
        return this.position;
    }

    static int emission(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }

        if (stack.getItem() instanceof BlockItem block) {
            int light = block.getBlock().defaultBlockState().getLightEmission();
            if (light > 0) {
                return light;
            }
        }

        if (stack.is(Items.LAVA_BUCKET)) {
            return 15;
        } else if (!stack.is(Items.BLAZE_ROD) && !stack.is(Items.BLAZE_POWDER)) {
            return !stack.is(Items.GLOW_INK_SAC) && !stack.is(Items.GLOW_BERRIES) ? 0 : 8;
        } else {
            return 10;
        }
    }

    void update(ClientLevel level, float partialTick, boolean enabled) {
        this.emission = 0;
        LocalPlayer player = level != null && enabled ? Minecraft.getInstance().player : null;
        if (player != null && !player.isSpectator()) {
            ItemStack main = player.getMainHandItem();
            ItemStack off = player.getOffhandItem();
            ItemStack selected = emission(main) >= emission(off) ? main : off;
            this.emission = emission(selected);
            if (this.emission != 0) {
                this.cool = selected.is(Items.SOUL_TORCH) || selected.is(Items.SOUL_LANTERN) || selected.is(Items.SOUL_CAMPFIRE);
                float yaw = (float)Math.toRadians(player.getViewYRot(partialTick));
                float side = (selected == main ? 1 : -1) * (player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1);
                this.position = player.getEyePosition(partialTick)
                    .add(-Math.cos(yaw) * side * 0.45 - Math.sin(yaw) * 0.2, -0.35, -Math.sin(yaw) * side * 0.45 + Math.cos(yaw) * 0.2);
            }
        }
    }

    void write(ByteBuffer bytes, Vec3 camera) {
        bytes.putFloat((float)(this.position.x - camera.x))
            .putFloat((float)(this.position.y - camera.y))
            .putFloat((float)(this.position.z - camera.z))
            .putFloat(this.emission == 0 ? 0.0F : 12.0F);
        float strength = this.emission / 15.0F;
        bytes.putFloat(strength * (this.cool ? 0.25F : 1.35F))
            .putFloat(strength * (this.cool ? 0.85F : 0.76F))
            .putFloat(strength * (this.cool ? 1.3F : 0.34F))
            .putFloat(0.0F);
    }
}
