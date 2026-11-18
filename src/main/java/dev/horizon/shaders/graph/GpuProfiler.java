package dev.horizon.shaders.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.OptionalLong;

/**
 * Where a frame's GPU time goes with a pack active: GPU timestamps at the stages Sulkan sees (scene start, shadow maps,
 * the copy of the opaque world before translucents, the pack's post passes), averaged and logged every two seconds as
 * "SULKANGPU ..." lines. Off unless started ({@link #start()}, e.g. ShotBot's
 * {@code call dev.horizon.shaders.graph.GpuProfiler start}); off it costs one boolean check per mark.
 */
public final class GpuProfiler {
    public static final int SCENE = 0;
    public static final int SHADOWS_START = 1;
    public static final int SHADOWS_END = 2;
    public static final int CAPTURE_START = 3;
    public static final int CAPTURE_END = 4;
    public static final int POST_START = 5;
    public static final int POST_END = 6;
    private static final int MARKS = 7;
    private static final int SLOTS = 6;
    private static final String[] SEGMENTS = {"setup", "shadows", "world", "capture", "translucent", "post", "rest"};
    private static volatile boolean enabled;
    private static GpuQueryPool pool;
    private static long frame;
    private static final int[] written = new int[SLOTS];
    private static final double[] sums = new double[SEGMENTS.length + 1];
    private static final int[] counts = new int[SEGMENTS.length + 1];
    private static long lastPrint;

    private GpuProfiler() {
    }

    public static void start() {
        enabled = true;
        lastPrint = System.nanoTime();
        java.util.Arrays.fill(sums, 0.0);
        java.util.Arrays.fill(counts, 0);
    }

    public static void stop() {
        enabled = false;
    }

    /** Scene start: reads back a frame old enough to be finished, then starts this frame's marks. */
    public static void frameStart() {
        if (!enabled) {
            return;
        }

        try {
            if (pool == null) {
                pool = RenderSystem.getDevice().createTimestampQueryPool(MARKS * SLOTS);
            }

            frame++;
            if (frame > SLOTS) {
                collect((int)((frame - 4) % SLOTS), (int)((frame - 3) % SLOTS));
            }

            written[(int)(frame % SLOTS)] = 0;
            mark(SCENE);
            long now = System.nanoTime();
            if (now - lastPrint > 2_000_000_000L) {
                StringBuilder line = new StringBuilder("SULKANGPU");
                for (int i = 0; i < SEGMENTS.length; i++) {
                    line.append(' ').append(SEGMENTS[i]).append('=').append(counts[i] == 0 ? "-" : String.format("%.3f", sums[i] / counts[i]));
                }

                line.append(" total=").append(counts[SEGMENTS.length] == 0 ? "-" : String.format("%.3f", sums[SEGMENTS.length] / counts[SEGMENTS.length]));
                line.append(" ms frames=").append(counts[SEGMENTS.length]);
                System.out.println(line);
                java.util.Arrays.fill(sums, 0.0);
                java.util.Arrays.fill(counts, 0);
                lastPrint = now;
            }
        } catch (RuntimeException failure) {
            enabled = false;
            System.out.println("SULKANGPU off: " + failure);
        }
    }

    public static void mark(int index) {
        if (!enabled || pool == null) {
            return;
        }

        int slot = (int)(frame % SLOTS);
        RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, slot * MARKS + index);
        written[slot] |= 1 << index;
    }

    // One finished frame: each stage between consecutive marks it has, and the whole frame up to the next scene start.
    private static void collect(int slot, int next) {
        OptionalLong[] values = pool.getValues(slot * MARKS, MARKS);
        long[] t = new long[MARKS];
        for (int i = 0; i < MARKS; i++) {
            if ((written[slot] & 1 << i) == 0 || values[i].isEmpty()) {
                t[i] = -1L;
            } else {
                t[i] = values[i].getAsLong();
            }
        }

        OptionalLong nextStart = (written[next] & 1) != 0 ? pool.getValue(next * MARKS) : OptionalLong.empty();
        int previous = 0;
        if (t[0] < 0L) {
            return;
        }

        for (int i = 1; i < MARKS; i++) {
            if (t[i] >= 0L) {
                add(i - 1, t[i] - t[previous]);
                previous = i;
            }
        }

        if (nextStart.isPresent()) {
            add(SEGMENTS.length - 1, nextStart.getAsLong() - t[previous]);
            add(SEGMENTS.length, nextStart.getAsLong() - t[0]);
        }
    }

    private static void add(int segment, long ticks) {
        if (ticks >= 0L && ticks < 1_000_000_000L) {
            sums[segment] += ticks / 1.0E6;
            counts[segment]++;
        }
    }
}
