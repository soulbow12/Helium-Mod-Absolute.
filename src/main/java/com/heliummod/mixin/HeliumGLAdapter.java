package com.heliummod.util;

/*
 * HeliumGLAdapter — thin wrapper around LWJGL / OpenGL calls.
 *
 * By funnelling every GL call through this class we:
 *  1. Allow unit-testing the render pipeline without a GL context (swap the impl).
 *  2. Centralise the LWJGL import so users who build against Minecraft's bundled
 *     LWJGL never need to declare it separately in build.gradle.
 *  3. Make PojavLauncher patches (GLES → GL translation) easy to slot in.
 *
 * All methods are static for zero-overhead dispatch (JIT inlines them readily).
 */

import java.nio.ByteBuffer;

// These imports resolve to Minecraft's bundled LWJGL 3 on a Fabric workspace.
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.ARBMultiDrawIndirect;

public final class HeliumGLAdapter {

    private HeliumGLAdapter() {}

    // ─── Buffer management ────────────────────────────────────────────────────

    public static int genBuffer() {
        return GL15.glGenBuffers();
    }

    public static void deleteBuffer(int handle) {
        GL15.glDeleteBuffers(handle);
    }

    public static void bindBuffer(int target, int handle) {
        GL15.glBindBuffer(target, handle);
    }

    public static void bufferData(int target, long size, ByteBuffer data, int usage) {
        GL15.glBufferData(target, size, data, usage);
    }

    public static void bufferSubData(int target, long offset, ByteBuffer data) {
        GL15.glBufferSubData(target, offset, data);
    }

    public static ByteBuffer mapBufferRange(int target, long offset, long length, int access) {
        return GL30.glMapBufferRange(target, offset, length, access);
    }

    public static void unmapBuffer(int target) {
        GL15.glUnmapBuffer(target);
    }

    // ─── Vertex arrays ────────────────────────────────────────────────────────

    public static int genVertexArray() {
        return GL30.glGenVertexArrays();
    }

    public static void deleteVertexArray(int handle) {
        GL30.glDeleteVertexArrays(handle);
    }

    public static void bindVertexArray(int handle) {
        GL30.glBindVertexArray(handle);
    }

    public static void enableVertexAttribArray(int index) {
        GL20.glEnableVertexAttribArray(index);
    }

    public static void vertexAttribPointer(int index, int size, int type,
                                           boolean normalised, int stride, long pointer) {
        GL20.glVertexAttribPointer(index, size, type, normalised, stride, pointer);
    }

    // ─── Drawing ─────────────────────────────────────────────────────────────

    public static void drawElementsBaseVertex(int mode, int count, int type,
                                              long indicesOffset, int baseVertex) {
        GL32.glDrawElementsBaseVertex(mode, count, type, indicesOffset, baseVertex);
    }

    public static void multiDrawElementsIndirect(int mode, int type, long indirect,
                                                 int drawcount, int stride) {
        // Try core GL 4.3 first, then ARB extension
        try {
            GL43.glMultiDrawElementsIndirect(mode, type, indirect, drawcount, stride);
        } catch (Exception e) {
            ARBMultiDrawIndirect.glMultiDrawElementsIndirectARB(
                    mode, type, indirect, drawcount, stride);
        }
    }

    // ─── Shaders ─────────────────────────────────────────────────────────────

    public static int createShader(int type) { return GL20.glCreateShader(type); }
    public static void shaderSource(int shader, String src) { GL20.glShaderSource(shader, src); }
    public static void compileShader(int shader) { GL20.glCompileShader(shader); }
    public static void deleteShader(int shader) { GL20.glDeleteShader(shader); }

    public static int getShaderiv(int shader, int pname) {
        return GL20.glGetShaderi(shader, pname);
    }
    public static String getShaderInfoLog(int shader) {
        return GL20.glGetShaderInfoLog(shader);
    }

    public static int createProgram() { return GL20.glCreateProgram(); }
    public static void attachShader(int prog, int shader) { GL20.glAttachShader(prog, shader); }
    public static void linkProgram(int prog) { GL20.glLinkProgram(prog); }
    public static void useProgram(int prog) { GL20.glUseProgram(prog); }
    public static void deleteProgram(int prog) { GL20.glDeleteProgram(prog); }

    public static int getProgramiv(int prog, int pname) {
        return GL20.glGetProgrami(prog, pname);
    }
    public static String getProgramInfoLog(int prog) {
        return GL20.glGetProgramInfoLog(prog);
    }

    public static int getUniformLocation(int prog, String name) {
        return GL20.glGetUniformLocation(prog, name);
    }
    public static void uniformMatrix4fv(int loc, boolean transpose, float[] mat) {
        GL20.glUniformMatrix4fv(loc, transpose, mat);
    }

    // ─── Sync objects ─────────────────────────────────────────────────────────

    public static void fenceSync() {
        // Insert a fence to throttle CPU-GPU overlap after buffer orphaning
        long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
        GL32.glDeleteSync(fence);
    }

    // ─── Capability queries ───────────────────────────────────────────────────

    public static String getExtensionsString() {
        // GL 3.0+ uses indexed string; fall back to old single string
        try {
            StringBuilder sb = new StringBuilder();
            int n = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            for (int i = 0; i < n; i++) sb.append(GL30.glGetStringi(GL11.GL_EXTENSIONS, i)).append(' ');
            return sb.toString();
        } catch (Exception e) {
            return GL11.glGetString(GL11.GL_EXTENSIONS);
        }
    }

    public static String getGLVersionString() {
        return GL11.glGetString(GL11.GL_VERSION);
    }
}
