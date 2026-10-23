package dev.core.ic2upgrader.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.core.ic2upgrader.UpgradeSpinSync;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.execheinz.upgrader.network.ClientboundUpgradeResultPacket;
import net.execheinz.upgrader.value.ItemValues;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps a committed inventory result, wheel stop and owner sound on one server acknowledgement. */
@Mixin(value = UpgraderMenu.class, remap = false)
public abstract class UpgradeSpinMenuMixin implements UpgradeSpinSync.MenuState {
    @Shadow @Final private Player player;
    @Shadow private boolean pendingWin;
    @Shadow public abstract ItemStack getInputStack();

    @Unique private long ic2upgrader$spinRoundId;
    @Unique private boolean ic2upgrader$committingSpin;
    @Unique private boolean ic2upgrader$spinPlayedSound;

    @Override
    public long ic2upgrader$getSpinRoundId() { return ic2upgrader$spinRoundId; }

    @ModifyExpressionValue(method = "startUpgrade", at = @At(value = "FIELD", target =
            "Lnet/execheinz/upgrader/UpgraderConfig;spinTicks:I", opcode = Opcodes.GETSTATIC),
            require = 1, allow = 1, remap = false)
    private int ic2upgrader$requireCompletableDuration(int configuredTicks) {
        // Native completion only runs while the counter is positive.
        return Math.max(1, configuredTicks);
    }

    @WrapOperation(method = "startUpgrade", at = @At(value = "INVOKE", target =
            "Lnet/execheinz/upgrader/network/UpgraderNetwork;toClient(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/Object;)V"),
            require = 1, allow = 1, remap = false)
    private void ic2upgrader$startAcknowledgedSpin(ServerPlayer sender, Object packet, Operation<Void> original) {
        if (!(packet instanceof ClientboundUpgradeResultPacket start) || !UpgradeSpinSync.canSend(sender)) {
            original.call(sender, packet);
            return;
        }
        ic2upgrader$spinRoundId = UpgradeSpinSync.nextRoundId();
        ServerPlayNetworking.send(sender, new UpgradeSpinSync.StartPayload(
                ((UpgraderMenu) (Object) this).containerId, ic2upgrader$spinRoundId,
                start.success(), start.landingAngle(), start.durationTicks(), start.chance()));
    }

    @Inject(method = "applyResult", at = @At("HEAD"), remap = false)
    private void ic2upgrader$beginCommittedResult(CallbackInfo callback) {
        if (ic2upgrader$spinRoundId != 0 && player instanceof ServerPlayer) {
            ic2upgrader$committingSpin = true;
            ic2upgrader$spinPlayedSound = false;
        }
    }

    @Inject(method = "broadcastChanges", at = @At("HEAD"), cancellable = true, remap = false)
    private void ic2upgrader$deferReentrantInventorySync(CallbackInfo callback) {
        // Native input.setItem/setChanged reenter broadcastChanges from inside applyResult.
        // The final stack must reach the client through Finish before those vanilla slot updates.
        if (ic2upgrader$committingSpin) callback.cancel();
    }

    @WrapOperation(method = "applyResult", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ServerLevel;playSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"),
            require = 1, allow = 1, remap = false)
    private void ic2upgrader$sendOwnerSoundWithResult(ServerLevel level, Entity excluded, BlockPos position,
            SoundEvent event, SoundSource source, float volume, float pitch, Operation<Void> original) {
        if (!ic2upgrader$committingSpin) {
            original.call(level, excluded, position, event, source, volume, pitch);
            return;
        }
        // The original positional sound remains audible once to nearby players.
        // Only the owner hears the matching UI sound when Finish is actually received.
        original.call(level, player, position, event, source, volume, pitch);
        ic2upgrader$spinPlayedSound = true;
    }

    @Inject(method = "applyResult", at = @At("RETURN"), remap = false)
    private void ic2upgrader$finishCommittedResult(CallbackInfo callback) {
        if (!ic2upgrader$committingSpin || !(player instanceof ServerPlayer sender)) return;
        long roundId = ic2upgrader$spinRoundId;
        boolean playSound = ic2upgrader$spinPlayedSound;
        // Clear before handing the result to any networking hooks: repeated ticks or closing
        // must not acknowledge an already committed attempt a second time.
        ic2upgrader$spinRoundId = 0;
        ic2upgrader$committingSpin = false;
        ic2upgrader$spinPlayedSound = false;
        ItemStack result = getInputStack().copy();
        long inputValue = Math.round(ItemValues.stackValue(sender.level(), result));
        ServerPlayNetworking.send(sender, new UpgradeSpinSync.FinishPayload(
                ((UpgraderMenu) (Object) this).containerId, roundId,
                playSound && pendingWin && !result.isEmpty(), playSound, result, inputValue));
    }
}
