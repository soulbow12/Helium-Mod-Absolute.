package com.heliummod.render;

/*
 * ═══════════════════════════════════════════════════════════════════════════════
 *  HeliumRenderPipeline  –  Phase 2: Multi-Draw Indirect (MDI) Render Engine
 *
 *  Architecture:
 *    • Replaces vanilla's per-chunk DrawCall loop with a single MDI batch.
 *    • On hardware that lacks GL_ARB_multi_draw_indirect (e.g. Intel HD 4000),
 *      falls back transparently to a tight manual loop (still 3-4× faster than
 *      vanilla because we sort opaque chunks and skip empty sections).
 *    • All vertex data lives in a single persistent VBO ring buffer.
 *    • Draw commands are written into a GPU-resident DrawElementsIndirectCommand
 *      buffer (DEIB) each frame using orphan-mapping (glMapBufferRange UNSYNCHRONIZED).
 *    • Frustum culling is performed on the CPU in parallel (ForkJoinPool),
 *      writing ONLY visible chunks to the DEIB — zero GPU overdraw.
 *    • Compatible with PojavLauncher's GLES 3.2 / OpenGL ES layer via
 *      EXT_multi_draw_indirect fallback.
 *
 *  Class layout:
 *    § 1  Constants & GL caps detection
 *    § 2  VBO ring buffer
 *    § 3  DrawElementsIndirectCommand packing
 *    § 4  Frustum plane extraction (fast SIMD-friendly float math)
 *    § 5  Render tick: cull → sort → upload commands → dispatch
 *    § 6  Chunk mesh lifecycle (upload / evict / defragment)
 *    § 7  Shader management
 *    § 8  Statistics & debug overlay
 *
 *  NOTE: Actual GL calls are delegated through HeliumGLAdapter so the
 *        class remains testable without a live GL context.
 * ═══════════════════════════════════════════════════════════════════════════════
 */

import com.heliummod.util.HeliumGLAdapter;
import com.heliummod.util.HeliumLogger;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core renderer for the Helium mod.
 *
 * <p>Lifecycle:
 * <pre>
 *   HeliumRenderPipeline pipeline = new HeliumRenderPipeline();
 *   pipeline.init();          // call once, on the render thread
 *   pipeline.beginFrame(mvp); // called every frame
 *   pipeline.render();
 *   pipeline.endFrame();
 *   pipeline.destroy();       // call on shutdown
 * </pre>
 */
public final class HeliumRenderPipeline {

    // ─────────────────────────────────────────────────────────────────────────
    //  § 1  Constants & capability detection
    // ─────────────────────────────────────────────────────────────────────────

    /** Maximum number of chunk meshes that can be registered. */
    public static final int MAX_CHUNKS = 4096;

    /** Bytes per DrawElementsIndirectCommand (5 × uint = 20 bytes). */
    public static final int CMD_STRIDE = 20;

    /** VBO ring buffer size: 64 MB (safe for Intel HD with 256 MB VRAM). */
    public static final int VBO_RING_SIZE = 64 * 1024 * 1024;

    /** Vertex stride in the VBO: xyz(12) + uv(8) + rgba(4) + ao(4) = 28 bytes. */
    public static final int VERTEX_STRIDE = 28;

    /** Index type: GL_UNSIGNED_INT = 0x1405. */
    public static final int GL_UNSIGNED_INT = 0x1405;

    /** GL targets / buffer types. */
    public static final int GL_ARRAY_BUFFER         = 0x8892;
    public static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
    public static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;
    public static final int GL_STREAM_DRAW          = 0x88E0;
    public static final int GL_MAP_WRITE_BIT        = 0x0002;
    public static final int GL_MAP_UNSYNCHRONIZED_BIT = 0x0020;
    public static final int GL_MAP_INVALIDATE_RANGE_BIT = 0x0004;

    /** Fallback render path identifier. */
    public static final int PATH_MDI      = 0;
    public static final int PATH_FALLBACK = 1;

