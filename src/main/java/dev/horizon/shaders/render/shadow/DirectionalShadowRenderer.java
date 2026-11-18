package dev.horizon.shaders.render.shadow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.RenderTargetDescriptor.TextureProperties;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.DeviceLimits;
import dev.horizon.shaders.config.ShaderQualityPreset;
import dev.horizon.shaders.graph.NativePackRuntime;
import dev.horizon.shaders.graph.PackGraph;
import dev.horizon.shaders.render.sky.CustomCelestials;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

public final class DirectionalShadowRenderer {
    private static final int CASCADE_COUNT = 4;
    private static final float CELESTIAL_FADE_START = 0.02F;
    private static final float CELESTIAL_FADE_END = 0.2F;
    private static final float SHADOW_STRENGTH = 1.0F;
    private static final int[] CASCADE_UPDATE_INTERVALS = new int[]{3, 8, 16, 32};
    private static final double[] CASCADE_MOVEMENT_LIMITS = new double[]{1.5, 4.0, 12.0, 24.0};
    private static final int[] TERRAIN_CHANGE_INTERVALS = new int[]{1, 2, 4, 8};
    private static final float NEAR_PLANE = 0.1F;
    private static final float MAX_NORMAL_OFFSET = 0.02F;
    private static final int CASCADE_UBO_BYTES = 144;
    private static final int SHADOW_DATA_BYTES = 416;
    // The cascade being drawn and by which thread; any other thread (chunk builders) sees none, as with the ThreadLocals
    // these replace (asked on every draw, a ThreadLocal lookup cost more than the checks around it).
    private static Thread activeThread;
    private static int activeCascadeIndex = -1;
    private static boolean activeCachePass;
    private static boolean activeTranslucent;

    private static boolean onActiveThread() {
        return Thread.currentThread() == activeThread;
    }

    private static void activate(int cascade, boolean cachePass, boolean translucent) {
        activeThread = Thread.currentThread();
        activeCascadeIndex = cascade;
        activeCachePass = cachePass;
        activeTranslucent = translucent;
    }
    /** The colored shadow maps' format: transmittance in rgb, the nearest translucent caster's depth in alpha. */
    public static final GpuFormat COLORED_FORMAT = GpuFormat.RGBA16_UNORM;
    private static final int COLORED_MAX_SIZE = 2048;
    private final RenderTarget[] translucentTargets = new RenderTarget[4];
    private final int[] translucentSizes = new int[4];
    private final boolean[] translucentStale = new boolean[4];
    private Object translucentSettings;
    private static DirectionalShadowRenderer instance;
    private final RenderTarget[] targets = new RenderTarget[4];
    private RenderTarget staticCache;
    private int staticCacheSize;
    private RenderTarget placeholder;
    private boolean animatedCasters;
    private boolean cacheHasCutout;
    private boolean compositeHasDynamic;
    private final Matrix4f[] cascadeMatrices = new Matrix4f[4];
    private final Matrix4f[] renderedCascadeMatrices = new Matrix4f[4];
    private final Vector3f[] frustumCorners = new Vector3f[8];
    private final Vector3f[] renderedLightDirections = new Vector3f[4];
    private final Vector3f[] renderedCameraForwards = new Vector3f[4];
    private final DirectionalShadowRenderer.FitBox[] renderedBoxes = new DirectionalShadowRenderer.FitBox[4];
    private final DirectionalShadowRenderer.FitBox pendingBox = new DirectionalShadowRenderer.FitBox();
    private final float[] cascadeEnds = new float[4];
    private final float[] cascadeTexelWorldSizes = new float[4];
    private final float[] cascadeDepthRanges = new float[4];
    private final int[] targetSizes = new int[4];
    private final boolean[] cascadeUpdates = new boolean[4];
    private final boolean[] cascadeInitialized = new boolean[4];
    private final long[] cascadeLayoutVersions = new long[4];
    private final double[] renderedCameraX = new double[4];
    private final double[] renderedCameraY = new double[4];
    private final double[] renderedCameraZ = new double[4];
    private final long[] renderedFrameSerial = new long[4];
    private final long[] renderedTerrainRevision = new long[4];
    private final long[] terrainRevisions = new long[4];
    private final ByteBuffer cascadeUpload = ByteBuffer.allocateDirect(144).order(ByteOrder.nativeOrder());
    private final ByteBuffer shadowUpload = ByteBuffer.allocateDirect(416).order(ByteOrder.nativeOrder());
    private GpuBufferSlice cascadeSlice;
    private GpuBufferSlice shadowDataSlice;
    private int activeCascadeCount;
    private final Vector3f lightDirection = new Vector3f(0.0F, -1.0F, 0.0F);
    private final Vector3f sunDirection = new Vector3f();
    private final Vector3f moonDirection = new Vector3f();
    private final Vector3f cameraForward = new Vector3f(0.0F, 0.0F, -1.0F);
    private final Matrix4f inverseViewRotation = new Matrix4f();
    private final Matrix4f lastProjection = new Matrix4f().zero();
    private long frameSerial;
    private float celestialShadowFade = 1.0F;
    private float lastCoverage = -1.0F;
    private int lastActiveCascadeCount = -1;
    private double cameraX;
    private double cameraY;
    private double cameraZ;

