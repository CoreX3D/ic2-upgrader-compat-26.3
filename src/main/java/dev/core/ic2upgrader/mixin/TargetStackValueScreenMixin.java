package dev.core.ic2upgrader.mixin;

import net.execheinz.upgrader.client.UpgraderScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The target card displays the whole selected stack; server prices and odds stay unchanged. */
@Mixin(value = UpgraderScreen.class, remap = false)
public abstract class TargetStackValueScreenMixin {
    @Shadow private int targetCount;

    @ModifyArg(method = "renderCardText", at = @At(value = "INVOKE", target =
            "Lnet/execheinz/upgrader/client/UpgraderScreen;format(J)Ljava/lang/String;", ordinal = 1),
            index = 0, remap = false)
    private long ic2upgrader$displayWholeTargetValue(long unitValue) {
        try {
            return Math.multiplyExact(unitValue, Math.max(1, targetCount));
        } catch (ArithmeticException overflow) {
            return unitValue < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }
}
