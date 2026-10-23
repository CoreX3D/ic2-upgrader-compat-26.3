package dev.core.ic2upgrader.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.core.ic2upgrader.BookTargetMenu;
import dev.core.ic2upgrader.BookTargets;
import dev.core.ic2upgrader.UpgradeAnnouncements;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.execheinz.upgrader.value.ItemValues;
import net.execheinz.upgrader.value.UpgradeOdds;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = UpgraderMenu.class, remap = false)
public abstract class UpgraderMenuMixin implements BookTargetMenu {
    @Shadow @Final private Player player;
    @Shadow private Item target;
    @Shadow private boolean pendingWin;
    @Shadow private int targetCount;
    @Shadow public abstract boolean isSpinning();
    @Shadow public abstract ItemStack getInputStack();

    @Unique private ItemStack ic2Upgrader$committedInput = ItemStack.EMPTY;
    @Unique private Item ic2Upgrader$committedTarget;
    @Unique private double ic2Upgrader$committedChance;
    @Unique private boolean ic2Upgrader$applyingWin;
    @Unique private BookTargets.Key ic2upgrader$bookTarget;
    @Unique private BookTargets.Key ic2upgrader$lastSyncedBook;
    @Unique private boolean ic2upgrader$bookSynced;
    @Unique private long ic2upgrader$catalogRevision = Long.MIN_VALUE;

    @Override
    public BookTargets.Key ic2upgrader$getBookTarget() { return ic2upgrader$bookTarget; }

    @Override
    public void ic2upgrader$setBookTarget(BookTargets.Key key) { ic2upgrader$bookTarget = key; }

    @Inject(method = "setTarget", at = @At("HEAD"), remap = false)
    private void ic2upgrader$clearBookForOrdinarySelection(ServerPlayer sender, Item item, CallbackInfo callback) {
        // Rejected target changes during a committed spin cannot alter the eventual reward.
        if (!isSpinning() && (item == null || !ItemValues.isBlacklisted(item))) ic2upgrader$bookTarget = null;
    }

    @Inject(method = "setTargetClient", at = @At("HEAD"), remap = false)
    private void ic2upgrader$clearDifferentClientTarget(Item item, CallbackInfo callback) {
        // Upgrader repeats setTargetClient on every input/count sync. Keep a book's components then.
        if (item != Items.ENCHANTED_BOOK) ic2upgrader$bookTarget = null;
    }

    @Inject(method = "startUpgrade", at = @At("HEAD"), cancellable = true, remap = false)
    private void ic2upgrader$validateBookAttempt(ServerPlayer sender, CallbackInfo callback) {
        if (target == Items.ENCHANTED_BOOK && ic2upgrader$bookTarget == null) {
            callback.cancel();
            return;
        }
        if (ic2upgrader$bookTarget != null && (target != Items.ENCHANTED_BOOK
                || !BookTargets.isLegal(sender.level(), ic2upgrader$bookTarget)
                || BookTargets.value(sender.level(), ic2upgrader$bookTarget) <= 0)) callback.cancel();
    }

    @ModifyExpressionValue(method = "startUpgrade", at = @At(value = "INVOKE", target =
            "Lnet/execheinz/upgrader/value/UpgradeOdds;chance(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/Item;I)D"), remap = false)
    private double ic2Upgrader$captureAcceptedAttempt(double chance) {
        if (ic2upgrader$bookTarget != null) {
            chance = UpgradeOdds.chance(ItemValues.stackValue(player.level(), getInputStack()),
                    BookTargets.value(player.level(), ic2upgrader$bookTarget) * targetCount);
        }
        // This call occurs only after all rejection checks, and is the exact chance used by RNG.
        ic2Upgrader$committedInput = getInputStack().copy();
        ic2Upgrader$committedTarget = target;
        ic2Upgrader$committedChance = chance;
        return chance;
    }

    @ModifyExpressionValue(method = "syncToClient", at = @At(value = "INVOKE", target =
            "Lnet/execheinz/upgrader/value/ItemValues;unitValue(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/Item;)D"), remap = false)
    private double ic2upgrader$bookTargetPrice(double original) {
        return ic2upgrader$bookTarget == null ? original : BookTargets.value(player.level(), ic2upgrader$bookTarget);
    }

