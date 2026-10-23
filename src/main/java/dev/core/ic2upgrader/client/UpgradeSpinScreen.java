package dev.core.ic2upgrader.client;

import dev.core.ic2upgrader.UpgradeSpinSync;

/** The native wheel displays a result only after the server has committed that round. */
public interface UpgradeSpinScreen {
    void ic2upgrader$startSpin(UpgradeSpinSync.StartPayload payload);

    void ic2upgrader$finishSpin(UpgradeSpinSync.FinishPayload payload);
}
