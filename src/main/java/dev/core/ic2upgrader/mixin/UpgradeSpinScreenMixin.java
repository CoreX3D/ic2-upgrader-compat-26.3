package dev.core.ic2upgrader.mixin;

import dev.core.ic2upgrader.UpgradeSpinSync;
import dev.core.ic2upgrader.client.UpgradeSpinScreen;
import net.execheinz.upgrader.client.UpgraderScreen;
import net.execheinz.upgrader.network.ClientboundUpgradeResultPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Retains native easing and rolling clicks, while the server alone decides when the wheel stops. */
@Mixin(value = UpgraderScreen.class, remap = false)
public abstract class UpgradeSpinScreenMixin implements UpgradeSpinScreen {
    @Shadow private long inputValue;
    @Shadow private boolean spinning;
    @Shadow private long spinStartMs;
    @Shadow private long spinDurationMs;
    @Shadow private float spinTotalAngle;
    @Shadow private long resultFlashUntil;
    @Shadow private boolean lastResultWin;
    @Shadow public abstract void onResult(ClientboundUpgradeResultPacket packet);

    @Unique private boolean ic2upgrader$authoritativeSpinPending;
    @Unique private boolean ic2upgrader$startingAuthoritativeSpin;
    @Unique private long ic2upgrader$authoritativeRoundId;

    @Override
    public void ic2upgrader$startSpin(UpgradeSpinSync.StartPayload payload) {
        if (payload.roundId() <= ic2upgrader$authoritativeRoundId) return;
        ic2upgrader$authoritativeRoundId = payload.roundId();
        ic2upgrader$authoritativeSpinPending = true;
        ic2upgrader$startingAuthoritativeSpin = true;
        try {
            onResult(new ClientboundUpgradeResultPacket(payload.success(), payload.landingAngle(),
                    payload.durationTicks(), payload.chance()));
        } finally {
            ic2upgrader$startingAuthoritativeSpin = false;
        }
    }

    @Override
    public void ic2upgrader$finishSpin(UpgradeSpinSync.FinishPayload payload) {
        if (!ic2upgrader$authoritativeSpinPending
                || payload.roundId() != ic2upgrader$authoritativeRoundId) return;
        inputValue = payload.inputValue();
        lastResultWin = payload.success();
        long now = System.currentTimeMillis();
        // Native needleAngle() now evaluates to the exact server-selected landing angle.
        spinStartMs = now - spinDurationMs;
        resultFlashUntil = now + 1_600L;
        ic2upgrader$authoritativeSpinPending = false;
        spinning = false;
        // The packet receiver owns the single completion sound, including the closed-window path.
    }

    @Inject(method = "onResult", at = @At("HEAD"), remap = false)
    private void ic2upgrader$keepNativeFallback(ClientboundUpgradeResultPacket packet, CallbackInfo callback) {
        if (!ic2upgrader$startingAuthoritativeSpin) ic2upgrader$authoritativeSpinPending = false;
    }

    @Inject(method = "progress", at = @At("RETURN"), cancellable = true, remap = false)
    private void ic2upgrader$waitForServerCommit(CallbackInfoReturnable<Float> callback) {
        // Prevent native containerTick() from independently stopping the wheel or playing another sound.
        if (ic2upgrader$authoritativeSpinPending) {
            callback.setReturnValue(Math.min(callback.getReturnValueF(), Math.nextDown(1.0f)));
        }
    }

    @Inject(method = "needleAngle", at = @At("RETURN"), cancellable = true, remap = false)
    private void ic2upgrader$keepWaitingWheelMoving(CallbackInfoReturnable<Float> callback) {
        if (!ic2upgrader$authoritativeSpinPending) return;
        long waitingMillis = System.currentTimeMillis() - spinStartMs - spinDurationMs;
        if (waitingMillis <= 0) return;
        double seconds = waitingMillis / 1_000.0;
        // Join the native easing at its final position and zero speed, then reach 60 degrees/second.
        // This is only a waiting animation: no elapsed time can commit or reveal the server result.
        double extraAngle = seconds < 0.5 ? 60.0 * seconds * seconds : 60.0 * (seconds - 0.25);
        callback.setReturnValue((float) ((spinTotalAngle + extraAngle) % 360.0));
    }
}