    private DirectionalShadowRenderer() {
        for (int i = 0; i < 4; i++) {
            this.cascadeMatrices[i] = new Matrix4f();
            this.renderedCascadeMatrices[i] = new Matrix4f();
            this.renderedLightDirections[i] = new Vector3f();
            this.renderedCameraForwards[i] = new Vector3f();
            this.renderedBoxes[i] = new DirectionalShadowRenderer.FitBox();
        }

        for (int i = 0; i < this.frustumCorners.length; i++) {
            this.frustumCorners[i] = new Vector3f();
        }
    }

    public static DirectionalShadowRenderer get() {
        if (instance == null) {
            instance = new DirectionalShadowRenderer();
        }

        return instance;
    }

    public static void markSectionDirty(int sectionX, int sectionY, int sectionZ) {
        if (instance != null) {
            for (int cascade = 0; cascade < 4; cascade++) {
                if (!instance.cascadeInitialized[cascade] || instance.sectionIntersectsCascade(cascade, sectionX << 4, sectionY << 4, sectionZ << 4)) {
                    instance.terrainRevisions[cascade]++;
                }
            }
        }
    }

    public static void markAllTerrainDirty() {
        if (instance != null) {
            for (int cascade = 0; cascade < 4; cascade++) {
                instance.terrainRevisions[cascade]++;
            }
        }
    }

    public static boolean isRenderingShadowMap() {
        return activeCascadeIndex >= 0 && onActiveThread();
    }

    public static int activeCascade() {
        return onActiveThread() ? activeCascadeIndex : -1;
    }

    public static void beginCascade(int cascade) {
        activate(cascade, false, onActiveThread() && activeTranslucent);
    }

    /** Draws translucent terrain into the cascade's colored shadow map (see {@link #translucentTarget}). */
    public static void beginTranslucent(int cascade) {
        activate(cascade, false, true);
    }

    public static void beginStaticCache(int cascade) {
        activate(cascade, true, onActiveThread() && activeTranslucent);
    }

    public static void endCascade() {
        if (onActiveThread()) {
            activeCascadeIndex = -1;
            activeCachePass = false;
            activeTranslucent = false;
            activeThread = null;
        }
    }