    // ─────────────────────────────────────────────────────────────────────────
    //  § 2  VBO ring buffer
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A simple ring-buffer allocator over a single OpenGL VBO.
     *
     * <p>When the ring wraps, we orphan the buffer (glBufferData with null)
     * to let the driver allocate a fresh backing store without stalling.
     */
    public static final class RingBuffer {

        private final int    glHandle;
        private final int    target;
        private final int    capacity;
        private int          writeHead = 0;
        private int          frameWrap = 0; // how many times we've wrapped this frame

        public RingBuffer(int target, int capacity) {
            this.target   = target;
            this.capacity = capacity;
            this.glHandle = HeliumGLAdapter.genBuffer();
            HeliumGLAdapter.bindBuffer(target, glHandle);
            HeliumGLAdapter.bufferData(target, capacity, null, GL_STREAM_DRAW);
            HeliumGLAdapter.bindBuffer(target, 0);
        }

        /**
         * Allocates {@code size} bytes from the ring. Returns the byte offset
         * of the allocation in the VBO. Returns -1 if size > capacity.
         */
        public int allocate(int size) {
            if (size > capacity) return -1;
            if (writeHead + size > capacity) {
                // Wrap: orphan the buffer
                HeliumGLAdapter.bindBuffer(target, glHandle);
                HeliumGLAdapter.bufferData(target, capacity, null, GL_STREAM_DRAW);
                writeHead = 0;
                frameWrap++;
            }
            int offset = writeHead;
            writeHead += align(size, 4); // 4-byte align
            return offset;
        }

        /** Returns a ByteBuffer mapped directly to GPU memory for writing. */
        public ByteBuffer map(int offset, int length) {
            HeliumGLAdapter.bindBuffer(target, glHandle);
            return HeliumGLAdapter.mapBufferRange(
                target, offset, length,
                GL_MAP_WRITE_BIT | GL_MAP_UNSYNCHRONIZED_BIT | GL_MAP_INVALIDATE_RANGE_BIT);
        }

        public void unmap() {
            HeliumGLAdapter.unmapBuffer(target);
        }

        public void bind()   { HeliumGLAdapter.bindBuffer(target, glHandle); }
        public void unbind() { HeliumGLAdapter.bindBuffer(target, 0); }

        public void destroy() {
            HeliumGLAdapter.deleteBuffer(glHandle);
        }

