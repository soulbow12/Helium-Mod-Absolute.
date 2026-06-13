package com.heliummod.mixin;

import com.heliummod.HeliumClientMod;
import com.heliummod.render.HeliumRenderPipeline;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects into {@link GameRenderer} to capture the projection matrix
 * each frame before the world is rendered.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    @Inject(
        method = "renderWorld",
        at     = @At("HEAD")
    )
    private void helium$captureProjection(float tickDelta, long limitTime,
                                          org.joml.Matrix4f matrix,
                                          CallbackInfo ci) {
        // The actual MVP extraction happens in HeliumClientMod via the
        // WorldRenderEvents. This mixin is a placeholder for Phase 3 where
        // we'll capture the exact projection matrix before any vanilla rendering.
        HeliumRenderPipeline p = HeliumClientMod.PIPELINE;
        if (p == null) return;
        // Reserved for Phase 3: p.setProjMatrix(matrix);
    }
}