    public void prepare(LevelRenderState levelRenderState) {
        ShaderQualityPreset quality = ShadowService.quality();
        if (quality.enabled() && levelRenderState != null && levelRenderState.cameraRenderState != null) {
            int nextActiveCascadeCount = switch (quality) {
                case LOW -> 1;
                case MEDIUM -> 3;
                case HIGH, ULTRA -> 4;
                case OFF -> 0;
            };
            DeviceLimits limits = RenderSystem.getDevice().getDeviceInfo().limits();
            int maxShadowSize = Math.min(limits.maxTextureSizeForFormat(GpuFormat.R8_UNORM), limits.maxTextureSizeForFormat(GpuFormat.D32_FLOAT));
            int[] sizes = targetSizes(quality, maxShadowSize);
            boolean resourcesChanged = this.ensureResources(sizes);
            this.ensureTranslucent(NativePackRuntime.translucentShadows(), NativePackRuntime.shadowSkipMaterials(), sizes);
            this.activeCascadeCount = nextActiveCascadeCount;
            this.frameSerial++;
            if (levelRenderState.skyRenderState != null) {
                CustomCelestials.setCelestialDirection(CustomCelestials.sunAngle(levelRenderState.skyRenderState), this.sunDirection);
                CustomCelestials.setCelestialDirection(CustomCelestials.moonAngle(levelRenderState.skyRenderState), this.moonDirection);
                this.celestialShadowFade = smoothstep(0.02F, 0.2F, Math.abs(this.sunDirection.y - this.moonDirection.y));
                this.lightDirection.set(this.sunDirection.y >= this.moonDirection.y ? this.sunDirection : this.moonDirection);
                if (this.lightDirection.y < 0.08F) {
                    this.lightDirection.y = 0.08F;
                }

                this.lightDirection.normalize();
            } else {
                this.lightDirection.set(-0.35F, 0.82F, -0.44F).normalize();
                this.celestialShadowFade = 1.0F;
            }

            if (levelRenderState.cameraRenderState.viewRotationMatrix != null) {
                this.inverseViewRotation.set(levelRenderState.cameraRenderState.viewRotationMatrix).invert();
                this.cameraForward.set(0.0F, 0.0F, -1.0F);
                this.inverseViewRotation.transformDirection(this.cameraForward).normalize();
            } else {
                this.inverseViewRotation.identity();
                this.cameraForward.set(0.0F, 0.0F, -1.0F);
            }

            if (levelRenderState.cameraRenderState.pos != null) {
                this.cameraX = levelRenderState.cameraRenderState.pos.x;
                this.cameraY = levelRenderState.cameraRenderState.pos.y;
                this.cameraZ = levelRenderState.cameraRenderState.pos.z;
            } else {
                this.cameraX = 0.0;
                this.cameraY = 0.0;
                this.cameraZ = 0.0;
            }

            float coverage = Math.min(ShadowService.distance(), Math.max(64.0F, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0F));
            boolean layoutChanged = resourcesChanged
                || !this.lastProjection.equals(levelRenderState.cameraRenderState.projectionMatrix, 1.0E-4F)
                || this.lastActiveCascadeCount != this.activeCascadeCount
                || Math.abs(this.lastCoverage - coverage) > 0.5F;
            this.lastProjection.set(levelRenderState.cameraRenderState.projectionMatrix);
            this.lastActiveCascadeCount = this.activeCascadeCount;
            this.lastCoverage = coverage;
            float[] splits = splitDistances(coverage, this.activeCascadeCount);
            Matrix4f inverseProjection = new Matrix4f(levelRenderState.cameraRenderState.projectionMatrix).invert();
            this.animatedCasters = NativePackRuntime.animatedShadowCasters();
            boolean farCascadeUpdated = false;
            Vector3f sliceCenter = new Vector3f();

            for (int i = 0; i < 4; i++) {
                float end = i < this.activeCascadeCount ? splits[i] : 0.0F;
                if (i < this.activeCascadeCount) {
                    float start = i == 0 ? 0.5F : splits[i - 1] * 0.82F;
                    float sliceRadius = this.frustumSliceSphere(inverseProjection, start, end, sliceCenter);
                    boolean uncovered = !this.covers(i, sliceCenter, sliceRadius);
                    boolean update = uncovered || this.shouldUpdateCascade(i, layoutChanged);
                    if (update && i > 0 && farCascadeUpdated && !layoutChanged && !uncovered && !this.urgentCascadeUpdate(i)) {
                        update = false;
                    }

                    boolean redraw = update;
                    if (update) {
                        boolean terrainChanged = this.renderedTerrainRevision[i] != this.terrainRevisions[i];
                        this.cascadeEnds[i] = end;
                        Matrix4f previousFit = new Matrix4f(this.renderedCascadeMatrices[i])
                            .translate(
                                (float)(this.cameraX - this.renderedCameraX[i]),
                                (float)(this.cameraY - this.renderedCameraY[i]),
                                (float)(this.cameraZ - this.renderedCameraZ[i])
                            );
                        Matrix4f fit = this.fitCascadeMatrix(i, sliceCenter, sliceRadius, start, end, this.pendingBox, new Matrix4f());
                        boolean fitChanged = !this.cascadeInitialized[i] || !previousFit.equals(fit, 1.0E-6F);
                        if (fitChanged) {
                            this.cascadeLayoutVersions[i]++;
                        }

                        redraw = fitChanged || terrainChanged || layoutChanged;
                        if (redraw) {
                            this.renderedCascadeMatrices[i].set(fit);
                            this.renderedBoxes[i].set(this.pendingBox);
                            this.renderedLightDirections[i].set(this.pendingBox.light);
                            this.cascadeTexelWorldSizes[i] = this.pendingBox.texelSize;
                            this.cascadeDepthRanges[i] = this.pendingBox.lightwardReach + this.pendingBox.oppositeReach;
                            this.renderedCameraX[i] = this.cameraX;
                            this.renderedCameraY[i] = this.cameraY;
                            this.renderedCameraZ[i] = this.cameraZ;
                        }

                        this.renderedFrameSerial[i] = this.frameSerial;
                        this.renderedTerrainRevision[i] = this.terrainRevisions[i];
                        this.renderedCameraForwards[i].set(this.cameraForward);
                        this.cascadeInitialized[i] = true;
                    }

                    farCascadeUpdated |= redraw && i > 0;
                    this.cascadeUpdates[i] = redraw || i == 0 && this.cacheHasCutout == this.animatedCasters;
                    this.cascadeMatrices[i]
                        .set(this.renderedCascadeMatrices[i])
                        .translate(
                            (float)(this.cameraX - this.renderedCameraX[i]),
                            (float)(this.cameraY - this.renderedCameraY[i]),
                            (float)(this.cameraZ - this.renderedCameraZ[i])
                        );
                } else {
                    this.cascadeUpdates[i] = false;
                    this.cascadeInitialized[i] = false;
                    this.cascadeEnds[i] = 0.0F;
                    this.cascadeMatrices[i].identity();
                    this.renderedCascadeMatrices[i].identity();
                    this.cascadeTexelWorldSizes[i] = 0.0F;
                    this.cascadeDepthRanges[i] = 0.0F;
                }
            }
        } else {
            this.activeCascadeCount = 0;
        }
    }