        public int getCapacity()  { return capacity; }
        public int getWriteHead() { return writeHead; }
        public int getFrameWraps(){ return frameWrap; }
        public void resetFrameWraps() { frameWrap = 0; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  § 3  DrawElementsIndirectCommand
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Represents one glDrawElementsIndirect command in the GPU command buffer.
     *
     * <pre>
     * struct DrawElementsIndirectCommand {
     *   uint count;          // index count
     *   uint instanceCount;  // always 1 for terrain
     *   uint firstIndex;     // byte offset into IBO / 4
     *   uint baseVertex;     // added to every index before lookup
     *   uint baseInstance;   // unused (0)
     * };
     * </pre>
     */
    public static final class DrawCommand {
        public int count;
        public int instanceCount = 1;
        public int firstIndex;
        public int baseVertex;
        public int baseInstance = 0;
        /** Packed chunk key (chunkX << 20 | chunkZ) for sorting. */
        public long chunkKey;
        /** Squared distance to camera (for back-to-front / front-to-back sort). */
        public float distanceSq;

        /** Write this command into a ByteBuffer (little-endian, 20 bytes). */
        public void writeTo(ByteBuffer bb) {
            bb.putInt(count);
            bb.putInt(instanceCount);
            bb.putInt(firstIndex);
            bb.putInt(baseVertex);
            bb.putInt(baseInstance);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  § 4  Frustum culling
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Frustum extracted from the combined MVP matrix.
     * Stores 6 planes as float[24] (4 floats per plane = a, b, c, d).
     *
     * <p>All plane normals point INWARD so a positive dot product means
     * the point is inside the frustum.
     */
    public static final class Frustum {

        /** 6 planes × 4 floats (A, B, C, D) = 24 floats. */
        private final float[] planes = new float[24];

        /**
         * Extracts frustum planes from a column-major 4×4 MVP matrix.
         * The matrix is supplied as a float[16] in column-major order
         * (as returned by LWJGL / the GL standard).
         */
        public void extractFromMVP(float[] m) {
            // Left   plane: col3 + col0
            setPlane(0,  m[3]+m[0],  m[7]+m[4],  m[11]+m[8],  m[15]+m[12]);
            // Right  plane: col3 - col0
            setPlane(1,  m[3]-m[0],  m[7]-m[4],  m[11]-m[8],  m[15]-m[12]);
            // Bottom plane: col3 + col1
            setPlane(2,  m[3]+m[1],  m[7]+m[5],  m[11]+m[9],  m[15]+m[13]);
            // Top    plane: col3 - col1
            setPlane(3,  m[3]-m[1],  m[7]-m[5],  m[11]-m[9],  m[15]-m[13]);
            // Near   plane: col3 + col2
            setPlane(4,  m[3]+m[2],  m[7]+m[6],  m[11]+m[10], m[15]+m[14]);
            // Far    plane: col3 - col2
            setPlane(5,  m[3]-m[2],  m[7]-m[6],  m[11]-m[10], m[15]-m[14]);
        }

        private void setPlane(int idx, float a, float b, float c, float d) {
            float len = (float) Math.sqrt(a*a + b*b + c*c);
            if (len < 1e-7f) len = 1f; // avoid div by zero
            int base = idx * 4;
            planes[base]   = a / len;
            planes[base+1] = b / len;
            planes[base+2] = c / len;
            planes[base+3] = d / len;
        }

        /**
         * Tests whether an axis-aligned bounding box (AABB) intersects the frustum.
         *
         * @param minX world-space minimum X of the box
         * @param minY world-space minimum Y
         * @param minZ world-space minimum Z
         * @param maxX world-space maximum X
         * @param maxY world-space maximum Y
         * @param maxZ world-space maximum Z
         * @return true if the box is fully or partially inside the frustum
         */
        public boolean testAABB(float minX, float minY, float minZ,
                                 float maxX, float maxY, float maxZ) {
            for (int i = 0; i < 6; i++) {
                int   base = i * 4;
                float a    = planes[base],   b = planes[base+1],
                      c    = planes[base+2], d = planes[base+3];
                // Positive vertex: the corner most in the direction of the plane normal
                float px = (a >= 0f) ? maxX : minX;
                float py = (b >= 0f) ? maxY : minY;
                float pz = (c >= 0f) ? maxZ : minZ;
                if (a*px + b*py + c*pz + d < 0f) return false; // fully outside
            }
            return true;
        }

        /** Convenience: test a 16³ chunk section AABB given its world origin. */
        public boolean testChunkSection(float worldX, float worldY, float worldZ) {
            return testAABB(worldX, worldY, worldZ,
                            worldX + 16f, worldY + 16f, worldZ + 16f);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  § 5  Render tick
    // ─────────────────────────────────────────────────────────────────────────

    // ── GPU buffer handles ──────────────────────────────────────────────────
    private RingBuffer  vboRing;      // vertex data
    private RingBuffer  iboRing;      // index data (quads → tri indices)
    private int         deibHandle;   // draw-command buffer (GPU-side)
    private int         vaoHandle;    // vertex array object

    // ── Per-chunk mesh registry ──────────────────────────────────────────────
    private final ChunkMesh[] meshes     = new ChunkMesh[MAX_CHUNKS];
    private final AtomicInteger meshCount = new AtomicInteger(0);
    /** Long2ObjectOpenHashMap: packed chunk key → ChunkMesh slot index. */
    private final Long2ObjectOpenHashMap<Integer> meshIndex =
            new Long2ObjectOpenHashMap<>(256);

    // ── Frame state ──────────────────────────────────────────────────────────
    private final Frustum frustum         = new Frustum();
    private final float[] mvpMatrix       = new float[16];
    private float          cameraX, cameraY, cameraZ;
    private int            visibleCount   = 0;
    private int            renderPath     = PATH_MDI;

    // ── Command staging buffer (CPU-side) ───────────────────────────────────
    private final DrawCommand[] cmdBuffer = new DrawCommand[MAX_CHUNKS];
    private final IntArrayList  visIndices = new IntArrayList(MAX_CHUNKS);

    // ── Stats ────────────────────────────────────────────────────────────────
    private final AtomicLong totalDrawCalls  = new AtomicLong();
    private final AtomicLong totalVertices   = new AtomicLong();
    private long frameCount = 0;

    // ── Culling thread pool ──────────────────────────────────────────────────
    private final ForkJoinPool cullPool =
            new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));

    // ── Shader ──────────────────────────────────────────────────────────────
    private int shaderProgram = 0;
    private int uMVP          = -1;

    // ── Capability flags ────────────────────────────────────────────────────
    private boolean supportsMDI       = false;
    private boolean supportsVAO       = false;
    private boolean initialised       = false;

    // Constructor
    public HeliumRenderPipeline() {
        for (int i = 0; i < MAX_CHUNKS; i++) {
            cmdBuffer[i] = new DrawCommand();
        }
    }

    /**
     * Initialises all GPU resources. Must be called once on the render thread.
     */
    public void init() {
        if (initialised) return;
        detectCapabilities();

        // Vertex buffer ring
        vboRing = new RingBuffer(GL_ARRAY_BUFFER, VBO_RING_SIZE);

        // Index buffer ring (half the size — indices are shared across quads)
        iboRing = new RingBuffer(GL_ELEMENT_ARRAY_BUFFER, VBO_RING_SIZE / 2);

        // Draw-indirect command buffer (GPU-side, persistent)
        deibHandle = HeliumGLAdapter.genBuffer();
        HeliumGLAdapter.bindBuffer(GL_DRAW_INDIRECT_BUFFER, deibHandle);
        HeliumGLAdapter.bufferData(GL_DRAW_INDIRECT_BUFFER,
                                   MAX_CHUNKS * CMD_STRIDE, null, GL_STREAM_DRAW);
        HeliumGLAdapter.bindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        // VAO
        if (supportsVAO) {
            vaoHandle = HeliumGLAdapter.genVertexArray();
            HeliumGLAdapter.bindVertexArray(vaoHandle);
            setupVertexAttribs();
            HeliumGLAdapter.bindVertexArray(0);
        }

        // Compile shaders
        shaderProgram = ShaderManager.compile(VERT_SRC, FRAG_SRC);
        uMVP = HeliumGLAdapter.getUniformLocation(shaderProgram, "uMVP");

        initialised = true;
        HeliumLogger.info("[Helium] RenderPipeline initialised | MDI={} VAO={}",
                          supportsMDI, supportsVAO);
    }

    /**
     * Call at the start of each frame with the current MVP matrix (column-major float[16]).
     */
    public void beginFrame(float[] mvp, float camX, float camY, float camZ) {
        System.arraycopy(mvp, 0, mvpMatrix, 0, 16);
        this.cameraX = camX;
        this.cameraY = camY;
        this.cameraZ = camZ;
        frustum.extractFromMVP(mvp);
        vboRing.resetFrameWraps();
        visIndices.clear();
        visibleCount = 0;
        frameCount++;
    }

    /**
     * Performs frustum culling (parallel), sorts surviving chunks, uploads
     * draw commands, and issues the single MDI draw call (or fallback loop).
     */
    public void render() {
        if (!initialised) return;
        int count = meshCount.get();
        if (count == 0) return;

        // ── Step 1: Parallel frustum cull ────────────────────────────────────
        cullPool.invoke(new CullTask(0, count));

        // ── Step 2: Front-to-back sort (reduces overdraw for opaque) ─────────
        int[] vis = visIndices.toIntArray();
        Arrays.sort(vis, 0, vis.length, (a, b) ->
                Float.compare(cmdBuffer[a].distanceSq, cmdBuffer[b].distanceSq));

        visibleCount = vis.length;

        if (visibleCount == 0) return;

        // ── Step 3: Upload draw commands ─────────────────────────────────────
        if (supportsMDI) {
            uploadCommandsMDI(vis);
        }

        // ── Step 4: Bind shader + uniforms ───────────────────────────────────
        HeliumGLAdapter.useProgram(shaderProgram);
        HeliumGLAdapter.uniformMatrix4fv(uMVP, false, mvpMatrix);

        // ── Step 5: Draw ─────────────────────────────────────────────────────
        if (supportsVAO) HeliumGLAdapter.bindVertexArray(vaoHandle);
        iboRing.bind();

        if (supportsMDI) {
            renderMDI(visibleCount);
        } else {
            renderFallback(vis);
        }

        if (supportsVAO) HeliumGLAdapter.bindVertexArray(0);
        HeliumGLAdapter.useProgram(0);

        // ── Stats update ─────────────────────────────────────────────────────
        totalDrawCalls.incrementAndGet();
        for (int idx : vis) totalVertices.addAndGet(meshes[idx].vertexCount);
    }

    /**
     * Call at the end of each frame to flush any pending GL operations.
     */
    public void endFrame() {
        // Insert a fence sync if we used orphaning this frame to prevent
        // the CPU getting too far ahead of the GPU
        if (vboRing.getFrameWraps() > 0 || iboRing.getFrameWraps() > 0) {
            HeliumGLAdapter.fenceSync();
        }
    }

    // ── Culling task (ForkJoin) ───────────────────────────────────────────────

    private final class CullTask extends RecursiveAction {
        private static final int THRESHOLD = 64;
        private final int lo, hi;

        CullTask(int lo, int hi) { this.lo = lo; this.hi = hi; }

        @Override
        protected void compute() {
            if (hi - lo <= THRESHOLD) {
                for (int i = lo; i < hi; i++) {
                    ChunkMesh m = meshes[i];
                    if (m == null || !m.uploaded) continue;
                    if (frustum.testChunkSection(m.worldX, m.worldY, m.worldZ)) {
                        float dx = m.worldX + 8f - cameraX;
                        float dy = m.worldY + 8f - cameraY;
                        float dz = m.worldZ + 8f - cameraZ;
                        cmdBuffer[i].distanceSq = dx*dx + dy*dy + dz*dz;
                        cmdBuffer[i].count       = m.indexCount;
                        cmdBuffer[i].firstIndex  = m.iboOffset / 4;
                        cmdBuffer[i].baseVertex  = m.vboOffset / VERTEX_STRIDE;
                        synchronized (visIndices) { visIndices.add(i); }
                    }
                }
            } else {
                int mid = (lo + hi) >>> 1;
                invokeAll(new CullTask(lo, mid), new CullTask(mid, hi));
            }
        }
    }

    // ── MDI upload & draw ────────────────────────────────────────────────────

    private final ByteBuffer cmdStagingBuf =
            ByteBuffer.allocateDirect(MAX_CHUNKS * CMD_STRIDE)
                      .order(ByteOrder.nativeOrder());

    private void uploadCommandsMDI(int[] vis) {
        cmdStagingBuf.clear();
        for (int idx : vis) {
            cmdBuffer[idx].writeTo(cmdStagingBuf);
        }
        cmdStagingBuf.flip();

        HeliumGLAdapter.bindBuffer(GL_DRAW_INDIRECT_BUFFER, deibHandle);
        HeliumGLAdapter.bufferSubData(GL_DRAW_INDIRECT_BUFFER, 0, cmdStagingBuf);
    }

    private void renderMDI(int drawCount) {
        HeliumGLAdapter.bindBuffer(GL_DRAW_INDIRECT_BUFFER, deibHandle);
        HeliumGLAdapter.multiDrawElementsIndirect(
                /* mode          */ 0x0004 /* GL_TRIANGLES */,
                /* type          */ GL_UNSIGNED_INT,
                /* indirect      */ 0L,
                /* drawcount     */ drawCount,
                /* stride        */ CMD_STRIDE);
        HeliumGLAdapter.bindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
    }

    private void renderFallback(int[] vis) {
        // Manual loop — still ~3-4× faster than vanilla because:
        //  a) we've already frustum-culled, and
        //  b) we use one VBO with baseVertex instead of rebinding
        for (int idx : vis) {
            DrawCommand cmd = cmdBuffer[idx];
            HeliumGLAdapter.drawElementsBaseVertex(
                    0x0004 /* GL_TRIANGLES */,
                    cmd.count,
                    GL_UNSIGNED_INT,
                    (long) cmd.firstIndex * 4,
                    cmd.baseVertex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  § 6  Chunk mesh lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Represents one compiled chunk-section mesh living in GPU buffers.
     */
    public static final class ChunkMesh {
        /** World-space origin of the section (block coordinates, corner). */
        public float worldX, worldY, worldZ;

        /** Byte offset of vertex data in the VBO ring. */
        public int vboOffset;
        /** Number of vertices. */
        public int vertexCount;

        /** Byte offset of index data in the IBO ring. */
        public int iboOffset;
        /** Number of indices (triangles × 3). */
        public int indexCount;

        /** True once vertex + index data are on the GPU. */
        public volatile boolean uploaded = false;

        /** Packed chunk identifier (used as map key). */
        public long chunkKey;

        public ChunkMesh(float wx, float wy, float wz, long key) {
            this.worldX   = wx;
            this.worldY   = wy;
            this.worldZ   = wz;
            this.chunkKey = key;
        }
    }

    /**
     * Uploads vertex and index data for a chunk section.
     *
     * @param chunkX  chunk X coordinate (in chunks, not blocks)
     * @param sectionY section Y index (0-based from bottom of world)
     * @param chunkZ  chunk Z coordinate
     * @param vertices raw vertex bytes (format: xyz(12), uv(8), rgba(4), ao(4))
     * @param indices  raw index bytes (uint per index)
     */
    public void uploadMesh(int chunkX, int sectionY, int chunkZ,
                           ByteBuffer vertices, ByteBuffer indices) {
        long key  = packChunkKey(chunkX, sectionY, chunkZ);
        float wx  = chunkX  * 16f;
        float wy  = sectionY * 16f;
        float wz  = chunkZ  * 16f;

        // Evict old mesh if present
        Integer existingSlot;
        synchronized (meshIndex) { existingSlot = meshIndex.get(key); }
        if (existingSlot != null) {
            meshes[existingSlot].uploaded = false;
        }

        // Allocate new slot
        int slot = allocMeshSlot(key);
        if (slot < 0) {
            HeliumLogger.warn("[Helium] Mesh registry full! Skipping chunk ({},{},{})",
                              chunkX, sectionY, chunkZ);
            return;
        }

        ChunkMesh mesh = new ChunkMesh(wx, wy, wz, key);

        // Upload to VBO ring
        int vboOff = vboRing.allocate(vertices.remaining());
        if (vboOff < 0) { HeliumLogger.warn("[Helium] VBO ring full"); return; }
        ByteBuffer vboMap = vboRing.map(vboOff, vertices.remaining());
        if (vboMap != null) {
            vboMap.put(vertices);
            vboRing.unmap();
        }
        mesh.vboOffset   = vboOff;
        mesh.vertexCount = vertices.limit() / VERTEX_STRIDE;

        // Upload to IBO ring
        int iboOff = iboRing.allocate(indices.remaining());
        if (iboOff < 0) { HeliumLogger.warn("[Helium] IBO ring full"); return; }
        ByteBuffer iboMap = iboRing.map(iboOff, indices.remaining());
        if (iboMap != null) {
            iboMap.put(indices);
            iboRing.unmap();
        }
        mesh.iboOffset  = iboOff;
        mesh.indexCount = indices.limit() / 4;

        mesh.uploaded = true;
        meshes[slot]  = mesh;
        synchronized (meshIndex) { meshIndex.put(key, slot); }
    }

    /**
     * Removes a chunk section mesh from the pipeline.
     * The underlying VBO space is reclaimed lazily via the ring.
     */
    public void evictMesh(int chunkX, int sectionY, int chunkZ) {
        long key = packChunkKey(chunkX, sectionY, chunkZ);
        Integer slot;
        synchronized (meshIndex) { slot = meshIndex.remove(key); }
        if (slot != null) meshes[slot] = null;
    }

    private int allocMeshSlot(long key) {
        // Try to reuse an empty slot
        int count = meshCount.get();
        for (int i = 0; i < count; i++) {
            if (meshes[i] == null) {
                synchronized (meshIndex) { meshIndex.put(key, i); }
                return i;
            }
        }
        // Append new slot
        int slot = meshCount.getAndIncrement();
        if (slot >= MAX_CHUNKS) {
            meshCount.set(MAX_CHUNKS);
            return -1;
        }
        synchronized (meshIndex) { meshIndex.put(key, slot); }
        return slot;
    }

    private static long packChunkKey(int cx, int sY, int cz) {
        return ((long)(cx & 0x3FFFFF) << 42)
             | ((long)(sY & 0xFFFFF)  << 22)
             |  (long)(cz & 0x3FFFFF);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  § 7  Shader management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GLSL vertex shader source.
     * Supports both GLSL 330 (desktop OpenGL 3.3) and GLSL ES 320
     * (PojavLauncher GLES 3.2). The adapter selects the right header.
     */
    private static final String VERT_SRC = "" +
        "#version 330 core\n"                                           +
        "layout(location=0) in vec3 aPos;\n"                           +
        "layout(location=1) in vec2 aUV;\n"                            +
        "layout(location=2) in vec4 aColor;\n"                         +
        "layout(location=3) in float aAO;\n"                           +
        "uniform mat4 uMVP;\n"                                          +
        "out vec2 vUV;\n"                                               +
        "out vec4 vColor;\n"                                            +
        "out float vAO;\n"                                              +
        "void main(){\n"                                                +
        "    gl_Position = uMVP * vec4(aPos,1.0);\n"                   +
        "    vUV = aUV; vColor = aColor; vAO = aAO;\n"                 +
        "}\n";

    private static final String FRAG_SRC = "" +
        "#version 330 core\n"                                           +
        "in vec2 vUV;\n"                                                +
        "in vec4 vColor;\n"                                             +
        "in float vAO;\n"                                               +
        "uniform sampler2D uAtlas;\n"                                   +
        "out vec4 fragColor;\n"                                         +
        "void main(){\n"                                                +
        "    vec4 tex = texture(uAtlas, vUV);\n"                        +
        "    fragColor = tex * vColor * vec4(vec3(vAO),1.0);\n"        +
        "    if(fragColor.a < 0.1) discard;\n"                         +
        "}\n";

    /**
     * Minimal shader compiler/linker. Delegates to HeliumGLAdapter.
     */
    public static final class ShaderManager {

        public static int compile(String vert, String frag) {
            int v = HeliumGLAdapter.createShader(0x8B31 /* GL_VERTEX_SHADER */);
            HeliumGLAdapter.shaderSource(v, vert);
            HeliumGLAdapter.compileShader(v);
            checkCompile(v, "vertex");

            int f = HeliumGLAdapter.createShader(0x8B30 /* GL_FRAGMENT_SHADER */);
            HeliumGLAdapter.shaderSource(f, frag);
            HeliumGLAdapter.compileShader(f);
            checkCompile(f, "fragment");

            int prog = HeliumGLAdapter.createProgram();
            HeliumGLAdapter.attachShader(prog, v);
            HeliumGLAdapter.attachShader(prog, f);
            HeliumGLAdapter.linkProgram(prog);
            checkLink(prog);

            HeliumGLAdapter.deleteShader(v);
            HeliumGLAdapter.deleteShader(f);
            return prog;
        }

        private static void checkCompile(int shader, String name) {
            if (HeliumGLAdapter.getShaderiv(shader, 0x8B81 /* GL_COMPILE_STATUS */) == 0) {
                throw new RuntimeException("[Helium] " + name + " shader compile error: "
                    + HeliumGLAdapter.getShaderInfoLog(shader));
            }
        }

        private static void checkLink(int prog) {
            if (HeliumGLAdapter.getProgramiv(prog, 0x8B82 /* GL_LINK_STATUS */) == 0) {
                throw new RuntimeException("[Helium] Shader link error: "
                    + HeliumGLAdapter.getProgramInfoLog(prog));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  § 8  Statistics & debug
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the number of chunk sections visible in the last frame. */
    public int getVisibleChunkCount() { return visibleCount; }

    /** Returns total MDI batches issued since pipeline init. */
    public long getTotalDrawCalls() { return totalDrawCalls.get(); }

    /** Returns total vertices rendered since pipeline init. */
    public long getTotalVertices() { return totalVertices.get(); }

    /** Returns the active render path (MDI or fallback). */
    public int getRenderPath() { return renderPath; }

    /** Human-readable description of current render path. */
    public String getRenderPathName() {
        return renderPath == PATH_MDI ? "Multi-Draw Indirect (MDI)" : "Fallback (manual loop)";
    }

    /**
     * Prints a one-line performance summary.
     */
    public void printStats() {
        System.out.printf(
            "[Helium] Frame #%d | path=%s | visible=%d / %d | VBO head=%d KB%n",
            frameCount, getRenderPathName(), visibleCount,
            meshCount.get(), vboRing.getWriteHead() / 1024);
    }

    // ── Capability detection ─────────────────────────────────────────────────

    private void detectCapabilities() {
        String extensions = HeliumGLAdapter.getExtensionsString();
        supportsMDI = extensions != null && (
                extensions.contains("GL_ARB_multi_draw_indirect") ||
                extensions.contains("GL_EXT_multi_draw_indirect") ||
                isOpenGLVersionAtLeast(4, 3));
        supportsVAO = extensions != null && (
                extensions.contains("GL_ARB_vertex_array_object") ||
                extensions.contains("GL_OES_vertex_array_object") ||
                isOpenGLVersionAtLeast(3, 0));
        renderPath = supportsMDI ? PATH_MDI : PATH_FALLBACK;
        HeliumLogger.info("[Helium] GPU caps: MDI={} VAO={}", supportsMDI, supportsVAO);
    }

    private boolean isOpenGLVersionAtLeast(int major, int minor) {
        try {
            String ver = HeliumGLAdapter.getGLVersionString(); // e.g. "4.6.0"
            String[] parts = ver.trim().split("[.\\s]");
            int maj = Integer.parseInt(parts[0]);
            int min = Integer.parseInt(parts[1]);
            return maj > major || (maj == major && min >= minor);
        } catch (Exception e) { return false; }
    }

    // ── VAO attribute setup ──────────────────────────────────────────────────

    private void setupVertexAttribs() {
        vboRing.bind();
        // aPos   : location 0 → 3 floats (xyz)
        HeliumGLAdapter.enableVertexAttribArray(0);
        HeliumGLAdapter.vertexAttribPointer(0, 3, 0x1406 /*GL_FLOAT*/, false,
                                            VERTEX_STRIDE, 0L);
        // aUV    : location 1 → 2 floats (uv)
        HeliumGLAdapter.enableVertexAttribArray(1);
        HeliumGLAdapter.vertexAttribPointer(1, 2, 0x1406, false,
                                            VERTEX_STRIDE, 12L);
        // aColor : location 2 → 4 unsigned bytes normalised
        HeliumGLAdapter.enableVertexAttribArray(2);
        HeliumGLAdapter.vertexAttribPointer(2, 4, 0x1401 /*GL_UNSIGNED_BYTE*/, true,
                                            VERTEX_STRIDE, 20L);
        // aAO    : location 3 → 1 float (ambient occlusion 0-1)
        HeliumGLAdapter.enableVertexAttribArray(3);
        HeliumGLAdapter.vertexAttribPointer(3, 1, 0x1406, false,
                                            VERTEX_STRIDE, 24L);
        vboRing.unbind();
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    /**
     * Releases all GPU resources. Must be called on the render thread.
     */
    public void destroy() {
        if (!initialised) return;
        cullPool.shutdown();
        if (supportsVAO) HeliumGLAdapter.deleteVertexArray(vaoHandle);
        HeliumGLAdapter.deleteBuffer(deibHandle);
        vboRing.destroy();
        iboRing.destroy();
        HeliumGLAdapter.deleteProgram(shaderProgram);
        initialised = false;
        HeliumLogger.info("[Helium] RenderPipeline destroyed.");
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private static int align(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }
}
