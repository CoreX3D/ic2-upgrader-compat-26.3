package dev.core.ic2upgrader.mixin;

import dev.core.ic2upgrader.Ic2Pricing;
import dev.core.ic2upgrader.Ic2StackRules;
import dev.core.ic2upgrader.BookTargets;
import dev.core.ic2upgrader.EquipmentEnchantments;
import java.util.Map;
import net.execheinz.upgrader.UpgraderConfig;
import net.execheinz.upgrader.value.ItemValues;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ItemValues.class, remap = false)
public abstract class ItemValuesMixin {
    @Inject(method = "invalidate", at = @At("HEAD"), remap = false)
    private static void ic2Upgrader$refreshBookCatalog(CallbackInfo callback) {
        BookTargets.invalidateCatalog();
    }

    @Inject(method = "compute", at = @At("RETURN"), cancellable = true, remap = false)
    private static void ic2Upgrader$recipes(Level level, CallbackInfoReturnable<Map<Item, Double>> callback) {
        callback.setReturnValue(Ic2Pricing.adapt(level, callback.getReturnValue()));
    }

    @Inject(method = "isBlacklisted", at = @At("RETURN"), cancellable = true, remap = false)
    private static void ic2Upgrader$unsupportedTargets(Item item, CallbackInfoReturnable<Boolean> callback) {
        if (item == Items.ENCHANTED_BOOK) {
            // Upgrader blocks component-bearing books by default. Validated book variants now
            // have their own server protocol; an administrator's explicit blacklist still wins.
            callback.setReturnValue(UpgraderConfig.blacklist.contains("minecraft:enchanted_book"));
            return;
        }
        if (!callback.getReturnValueZ() && Ic2StackRules.isUnsupportedTarget(item)) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "stackValue", at = @At("RETURN"), cancellable = true, remap = false)
    private static void ic2Upgrader$wear(Level level, ItemStack stack, CallbackInfoReturnable<Double> callback) {
        double value = Ic2StackRules.adjustStackValue(level, stack, callback.getReturnValueD());
        value = BookTargets.stackValue(level, stack, value);
        callback.setReturnValue(EquipmentEnchantments.stackValue(level, stack, value));
    }
}
