package com.heliummod.mixin;

import com.heliummod.core.HeliumBlockStateCompressor;
import com.heliummod.util.HeliumLogger;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.block.BlockRenderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts block-render calls so we can route them through Helium's
 * cached block-state compressor instead of hitting vanilla's uncompressed
 * short-array lookup every frame.
 */
@Mixin(BlockRenderManager.class)
public class MixinBlockRenderManager {

    /**
     * Injects at the top of {@code canRenderNeighborBlockFluidAsOverlay}.
     * This is one of the hottest methods in the chunk-meshing path.
     * By returning early when the state maps to air we skip a full
     * registry lookup.
     */
    @Inject(
        method = "renderBatched",
        at     = @At("HEAD"),
        cancellable = true
    )
    private void helium$fastAirCheck(
            BlockState state,
            net.minecraft.util.math.BlockPos pos,
            net.minecraft.world.BlockRenderView world,
            net.minecraft.client.util.math.MatrixStack matrices,
            net.minecraft.client.render.VertexConsumer vertexConsumer,
            boolean cull,
            java.util.Random random,
            long seed,
            net.minecraft.client.render.model.BakedModelManager manager,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // If the block is air (stateId == 0) we can skip the entire render
        // call: meshing, model lookup, face culling — all avoided.
        if (state.isAir()) {
            ci.cancel();
        }
    }
}
