package dev.horizon.shaders.graph;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder.Vertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.lwjgl.system.MemoryUtil;

/**
 * Sodium's compact chunk vertex plus the pack's material id (a_SulkanMaterial). With terrain faces
 * ({@code environment.terrainNormals}) the face code ({@link FaceContext}) goes into the top 2 bits of each of the two
 * position words, which Sodium's 3 x 10-bit packing leaves free and every decoder masks off: no extra bytes.
 */
public final class MaterialChunkVertex implements ChunkVertexType {
    private static final VertexFormat FORMAT = VertexFormat.builder(0)
        .addAttribute("a_Position", GpuFormat.RG32_UINT)
        .addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("a_TexCoord", GpuFormat.RG16_UINT)
        .addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT)
        .addAttribute("a_SulkanMaterial", GpuFormat.R32_UINT)
        .build();
    private final ChunkVertexEncoder encoder;

    public MaterialChunkVertex(MaterialTable materials) {
        ChunkVertexEncoder compact = new CompactChunkVertex().getEncoder();
        this.encoder = (pointer, material, vertices, section) -> {
            compact.write(pointer, material, vertices, section);
            int id = materials.id(MaterialContext.state());
            float minY = Float.POSITIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;

            for (Vertex v : vertices) {
                minY = Math.min(minY, v.y);
                maxY = Math.max(maxY, v.y);
            }

            int face = materials.faceData ? faceCode(vertices) : 0;
            for (int vertex = 3; vertex >= 0; vertex--) {
                for (int word = 4; word >= 0; word--) {
                    MemoryUtil.memPutInt(pointer + vertex * 24L + word * 4L, MemoryUtil.memGetInt(pointer + vertex * 20L + word * 4L));
                }

                if (face != 0) {
                    long position = pointer + vertex * 24L;
                    MemoryUtil.memPutInt(position, MemoryUtil.memGetInt(position) | (face & 3) << 30);
                    MemoryUtil.memPutInt(position + 4L, MemoryUtil.memGetInt(position + 4L) | (face >> 2 & 3) << 30);
                }

                int packed = id;
                if (materials.vegetationWind && id >= 2 && id <= 5) {
                    float tip = Math.clamp((vertices[vertex].y - minY) / Math.max(maxY - minY, 0.001F), 0.0F, 1.0F);
                    float weight = id == 5 ? 1.0F : (id == 3 ? tip * 0.5F : (id == 4 ? 0.5F + tip * 0.5F : tip));
                    packed = id | Math.round(weight * 65535.0F) << 16;
                }

                MemoryUtil.memPutInt(pointer + vertex * 24L + 20L, packed);
            }

            return pointer + 96L;
        };
    }

    // The quad's face code: from the block renderer when it is writing a block quad, else from the quad's corners (the
    // diagonals' cross product, which still works when two corners coincide, as in a fluid's triangles).
    private static int faceCode(Vertex[] vertices) {
        FaceContext.State context = FaceContext.current();
        if (context.mode == FaceContext.BLOCK) {
            return context.code;
        }

        Vertex a = vertices[0];
        Vertex b = vertices[1];
        Vertex c = vertices[2];
        Vertex d = vertices[3];
        float ex = c.x - a.x;
        float ey = c.y - a.y;
        float ez = c.z - a.z;
        float fx = d.x - b.x;
        float fy = d.y - b.y;
        float fz = d.z - b.z;
        int direction = FaceContext.direction(ey * fz - ez * fy, ez * fx - ex * fz, ex * fy - ey * fx);
        // Sodium shades a fluid's bottom as its top face (its sides by their own direction).
        return context.mode == FaceContext.FLUID && direction == 1 ? direction | FaceContext.SHADED_UP : direction;
    }

    public VertexFormat getVertexFormat() {
        return FORMAT;
    }

    public ChunkVertexEncoder getEncoder() {
        return this.encoder;
    }
}
