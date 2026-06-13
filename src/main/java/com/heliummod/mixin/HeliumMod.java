package com.heliummod;

import com.heliummod.core.HeliumBlockStateCompressor;
import com.heliummod.util.HeliumLogger;
import net.fabricmc.api.ModInitializer;

/**
 * Helium Mod — server-side / common initialiser.
 *
 * <p>Runs on both client and dedicated server.
 * GPU-related code lives in {@link HeliumClientMod}.
 */
public class HeliumMod implements ModInitializer {

    public static final String MOD_ID      = "helium";
    public static final String MOD_VERSION = "1.0.0";

    @Override
    public void onInitialize() {
        HeliumLogger.info("===========================================");
        HeliumLogger.info("  Helium Mod {} loading…", MOD_VERSION);
        HeliumLogger.info("===========================================");

        // Pre-warm the section pool so the first chunks never wait for allocation
        prewarmSectionPool();

        HeliumLogger.info("[Helium] Common init complete.");
    }

    private void prewarmSectionPool() {
        // Acquire and immediately release 16 sections to populate the pool
        HeliumBlockStateCompressor[] warmup = new HeliumBlockStateCompressor[16];
        for (int i = 0; i < warmup.length; i++) {
            warmup[i] = HeliumBlockStateCompressor.acquire();
        }
        for (HeliumBlockStateCompressor s : warmup) {
            HeliumBlockStateCompressor.release(s);
        }
        HeliumLogger.info("[Helium] Section pool pre-warmed with {} entries.", warmup.length);
    }
}
