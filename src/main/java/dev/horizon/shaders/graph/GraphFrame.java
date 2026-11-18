package dev.horizon.shaders.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import dev.horizon.shaders.render.sky.CustomCelestials;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

final class GraphFrame {
    static final String MEMBERS = "mat4 Projection; mat4 View; mat4 InverseProjection; mat4 InverseView;\nmat4 PreviousProjection; mat4 PreviousView;\nvec4 CameraDeltaAndHistoryValid; vec4 TimeDeltaFrame; vec4 ViewSizeAndInverse;\nvec4 WorldTimeWeatherDimension; vec4 SunDirectionAndRainBrightness; vec4 MoonDirectionAndPhase;\nvec4 CameraPositionHighAndFogType; vec4 CameraPositionLowAndFarPlane;\nvec4 FogColorAndStart; vec4 FogDistances; vec4 SkyColorAndStarBrightness;\nvec4 CloudOffsetAndGameTime;\nmat4 InverseHandProjection; vec4 HandProjectionValid;\n      vec4 HeldLightPositionRadius; vec4 HeldLightColor;\nvec4 BiomeGroups[4]; vec4 Effects; vec4 EndFlashDirectionAndIntensity; vec4 RenderDistanceAndPrecipitation;\n";
    // The block's size in bytes. Members are only ever appended, so packs that declare an older copy of the block keep
    // working.
    static final int SIZE = 800;
    // Shader time counts from the game's start, so a pack reload, a resize or a new world doesn't restart animations.
    private static final long START = System.nanoTime();
    private final Matrix4f previousProjection = new Matrix4f();
    private final Matrix4f previousView = new Matrix4f();
    private final ByteBuffer bytes = ByteBuffer.allocateDirect(SIZE).order(ByteOrder.nativeOrder());
    private final float[] environment = new float[36];
    private float partialTick;
    final HeldLight heldLight = new HeldLight();
    boolean heldLighting;
    private Matrix4f worldProjection;
    private Matrix4f inverseHandProjection;
    private int cloudTextureWidth = 256;
    private Object world;
    private double x;
    private double y;
    private double z;
    private long lastTime;
    private final BiomeWeights biomeWeights = new BiomeWeights();
    private final float[] extras = new float[12];
    private int frame;
    private boolean valid;
    private boolean frameReset;

    static String declaration() {
        return "layout(std140) uniform SulkanFrame {\n" + MEMBERS + "};\n";
    }

    static boolean cameraReady(CameraRenderState camera, Matrix4fc view) {
        return camera != null
            && camera.pos != null
            && camera.projectionMatrix != null
            && matrixReady(camera.projectionMatrix)
            && (view == null || matrixReady(view));
    }

    private static boolean matrixReady(Matrix4fc matrix) {
        return matrix.isFinite() && Math.abs(matrix.determinant()) >= 1.0E-12F;
    }

    void handProjection(Matrix4fc projection) {
        this.inverseHandProjection = projection != null && matrixReady(projection) ? new Matrix4f(projection).invert() : null;
    }

    Matrix4fc inverseHandProjection() {
        return this.inverseHandProjection;
    }

    void projection(Matrix4fc projection) {
        this.inverseHandProjection = null;
        this.worldProjection = projection == null ? null : new Matrix4f(projection);
    }

    Matrix4fc projection(CameraRenderState camera) {
        return this.worldProjection != null ? this.worldProjection : camera.projectionMatrix;
    }

    void cloudTextureWidth(int width) {
        this.cloudTextureWidth = width;
    }

    static float cloudOffset(long ticks, float partialTick) {
        return cloudOffset(ticks, partialTick, 256);
    }

    static float cloudOffset(long ticks, float partialTick, int width) {
        return ((float)(ticks % (width * 400L)) + partialTick) * 0.030000001F;
    }

