package com.heliummod.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central logger for the Helium mod.
 * Wraps SLF4J (available via Fabric Loader) so all messages appear
 * under a consistent "[Helium]" prefix in the game log.
 */
public final class HeliumLogger {

    private static final Logger LOG = LoggerFactory.getLogger("Helium");

    private HeliumLogger() {}

    public static void info(String msg, Object... args)  { LOG.info(msg, args);  }
    public static void warn(String msg, Object... args)  { LOG.warn(msg, args);  }
    public static void error(String msg, Object... args) { LOG.error(msg, args); }
    public static void debug(String msg, Object... args) { LOG.debug(msg, args); }
}
