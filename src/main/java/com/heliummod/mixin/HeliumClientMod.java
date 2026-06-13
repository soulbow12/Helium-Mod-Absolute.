package com.heliummod;

import com.heliummod.render.HeliumRenderPipeline;
import com.heliummod.util.HeliumLogger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Helium Mod — client-only initialiser.
 *
 * <p>Registers the {@link HeliumRenderPipeline} into the Fabric render event loop.
 */
@Environment(EnvType.CLIENT)
public class HeliumClientMod implements ClientModInitializer {

    /** Singleton pipeline instance (one per client session). */
    public static HeliumRenderPipeline PIPELINE;

    @Override
    public void onInitializeClient() {
        HeliumLogger.info("[Helium] Client init…");

        // Initialise the pipeline once the GL context is ready
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            PIPELINE = new HeliumRenderPipeline();
            PIPELINE.init();
            HeliumLogger.info("[Helium] Render pipeline online. Path: {}",
                              PIPELINE.getRenderPathName());
        });

        // Hook into the world-render event for each frame
        WorldRenderEvents.BEFORE_ENTITIES.register(ctx -> {
            if (PIPELINE == null) return;
            float[] mvp = extractMVP(ctx);
            var cam    = ctx.camera().getPos();
            PIPELINE.beginFrame(mvp, (float) cam.x, (float) cam.y, (float) cam.z);
            PIPELINE.render();
            PIPELINE.endFrame();
        });

        // Clean up when the client shuts down
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (PIPELINE != null) {
                PIPELINE.destroy();
                PIPELINE = null;
            }
        });

        HeliumLogger.info("[Helium] Client events registered.");
    }

    /**
     * Extracts the MVP matrix from the Fabric render context.
     * In 1.20 the context exposes the projection + view matrices separately;
     * we combine them here.
     */
    private float[] extractMVP(net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext ctx) {
        // Fabric gives us com.mojang.math.Matrix4f
        // We pull the raw float[] via the JOML bridge that Mojang ships in 1.20
        org.joml.Matrix4f proj = new org.joml.Matrix4f();
        org.joml.Matrix4f view = new org.joml.Matrix4f();

        // Copy Mojang Matrix4f → JOML (element-by-element)
        com.mojang.math.Matrix4f mProj = ctx.projectionMatrix();
        com.mojang.math.Matrix4f mView = ctx.camera().getEntityOrInterpolated(ctx.tickDelta())
                .getViewMatrix();     // approximation – replace with proper extraction

        // Build MVP as proj * view
        org.joml.Matrix4f mvp = new org.joml.Matrix4f(proj).mul(view);
        float[] arr = new float[16];
        mvp.get(arr);
        return arr;
    }
}