    void environment(LevelRenderState state, ClientLevel level, float partialTick) {
        Arrays.fill(this.environment, 0.0F);
        this.partialTick = partialTick;
        this.heldLight.update(level, partialTick, this.heldLighting);
        if (level != null) {
            this.environment[0] = (float)Math.floorMod(level.getDefaultClockTime(), 24000L) + partialTick;
            this.environment[1] = level.getRainLevel(partialTick);
            this.environment[2] = level.getThunderLevel(partialTick);
            this.environment[3] = level.dimension().equals(Level.NETHER)
                ? -1.0F
                : (level.dimension().equals(Level.END) ? 1.0F : (level.dimension().equals(Level.OVERWORLD) ? 0.0F : 2.0F));
        }

        if (state != null) {
            this.environment[32] = cloudOffset(state.gameTime, partialTick, this.cloudTextureWidth);
            this.environment[33] = state.cloudHeight;
            if (level != null && state.cameraRenderState != null && state.cameraRenderState.pos != null) {
                Vec3 eye = state.cameraRenderState.pos;
                double px = eye.x - 0.5;
                double py = eye.y - 0.5;
                double pz = eye.z - 0.5;
                int bx = (int)Math.floor(px);
                int by = (int)Math.floor(py);
                int bz = (int)Math.floor(pz);
                float fx = (float)(px - bx);
                float fy = (float)(py - by);
                float fz = (float)(pz - bz);

                for (int ix = 0; ix < 2; ix++) {
                    for (int iy = 0; iy < 2; iy++) {
                        for (int iz = 0; iz < 2; iz++) {
                            float weight = (ix == 0 ? 1.0F - fx : fx) * (iy == 0 ? 1.0F - fy : fy) * (iz == 0 ? 1.0F - fz : fz);
                            this.environment[34] = this.environment[34]
                                + weight * level.getBrightness(LightLayer.SKY, new BlockPos(bx + ix, by + iy, bz + iz)) / 15.0F;
                        }
                    }
                }
            }
        }

        this.extras(state, level, partialTick);
        if (state != null && state.skyRenderState != null) {
            SkyRenderState sky = state.skyRenderState;
            Vector3f direction = new Vector3f();
            CustomCelestials.setCelestialDirection(CustomCelestials.sunAngle(sky), direction);
            this.environment[4] = direction.x;
            this.environment[5] = direction.y;
            this.environment[6] = direction.z;
            this.environment[7] = sky.rainBrightness;
            CustomCelestials.setCelestialDirection(CustomCelestials.moonAngle(sky), direction);
            this.environment[8] = direction.x;
            this.environment[9] = direction.y;
            this.environment[10] = direction.z;
            this.environment[11] = sky.moonPhase == null ? 0.0F : sky.moonPhase.index();
            if (sky.skyColor != null) {
                this.environment[28] = sky.skyColor.x();
                this.environment[29] = sky.skyColor.y();
                this.environment[30] = sky.skyColor.z();
            }

            this.environment[31] = sky.starBrightness;
        }
    }

    void biomeGroups(java.util.List<PackGraph.BiomeGroup> groups) {
        this.biomeWeights.configure(groups);
    }