    public int activeCascadeCount() {
        return this.activeCascadeCount;
    }

    public long cascadeLayoutVersion(int cascade) {
        return this.cascadeLayoutVersions[cascade];
    }

    public Matrix4fc cascadeMatrix(int cascade) {
        return this.cascadeMatrices[cascade];
    }

    public boolean shouldUpdateCascade(int cascade) {
        return cascade >= 0 && cascade < this.activeCascadeCount && this.cascadeUpdates[cascade];
    }

    public boolean shouldUpdateEntityCascade(int cascade) {
        return cascade == 0 && this.activeCascadeCount > 0 && ShadowService.entities();
    }

    public boolean animatedCasters() {
        return this.animatedCasters;
    }

    public boolean needsComposite(boolean staticUpdated, boolean dynamicCasters) {
        return staticUpdated || dynamicCasters || this.compositeHasDynamic;
    }

    public void markStaticCacheRendered() {
        this.cacheHasCutout = !this.animatedCasters;
    }

    public void markComposited(boolean dynamicCasters) {
        this.compositeHasDynamic = dynamicCasters;
    }

    public void clearStaticCache(CommandEncoder encoder) {
        if (this.staticCache != null && this.staticCache.getDepthTexture() != null) {
            encoder.clearDepthTexture(this.staticCache.getDepthTexture(), 1.0);
        }
    }

    public void copyStaticCache(CommandEncoder encoder) {
        RenderTarget target = this.targets[0];
        encoder.copyTextureToTexture(this.staticCache.getDepthTexture(), target.getDepthTexture(), 0, 0, 0, 0, 0, target.width, target.height);
    }

    public float cascadeEnd(int cascade) {
        return cascade >= 0 && cascade < this.activeCascadeCount ? this.cascadeEnds[cascade] : 0.0F;
    }

    public Vector3f lightDirection(Vector3f destination) {
        return destination.set(this.lightDirection);
    }

    public Vector3f cameraForward(Vector3f destination) {
        return destination.set(this.cameraForward);
    }

    public float shadowDistance() {
        return this.activeCascadeCount <= 0 ? 0.0F : this.cascadeEnds[this.activeCascadeCount - 1];
    }

    public float entityShadowDistance() {
        return this.activeCascadeCount <= 0 ? 0.0F : this.cascadeEnds[Math.min(1, this.activeCascadeCount) - 1];
    }

    public float entityCascadeEnd(int cascade) {
        return cascade >= 0 && cascade < Math.min(1, this.activeCascadeCount) ? this.cascadeEnds[cascade] : 0.0F;
    }

    public RenderTarget target(int cascade) {
        return cascade >= 0 && cascade < 4 ? this.targets[cascade] : null;
    }

    public RenderTarget entityTarget(int cascade) {
        return cascade == 0 ? this.targets[0] : (cascade == 1 ? this.placeholder : null);
    }

    public RenderTarget staticCache() {
        return this.staticCache;
    }

    public RenderTarget activeTarget() {
        if (onActiveThread() && activeTranslucent) {
            return this.translucentTarget(activeCascade());
        }

        return onActiveThread() && activeCachePass ? this.staticCache : this.target(activeCascade());
    }

    public RenderTarget translucentTarget(int cascade) {
        return cascade >= 0 && cascade < 4 ? this.translucentTargets[cascade] : null;
    }

    public boolean translucentReady() {
        for (RenderTarget target : this.translucentTargets) {
            if (target == null || target.getColorTextureView() == null) {
                return false;
            }
        }

        return true;
    }