    @ModifyExpressionValue(method = "syncToClient", at = @At(value = "INVOKE", target =
            "Lnet/execheinz/upgrader/value/UpgradeOdds;chance(DD)D"), remap = false)
    private double ic2upgrader$exactBookPreviewChance(double original) {
        return ic2upgrader$bookTarget == null ? original
                : UpgradeOdds.chance(ItemValues.stackValue(player.level(), getInputStack()),
                        BookTargets.value(player.level(), ic2upgrader$bookTarget) * targetCount);
    }

    @Inject(method = "sendCatalog", at = @At("RETURN"), remap = false)
    private void ic2upgrader$sendBookCatalog(ServerPlayer sender, CallbackInfo callback) {
        BookTargets.sendCatalog(sender, (UpgraderMenu) (Object) this);
        ic2upgrader$catalogRevision = BookTargets.catalogRevision();
    }

    @Inject(method = "broadcastChanges", at = @At("HEAD"), remap = false)
    private void ic2upgrader$refreshChangedBookPrices(CallbackInfo callback) {
        if (player instanceof ServerPlayer sender && ic2upgrader$catalogRevision != Long.MIN_VALUE
                && ic2upgrader$catalogRevision != BookTargets.catalogRevision()) {
            BookTargets.sendCatalog(sender, (UpgraderMenu) (Object) this);
            ic2upgrader$catalogRevision = BookTargets.catalogRevision();
        }
    }

    @Inject(method = "syncToClient", at = @At("RETURN"), remap = false)
    private void ic2upgrader$syncBookSelection(ServerPlayer sender, boolean force, CallbackInfo callback) {
        if (force || !ic2upgrader$bookSynced
                || !java.util.Objects.equals(ic2upgrader$bookTarget, ic2upgrader$lastSyncedBook)) {
            BookTargets.sendSelected(sender, (UpgraderMenu) (Object) this, ic2upgrader$bookTarget);
            ic2upgrader$lastSyncedBook = ic2upgrader$bookTarget;
            ic2upgrader$bookSynced = true;
        }
    }

    @ModifyExpressionValue(method = "applyResult", at = @At(value = "NEW", target =
            "(Lnet/minecraft/world/level/ItemLike;I)Lnet/minecraft/world/item/ItemStack;"), remap = false)
    private ItemStack ic2upgrader$produceExactBook(ItemStack original) {
        return ic2upgrader$bookTarget == null ? original : BookTargets.create(player.level(), ic2upgrader$bookTarget);
    }

    @Inject(method = "applyResult", at = @At("HEAD"), remap = false)
    private void ic2Upgrader$beforeResult(CallbackInfo callback) {
        ic2Upgrader$applyingWin = pendingWin && target != null && !getInputStack().isEmpty();
    }

    @Inject(method = "applyResult", at = @At("RETURN"), remap = false)
    private void ic2Upgrader$announceCompletedWin(CallbackInfo callback) {
        ItemStack spent = ic2Upgrader$committedInput;
        Item expected = ic2Upgrader$committedTarget;
        double chance = ic2Upgrader$committedChance;
        boolean applied = ic2Upgrader$applyingWin;
        // Clear before calling chat hooks, so closing, ticking or reentry cannot repeat a message.
        ic2Upgrader$committedInput = ItemStack.EMPTY;
        ic2Upgrader$committedTarget = null;
        ic2Upgrader$committedChance = 0;
        ic2Upgrader$applyingWin = false;

        ItemStack won = getInputStack();
        if (applied && !spent.isEmpty() && expected != null && won.is(expected)
                && player instanceof ServerPlayer winner) {
            UpgradeAnnouncements.broadcast(winner, won.copy(), spent, chance);
        }
    }

    @Inject(method = "removed", at = @At("RETURN"), remap = false)
    private void ic2upgrader$clearClosedMenu(Player player, CallbackInfo callback) {
        ic2upgrader$bookTarget = null;
        ic2upgrader$lastSyncedBook = null;
    }
}