    // Biome weights, the camera entity's blindness, darkness and night vision, the End's flash, the render distance
    // and the weather at the camera.
    private void extras(LevelRenderState state, ClientLevel level, float partialTick) {
        Arrays.fill(this.extras, 0.0F);
        Minecraft client = Minecraft.getInstance();
        Entity entity = client.getCameraEntity();
        Vec3 eye = state != null && state.cameraRenderState != null ? state.cameraRenderState.pos : null;
        if (level != null && (entity != null || eye != null)) {
            BlockPos biomePos = entity != null ? entity.blockPosition() : BlockPos.containing(eye);
            this.biomeWeights.update(level, biomePos, eye != null ? eye.y : entity.getEyeY());
            BlockPos at = eye != null ? BlockPos.containing(eye) : biomePos;
            Biome.Precipitation precipitation = level.getBiome(at).value().getPrecipitationAt(at, level.getSeaLevel());
            this.extras[9] = precipitation == Biome.Precipitation.SNOW ? 2.0F : (precipitation == Biome.Precipitation.RAIN ? 1.0F : 0.0F);
        }

        if (entity instanceof LivingEntity living) {
            MobEffectInstance blindness = living.getEffect(MobEffects.BLINDNESS);
            if (blindness != null) {
                this.extras[0] = blindness.isInfiniteDuration() ? 1.0F : Math.clamp(blindness.getDuration() / 20.0F, 0.0F, 1.0F);
            }

            float darkness = living.getEffectBlendFactor(MobEffects.DARKNESS, partialTick);
            this.extras[1] = darkness;
            float gamma = darkness * client.options.darknessEffectScale().get().floatValue();
            this.extras[2] = Math.max(0.0F, Mth.cos((living.tickCount - partialTick) * (float)Math.PI * 0.025F) * 0.45F * gamma);
            this.extras[3] = living.hasEffect(MobEffects.NIGHT_VISION) ? net.minecraft.client.renderer.GameRenderer.nightVisionScale(living, partialTick) : 0.0F;
        }

        if (state != null && state.skyRenderState != null && state.skyRenderState.endFlashIntensity > 0.0F) {
            // Vanilla draws the flash at (0, 100, 0) turned by 180 - yAngle about Y, then -90 - xAngle about X.
            SkyRenderState sky = state.skyRenderState;
            double pitch = Math.toRadians(-90.0F - sky.endFlashXAngle);
            double yaw = Math.toRadians(180.0F - sky.endFlashYAngle);
            this.extras[4] = (float)(Math.sin(pitch) * Math.sin(yaw));
            this.extras[5] = (float)Math.cos(pitch);
            this.extras[6] = (float)(Math.sin(pitch) * Math.cos(yaw));
            this.extras[7] = sky.endFlashIntensity;
        }

        this.extras[8] = client.options.getEffectiveRenderDistance() * 16.0F;
    }

    void reset() {
        this.valid = false;
        this.frame = 0;
    }

    boolean discontinuity(CameraRenderState camera, Object currentWorld, Matrix4fc view) {
        return this.globalDiscontinuity(camera, currentWorld)
            || this.projectionChanged(this.projection(camera))
            || view != null && view.m02() * this.previousView.m02() + view.m12() * this.previousView.m12() + view.m22() * this.previousView.m22() < 0.5F;
    }

    boolean globalDiscontinuity(CameraRenderState camera, Object currentWorld) {
        return !this.valid
            || this.world != currentWorld
            || camera == null
            || camera.pos == null
            || Math.pow(camera.pos.x - this.x, 2.0) + Math.pow(camera.pos.y - this.y, 2.0) + Math.pow(camera.pos.z - this.z, 2.0) > 256.0
            || System.nanoTime() - this.lastTime > 1000000000L;
    }

