package dev.core.ic2upgrader.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.core.ic2upgrader.client.BookTargetClient;
import java.util.List;
import net.execheinz.upgrader.client.UpgraderScreen;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.execheinz.upgrader.network.ServerboundSetTargetPacket;
import net.execheinz.upgrader.network.UpgraderNetwork;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The main card and its native tooltip must display the exact selected enchantment. */
@Mixin(value = UpgraderScreen.class, remap = false)
public abstract class BookTargetScreenMixin extends AbstractContainerScreen<UpgraderMenu> {
    @Shadow private List<ItemStack> catalog;
    @Shadow private void ensureCatalog() { throw new AssertionError("Mixin shadow"); }
    @Shadow private void playUi(SoundEvent event, float pitch) { throw new AssertionError("Mixin shadow"); }

    protected BookTargetScreenMixin(UpgraderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 288, 240);
    }

    @ModifyExpressionValue(method = {"renderCardText", "renderTargetTooltip"}, at = @At(value = "NEW", target =
            "(Lnet/minecraft/world/level/ItemLike;)Lnet/minecraft/world/item/ItemStack;"), remap = false)
    private ItemStack ic2upgrader$showExactBook(ItemStack original) {
        return BookTargetClient.preview(menu, original);
    }

    @Inject(method = "applyPreset", at = @At("HEAD"), cancellable = true, remap = false)
    private void ic2upgrader$choosePresetVariant(double desiredValue, CallbackInfo callback) {
        ensureCatalog();
        if (catalog != null && desiredValue > 0) {
            ItemStack chosen = ItemStack.EMPTY;
            double nearest = Double.MAX_VALUE;
            for (ItemStack stack : BookTargetClient.augmentCatalog(catalog)) {
                // A plain enchanted-book item has no enchantment and is never a useful prize.
                if (stack.is(Items.ENCHANTED_BOOK) && BookTargetClient.key(stack) == null) continue;
                double difference = Math.abs(BookTargetClient.value(stack) - desiredValue);
                if (difference < nearest) {
                    nearest = difference;
                    chosen = stack;
                }
            }
            if (!chosen.isEmpty()) {
                if (!BookTargetClient.select(chosen)) {
                    UpgraderNetwork.toServer(ServerboundSetTargetPacket.of(chosen.getItem()));
                }
                playUi(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f);
            }
        }
        callback.cancel();
    }
}
