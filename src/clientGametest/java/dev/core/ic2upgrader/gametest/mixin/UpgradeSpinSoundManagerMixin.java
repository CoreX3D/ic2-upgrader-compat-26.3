package dev.core.ic2upgrader.gametest.mixin;

import dev.core.ic2upgrader.gametest.UpgradeSpinSoundLedger;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Present only in the test mod; production has no sound counters or observational hooks. */
@Mixin(SoundManager.class)
public abstract class UpgradeSpinSoundManagerMixin {
    @Inject(method = "play", at = @At("HEAD"))
    private void ic2upgrader$recordActualSound(SoundInstance sound,
            CallbackInfoReturnable<SoundEngine.PlayResult> callback) {
        UpgradeSpinSoundLedger.record(sound);
    }
}