    /** Whether the cascade's colored shadow map needs drawing this frame: its depth map does, or it was just made. */
    public boolean shouldUpdateTranslucent(int cascade) {
        return cascade >= 0
            && cascade < this.activeCascadeCount
            && this.translucentTargets[cascade] != null
            && (this.cascadeUpdates[cascade] || this.translucentStale[cascade]);
    }

    public void clearTranslucent(CommandEncoder encoder, int cascade) {
        RenderTarget target = this.translucentTargets[cascade];
        encoder.clearColorTexture(target.getColorTexture(), new Vector4f(1.0F));
        this.translucentStale[cascade] = false;
    }

    /** The colored maps are half the depth maps' size (at most 2048): colored shadows are soft enough for it. */
    public static int translucentSize(int size) {
        return size <= 1 ? 1 : Math.max(1, Math.min(COLORED_MAX_SIZE, size / 2));
    }

    private void ensureTranslucent(PackGraph.TranslucentShadows settings, java.util.List<Integer> skip, int[] sizes) {
        boolean enabled = settings != null;
        // Another pack (or other caster settings): draw the colored maps again.
        Object current = enabled ? java.util.List.of(settings, skip) : null;
        if (!java.util.Objects.equals(current, this.translucentSettings)) {
            this.translucentSettings = current;
            java.util.Arrays.fill(this.translucentStale, true);
        }

        boolean allocated = this.translucentTargets[0] != null;
        boolean same = allocated;
        for (int i = 0; i < 4; i++) {
            same &= this.translucentSizes[i] == translucentSize(sizes[i]);
        }

        if (enabled == allocated && (!enabled || same)) {
            return;
        }

        RenderTarget[] old = this.translucentTargets.clone();
        retireTargets(old);
        for (int i = 0; i < 4; i++) {
            this.translucentTargets[i] = null;
            this.translucentSizes[i] = 0;
        }

        if (enabled) {
            for (int i = 0; i < 4; i++) {
                int size = translucentSize(sizes[i]);
                RenderTargetDescriptor descriptor = new RenderTargetDescriptor(size, size, new TextureProperties(new Vector4f(1.0F), COLORED_FORMAT), null);
                this.translucentTargets[i] = descriptor.allocate();
                descriptor.prepare(this.translucentTargets[i]);
                this.translucentSizes[i] = size;
                this.translucentStale[i] = true;
            }
        }
    }

    public boolean sectionIntersectsCascade(int cascade, int originX, int originY, int originZ) {
        if (cascade >= 0 && cascade < this.activeCascadeCount) {
            float margin = cascade == 0 ? 0.18F : 0.12F;
            return intersectsSection(
                this.cascadeMatrices[cascade],
                (float)(originX + 8.0 - this.cameraX),
                (float)(originY + 8.0 - this.cameraY),
                (float)(originZ + 8.0 - this.cameraZ),
                margin
            );
        } else {
            return false;
        }
    }

    static boolean intersectsSection(Matrix4fc matrix, float x, float y, float z, float margin) {
        float extent = 8.25F;
        float cx = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
        float cy = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
        float cz = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
        float ex = extent * (Math.abs(matrix.m00()) + Math.abs(matrix.m10()) + Math.abs(matrix.m20()));
        float ey = extent * (Math.abs(matrix.m01()) + Math.abs(matrix.m11()) + Math.abs(matrix.m21()));
        float ez = extent * (Math.abs(matrix.m02()) + Math.abs(matrix.m12()) + Math.abs(matrix.m22()));
        return cx + ex >= -1.0F - margin && cx - ex <= 1.0F + margin && cy + ey >= -1.0F - margin && cy - ey <= 1.0F + margin && cz - ez <= 1.08F;
    }