    GpuBufferSlice upload(CommandEncoder encoder, CameraRenderState camera, Matrix4fc view, int width, int height, boolean reset) {
        this.frameReset = reset;
        Matrix4f projection = camera != null && camera.projectionMatrix != null ? new Matrix4f(this.projection(camera)) : new Matrix4f();
        Matrix4f model = view == null ? new Matrix4f() : new Matrix4f(view);
        if (matrixReady(projection) && matrixReady(model)) {
            projection.get(0, this.bytes);
            model.get(64, this.bytes);
            new Matrix4f(projection).invert().get(128, this.bytes);
            new Matrix4f(model).invert().get(192, this.bytes);
            (reset ? projection : this.previousProjection).get(256, this.bytes);
            (reset ? model : this.previousView).get(320, this.bytes);
            this.bytes.position(384);
            this.bytes.putFloat(!reset && camera != null ? (float)(camera.pos.x - this.x) : 0.0F);
            this.bytes.putFloat(!reset && camera != null ? (float)(camera.pos.y - this.y) : 0.0F);
            this.bytes.putFloat(!reset && camera != null ? (float)(camera.pos.z - this.z) : 0.0F);
            this.bytes.putFloat(reset ? 0.0F : 1.0F);
            long now = System.nanoTime();
            this.bytes
                .putFloat((float)((now - START) * 1.0E-9))
                .putFloat(reset ? 0.0F : (float)(now - this.lastTime) * 1.0E-9F)
                .putFloat(reset ? 0.0F : this.frame)
                .putFloat(this.partialTick);
            this.bytes.putFloat(width).putFloat(height).putFloat(1.0F / width).putFloat(1.0F / height);
            if (camera != null) {
                if (camera.pos != null) {
                    this.environment[12] = (float)camera.pos.x;
                    this.environment[13] = (float)camera.pos.y;
                    this.environment[14] = (float)camera.pos.z;
                    this.environment[16] = (float)(camera.pos.x - this.environment[12]);
                    this.environment[17] = (float)(camera.pos.y - this.environment[13]);
                    this.environment[18] = (float)(camera.pos.z - this.environment[14]);
                }

                float var10002;
                if (camera.fogType == null) {
                    var10002 = 0.0F;
                } else {
                    switch (camera.fogType) {
                        case WATER:
                            var10002 = 1.0F;
                            break;
                        case LAVA:
                            var10002 = 2.0F;
                            break;
                        case POWDER_SNOW:
                            var10002 = 3.0F;
                            break;
                        default:
                            var10002 = 0.0F;
                    }
                }

                this.environment[15] = var10002;
                this.environment[19] = camera.depthFar;
                if (camera.fogData != null) {
                    FogData fog = camera.fogData;
                    if (fog.color != null) {
                        this.environment[20] = fog.color.x;
                        this.environment[21] = fog.color.y;
                        this.environment[22] = fog.color.z;
                    }

                    this.environment[23] = fog.environmentalStart;
                    this.environment[24] = fog.environmentalEnd;
                    this.environment[25] = fog.renderDistanceStart;
                    this.environment[26] = fog.renderDistanceEnd;
                    this.environment[27] = fog.skyEnd;
                }
            }

            for (float value : this.environment) {
                this.bytes.putFloat(value);
            }

            (this.inverseHandProjection == null ? new Matrix4f() : this.inverseHandProjection).get(576, this.bytes);
            this.bytes.position(640);
            this.bytes.putFloat(this.inverseHandProjection == null ? 0.0F : 1.0F).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
            this.heldLight.write(this.bytes, camera != null && camera.pos != null ? camera.pos : Vec3.ZERO);
            for (int i = 0; i < BiomeWeights.MAX_GROUPS; i++) {
                this.bytes.putFloat(this.biomeWeights.value(i));
            }

            for (float value : this.extras) {
                this.bytes.putFloat(value);
            }

            this.bytes.flip();
            return encoder.transientMemory().uploadGpu(this.bytes, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
        } else {
            throw new IllegalArgumentException("Invalid frame matrices");
        }
    }

    void commit(CameraRenderState camera, Matrix4fc view, Object currentWorld) {
        if (camera != null && camera.projectionMatrix != null) {
            this.previousProjection.set(this.projection(camera));
        } else {
            this.previousProjection.identity();
        }

        if (view != null) {
            this.previousView.set(view);
        } else {
            this.previousView.identity();
        }

        if (camera != null && camera.pos != null) {
            this.x = camera.pos.x;
            this.y = camera.pos.y;
            this.z = camera.pos.z;
        }

        this.world = currentWorld;
        this.valid = true;
        this.frame = this.frameReset ? 1 : this.frame + 1;
        this.lastTime = System.nanoTime();
    }

    private boolean projectionChanged(Matrix4fc projection) {
        return projection != null
            && (
                Math.abs(projection.m00() - this.previousProjection.m00()) > Math.max(0.001F, Math.abs(this.previousProjection.m00()) * 0.05F)
                    || Math.abs(projection.m11() - this.previousProjection.m11()) > Math.max(0.001F, Math.abs(this.previousProjection.m11()) * 0.05F)
            );
    }
}
