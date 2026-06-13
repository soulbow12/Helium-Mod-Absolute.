package com.heliummod.mixin;

import com.heliummod.HeliumClientMod;
import com.heliummod.render.HeliumRenderPipeline;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into {@link WorldRenderer#render} to upload newly built chunk meshes
 * to the Helium render pipeline each frame. We do NOT cancel vanilla rendering
 * in Phase 1/2 — Helium's pipeline runs alongside vanilla until Phase 3.
 */
@Mixin(WorldRenderer.class)
public class MixinWorldRenderer {

    @Inject(
        method = "render",
        at     = @At("TAIL")
    )
    private void helium$onRenderTail(
            MatrixStack matrices,
            float tickDelta,
            long limitTime,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightmapTextureManager lightmapTextureManager,
            Matrix4f positionMatrix,
            CallbackInfo ci) {

        HeliumRenderPipeline pipeline = HeliumClientMod.PIPELINE;
        if (pipeline == null) return;

        // Log stats every 200 frames to the debug console
        // (remove this block in release builds)
        //noinspection ConstantConditions
        if (System.nanoTime() % 200_000_000L < 1_000_000L) {
            pipeline.printStats();
        }
    }
}
