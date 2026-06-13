package com.heliummod.mixin;

import com.heliummod.core.HeliumBlockStateCompressor;
import com.heliummod.util.HeliumLogger;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.world.chunk.ChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into {@link ChunkBuilder} to redirect chunk section data
 * through the Helium compressor when sections are built/submitted.
 *
 * <p>Phase 2 goal: swap ChunkSection's internal PalettedContainer
 * with {@link HeliumBlockStateCompressor} via interface injection (coming in Phase 3).
 * For now we log section stats.
 */
@Mixin(ChunkBuilder.class)
public class MixinChunkBuilder {

    @Inject(
        method = "rebuild",
        at     = @At("HEAD")
    )
    private void helium$onRebuild(CallbackInfo ci) {
        // Placeholder: in Phase 3, intercept the PalettedContainer here and
        // replace it with HeliumBlockStateCompressor.fromVanillaIntArray(...)
        // For now, just ensure the mixin wires up without error.
    }
}
