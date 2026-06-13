package com.heliummod.mixin;

import com.heliummod.core.HeliumBlockStateCompressor;
import com.heliummod.util.HeliumLogger;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks chunk load/unload events on the ClientWorld so Helium can
 * update its SectionCache and trigger mesh eviction in the pipeline.
 */
@Mixin(ClientWorld.class)
public class MixinClientWorld {

    @Inject(
        method = "onChunkLoaded",
        at     = @At("TAIL")
    )
    private void helium$onChunkLoaded(int x, int z, WorldChunk chunk, CallbackInfo ci) {
        HeliumLogger.debug("[Helium] Chunk loaded ({}, {})", x, z);
        // Future: migrate chunk sections to HeliumBlockStateCompressor here
    }

    @Inject(
        method = "unloadChunk",
        at     = @At("HEAD")
    )
    private void helium$onChunkUnloaded(ChunkPos pos, CallbackInfo ci) {
        HeliumLogger.debug("[Helium] Chunk unloaded ({}, {})", pos.x, pos.z);
        // Future: release HeliumBlockStateCompressor sections back to pool here
    }
}
