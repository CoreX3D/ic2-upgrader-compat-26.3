package dev.core.ic2upgrader.client;

import dev.core.ic2upgrader.UpgradeSpinSync;
import net.execheinz.upgrader.client.UpgraderScreen;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/** Applies the slot, wheel and owner sound together from one authoritative completion packet. */
public final class UpgradeSpinClient implements ClientModInitializer {
    private static long latestStartedRound;
    private static Pending pending;

    private record Pending(int containerId, long roundId, UpgraderMenu owner) {}

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(UpgradeSpinSync.StartPayload.TYPE,
                (payload, context) -> acceptStart(payload, context.client()));
        ClientPlayNetworking.registerGlobalReceiver(UpgradeSpinSync.FinishPayload.TYPE,
                (payload, context) -> acceptFinish(payload, context.client()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    /** Called on the client thread by the registered packet receiver. */
    public static boolean acceptStart(UpgradeSpinSync.StartPayload payload, Minecraft client) {
        // Round IDs are positive and monotonic across all server menus. Replays cannot restart a wheel.
        if (client.player == null || payload.roundId() <= latestStartedRound
                || payload.roundId() <= 0 || payload.containerId() < 0
                || payload.durationTicks() < 1 || !Float.isFinite(payload.landingAngle())
                || !Float.isFinite(payload.chance())) return false;

        latestStartedRound = payload.roundId();
        UpgraderMenu owner = client.player.containerMenu instanceof UpgraderMenu menu
                && menu.containerId == payload.containerId() ? menu : null;
        // Keep the round even if the user has already closed its window: its completion still has one sound.
        pending = new Pending(payload.containerId(), payload.roundId(), owner);
        if (owner != null && client.gui.screen() instanceof UpgraderScreen screen && screen.getMenu() == owner) {
            ((UpgradeSpinScreen) screen).ic2upgrader$startSpin(payload);
        }
        return true;
    }

    /** A duplicate, stale or unrelated completion cannot change an inventory or replay its sound. */
    public static boolean acceptFinish(UpgradeSpinSync.FinishPayload payload, Minecraft client) {
        Pending round = pending;
        if (client.player == null || round == null || round.roundId() != payload.roundId()
                || round.containerId() != payload.containerId()) return false;

        // Consume before callbacks: slot listeners and sound hooks must not reenter this completion.
        pending = null;
        UpgraderMenu owner = round.owner();
        if (owner != null && client.player.containerMenu == owner && owner.containerId == payload.containerId()) {
            owner.getSlot(UpgraderMenu.INPUT_SLOT).set(payload.result().copy());
            if (client.gui.screen() instanceof UpgraderScreen screen && screen.getMenu() == owner) {
                ((UpgradeSpinScreen) screen).ic2upgrader$finishSpin(payload);
            }
        }
        if (payload.playSound()) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(
                    payload.success() ? SoundEvents.PLAYER_LEVELUP : SoundEvents.ANVIL_LAND,
                    payload.success() ? 1.5f : 0.7f, 0.35f));
        }
        return true;
    }

    private static void clear() {
        latestStartedRound = 0;
        pending = null;
    }
}
