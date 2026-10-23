package dev.core.ic2upgrader;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UpgraderIc2Compat implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ic2_upgrader_compat");

    @Override
    public void onInitialize() {
        BookTargets.initialize();
        UpgradeSpinSync.initialize();
        LOGGER.info("IC2 alpha.13 / Upgrader 1.2.1 compatibility enabled; original odds are preserved.");
    }
}