    public void uploadCascade(CommandEncoder encoder, int cascade) {
        this.cascadeUpload.clear();
        this.cascadeMatrices[cascade].get(this.cascadeUpload);
        this.cascadeUpload.position(64);
        putVec4(this.lightDirection.x, this.lightDirection.y, this.lightDirection.z, 0.0F, this.cascadeUpload);
        this.cascadeUpload.position(80);
        this.inverseViewRotation.get(this.cascadeUpload);
        this.cascadeUpload.rewind();
        this.cascadeSlice = encoder.transientMemory()
            .uploadGpu(this.cascadeUpload, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
    }

    public GpuBufferSlice cascadeSlice() {
        return this.cascadeSlice;
    }

    public void uploadShadowData(CommandEncoder encoder) {
        this.shadowUpload.clear();

        for (int i = 0; i < 4; i++) {
            this.cascadeMatrices[i].get(this.shadowUpload);
            this.shadowUpload.position((i + 1) * 64);
        }

        for (int i = 0; i < 4; i++) {
            float depthRange = i < this.activeCascadeCount ? this.cascadeDepthRanges[i] : 0.0F;
            putVec4(this.cascadeTexelWorldSizes[i], i == 0 ? 0.0F : this.cascadeEnds[i - 1], this.cascadeEnds[i], depthRange, this.shadowUpload);
        }

        putVec4(this.lightDirection.x, this.lightDirection.y, this.lightDirection.z, 0.0F, this.shadowUpload);

        float filterSamples = switch (ShadowService.quality()) {
            case LOW -> 1.0F;
            case MEDIUM -> 4.0F;
            case HIGH -> 9.0F;
            case ULTRA -> 16.0F;
            case OFF -> 1.0F;
        };
        putVec4(1.0F * this.celestialShadowFade, 0.0F, 0.08F, filterSamples, this.shadowUpload);
        this.inverseViewRotation.get(this.shadowUpload);
        this.shadowUpload.rewind();
        this.shadowDataSlice = encoder.transientMemory()
            .uploadGpu(this.shadowUpload, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
    }

    public GpuBufferSlice shadowDataSlice() {
        return this.shadowDataSlice;
    }

    public boolean resourcesReady() {
        if (this.shadowDataSlice == null) {
            return false;
        }

        for (RenderTarget target : this.targets) {
            if (target == null || target.getDepthTextureView() == null) {
                return false;
            }
        }

        return this.staticCache != null
            && this.staticCache.getDepthTextureView() != null
            && this.placeholder != null
            && this.placeholder.getDepthTextureView() != null;
    }

    public void clearCascade(CommandEncoder encoder, int cascade) {
        RenderTarget target = this.target(cascade);
        if (target != null && target.getDepthTexture() != null) {
            encoder.clearDepthTexture(target.getDepthTexture(), 1.0);
        }
    }

    private boolean ensureResources(int[] sizes) {
        boolean sameSizes = this.targets[0] != null && this.staticCache != null && this.staticCacheSize == sizes[0];

        for (int i = 0; i < 4; i++) {
            sameSizes &= this.targetSizes[i] == sizes[i];
        }

        if (sameSizes) {
            return false;
        }

        RenderTarget[] next = new RenderTarget[6];

        try {
            for (int i = 0; i < 4; i++) {
                next[i] = allocateDepth(sizes[i]);
            }

            next[4] = allocateDepth(sizes[0]);
            next[5] = allocateDepth(1);
        } catch (RuntimeException failure) {
            retireTargets(next);
            throw failure;
        }

        RenderTarget[] old = new RenderTarget[]{this.targets[0], this.targets[1], this.targets[2], this.targets[3], this.staticCache, this.placeholder};
        retireTargets(old);
        System.arraycopy(next, 0, this.targets, 0, 4);
        this.staticCache = next[4];
        this.placeholder = next[5];
        this.staticCacheSize = sizes[0];
        this.compositeHasDynamic = false;
        System.arraycopy(sizes, 0, this.targetSizes, 0, 4);
        return true;
    }

    private static RenderTarget allocateDepth(int size) {
        RenderTargetDescriptor descriptor = new RenderTargetDescriptor(size, size, null, TextureProperties.DEFAULT_DEPTH);
        RenderTarget target = descriptor.allocate();
        descriptor.prepare(target);
        return target;
    }

    private static void retireTargets(RenderTarget[] targets) {
        RenderSystem.queueFencedTask(() -> {
            for (RenderTarget target : targets) {
                if (target != null) {
                    target.destroyBuffers();
                }
            }
        });
    }

    public static void retireUnused() {
        if (instance != null && !ShadowService.entities() && NativePackRuntime.shadowQuality() <= 0) {
            DirectionalShadowRenderer old = instance;
            instance = null;
            RenderSystem.queueFencedTask(() -> old.destroyTargets());
        }
    }

    private boolean shouldUpdateCascade(int cascade, boolean force) {
        if (!force && this.cascadeInitialized[cascade]) {
            if (this.renderedTerrainRevision[cascade] != this.terrainRevisions[cascade]
                && this.frameSerial - this.renderedFrameSerial[cascade] >= TERRAIN_CHANGE_INTERVALS[cascade]) {
                return true;
            }

            int interval = CASCADE_UPDATE_INTERVALS[cascade];
            return this.frameSerial - this.renderedFrameSerial[cascade] >= interval || this.urgentCascadeUpdate(cascade);
        } else {
            return true;
        }
    }

    private boolean urgentCascadeUpdate(int cascade) {
        if (!this.cascadeInitialized[cascade]) {
            return true;
        }

        double dx = this.cameraX - this.renderedCameraX[cascade];
        double dy = this.cameraY - this.renderedCameraY[cascade];
        double dz = this.cameraZ - this.renderedCameraZ[cascade];
        double movementLimit = CASCADE_MOVEMENT_LIMITS[cascade];
        return dx * dx + dy * dy + dz * dz >= movementLimit * movementLimit
            || this.renderedLightDirections[cascade].dot(this.lightDirection) < 0.9998F
            || this.renderedCameraForwards[cascade].dot(this.cameraForward) < 0.9659F;
    }

    private float frustumSliceSphere(Matrix4f inverseProjection, float start, float end, Vector3f center) {
        this.buildFrustumSlice(inverseProjection, start, end);
        center.zero();

        for (Vector3f corner : this.frustumCorners) {
            center.add(corner);
        }

        center.div(this.frustumCorners.length);
        float radius = 0.0F;

        for (Vector3f corner : this.frustumCorners) {
            radius = Math.max(radius, center.distance(corner));
        }

        return radius;
    }

    private boolean covers(int cascade, Vector3f center, float sphereRadius) {
        if (!this.cascadeInitialized[cascade]) {
            return false;
        }

        DirectionalShadowRenderer.FitBox box = this.renderedBoxes[cascade];
        Vector3f offset = new Vector3f(
            (float)(this.cameraX - box.centerX) + center.x, (float)(this.cameraY - box.centerY) + center.y, (float)(this.cameraZ - box.centerZ) + center.z
        );
        float lateral = box.halfSize - box.texelSize * 1.5F - 0.02F - sphereRadius;
        float towardLight = box.light.dot(offset);
        return Math.abs(box.right.dot(offset)) <= lateral
            && Math.abs(box.up.dot(offset)) <= lateral
            && towardLight + sphereRadius <= box.lightwardReach - 0.1F
            && sphereRadius - towardLight <= box.oppositeReach;
    }

    private Matrix4f fitCascadeMatrix(
        int cascade, Vector3f center, float sliceRadius, float start, float end, DirectionalShadowRenderer.FitBox box, Matrix4f destination
    ) {
        Vector3f fitLight = this.lightDirection;
        if (this.cascadeInitialized[cascade] && this.renderedLightDirections[cascade].distanceSquared(fitLight) < 1.0E-6F) {
            fitLight = this.renderedLightDirections[cascade];
        }

        float radius = sliceRadius + Math.max(2.0F, (end - start) * 0.03F);
        radius = (float)Math.ceil(radius + 0.001F);
        Vector3f up = Math.abs(fitLight.y) > 0.88F ? new Vector3f(0.0F, 0.0F, 1.0F) : new Vector3f(0.0F, 1.0F, 0.0F);
        Vector3f lightRight = new Vector3f(up).cross(fitLight).normalize();
        Vector3f lightUp = new Vector3f(fitLight).cross(lightRight).normalize();
        float centerX = lightRight.dot(center);
        float centerY = lightUp.dot(center);
        float texelSize = radius * 2.0F / Math.max(1, this.targetSizes[cascade]);
        double originX = lightRight.x * this.cameraX + lightRight.y * this.cameraY + lightRight.z * this.cameraZ;
        double originY = lightUp.x * this.cameraX + lightUp.y * this.cameraY + lightUp.z * this.cameraZ;
        centerX = snapWorldTexel(centerX, originX, texelSize);
        centerY = snapWorldTexel(centerY, originY, texelSize);
        float depthPadding = Math.max(32.0F, Math.min(128.0F, end * 0.35F));
        float lightwardPadding = cascade < 2 ? Math.max(depthPadding, this.lastCoverage) : depthPadding;
        float lightwardReach = radius + lightwardPadding;
        float oppositeReach = radius + depthPadding;
        float shadowDepth = lightwardReach + oppositeReach;
        Vector3f stableCenter = new Vector3f(center)
            .add(new Vector3f(lightRight).mul(centerX - lightRight.dot(center)))
            .add(new Vector3f(lightUp).mul(centerY - lightUp.dot(center)));
        box.centerX = this.cameraX + stableCenter.x;
        box.centerY = this.cameraY + stableCenter.y;
        box.centerZ = this.cameraZ + stableCenter.z;
        box.right.set(lightRight);
        box.up.set(lightUp);
        box.light.set(fitLight);
        box.halfSize = radius;
        box.lightwardReach = lightwardReach;
        box.oppositeReach = oppositeReach;
        box.texelSize = texelSize;
        Matrix4f lightView = stableLightView(stableCenter, fitLight, lightUp, lightwardReach);
        return destination.identity().ortho(-radius, radius, -radius, radius, 0.1F, shadowDepth, true).mul(lightView);
    }

    static Matrix4f stableLightView(Vector3f center, Vector3f direction, Vector3f up, float reach) {
        Vector3f eye = new Vector3f(direction).mul(reach).add(center);
        return new Matrix4f().lookAlong(new Vector3f(direction).negate(), up).translate(-eye.x, -eye.y, -eye.z);
    }

    static float snapWorldTexel(float relative, double origin, float texelSize) {
        return (float)(Math.floor((origin + relative) / texelSize + 0.5) * texelSize - origin);
    }

    private void buildFrustumSlice(Matrix4f inverseProjection, float start, float end) {
        int index = 0;

        for (float distance : new float[]{start, end}) {
            for (int y = -1; y <= 1; y += 2) {
                for (int x = -1; x <= 1; x += 2) {
                    Vector4f view = inverseProjection.transform(new Vector4f(x, y, 1.0F, 1.0F));
                    view.div(view.w);
                    Vector3f ray = new Vector3f(view.x, view.y, view.z).normalize().mul(distance);
                    this.inverseViewRotation.transformPosition(ray);
                    this.frustumCorners[index++].set(ray);
                }
            }
        }
    }

    private void destroyTargets() {
        for (int i = 0; i < 4; i++) {
            if (this.translucentTargets[i] != null) {
                this.translucentTargets[i].destroyBuffers();
                this.translucentTargets[i] = null;
            }

            this.translucentSizes[i] = 0;
        }

        for (RenderTarget target : new RenderTarget[]{this.staticCache, this.placeholder}) {
            if (target != null) {
                target.destroyBuffers();
            }
        }

        this.staticCache = null;
        this.placeholder = null;
        this.staticCacheSize = 0;

        for (int i = 0; i < 4; i++) {
            if (this.targets[i] != null) {
                this.targets[i].destroyBuffers();
                this.targets[i] = null;
            }

            this.targetSizes[i] = 0;
            this.cascadeTexelWorldSizes[i] = 0.0F;
            this.cascadeDepthRanges[i] = 0.0F;
        }
    }

    private static float[] splitDistances(float coverage, int count) {
        float[] result = new float[4];
        float near = 8.0F;

        for (int i = 0; i < count; i++) {
            float t = (float)(i + 1) / count;
            float logarithmic = near * (float)Math.pow(coverage / near, t);
            float uniform = near + (coverage - near) * t;
            result[i] = uniform * 0.35F + logarithmic * 0.65F;
        }

        return result;
    }

    static int[] targetSizes(ShaderQualityPreset quality, int maxSize) {
        int[] sizes = switch (quality) {
            case LOW -> new int[]{1536, 1, 1, 1};
            case MEDIUM -> new int[]{2048, 1536, 1024, 1};
            case HIGH -> new int[]{3072, 2048, 1536, 1024};
            case ULTRA -> new int[]{6144, 2048, 2048, 1024};
            case OFF -> new int[]{1, 1, 1, 1};
        };

        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = Math.min(sizes[i], Math.max(1, maxSize));
        }

        return sizes;
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0F, Math.min(1.0F, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0F - 2.0F * t);
    }

    private static void putVec4(float x, float y, float z, float w, ByteBuffer buffer) {
        buffer.putFloat(x);
        buffer.putFloat(y);
        buffer.putFloat(z);
        buffer.putFloat(w);
    }

    public static void close() {
        if (instance != null) {
            instance.destroyTargets();
            instance = null;
        }
    }

    private static final class FitBox {
        private double centerX;
        private double centerY;
        private double centerZ;
        private final Vector3f right = new Vector3f();
        private final Vector3f up = new Vector3f();
        private final Vector3f light = new Vector3f();
        private float halfSize;
        private float lightwardReach;
        private float oppositeReach;
        private float texelSize;

        private void set(DirectionalShadowRenderer.FitBox other) {
            this.centerX = other.centerX;
            this.centerY = other.centerY;
            this.centerZ = other.centerZ;
            this.right.set(other.right);
            this.up.set(other.up);
            this.light.set(other.light);
            this.halfSize = other.halfSize;
            this.lightwardReach = other.lightwardReach;
            this.oppositeReach = other.oppositeReach;
            this.texelSize = other.texelSize;
        }
    }
}
