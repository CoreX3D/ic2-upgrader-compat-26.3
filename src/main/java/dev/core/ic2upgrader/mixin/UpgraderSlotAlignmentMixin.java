package dev.core.ic2upgrader.mixin;

import net.execheinz.upgrader.menu.UpgraderMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Centers native 16px slot contents within Upgrader's 18px slot interiors. */
@Mixin(value = UpgraderMenu.class, remap = false)
public abstract class UpgraderSlotAlignmentMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target =
            "Lnet/execheinz/upgrader/menu/UpgraderMenu$2;<init>(Lnet/execheinz/upgrader/menu/UpgraderMenu;Lnet/minecraft/world/Container;III)V"),
            index = 3, require = 1, allow = 1, remap = false)
    private int ic2upgrader$centerInputX(int x) {
        return x + 1;
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target =
            "Lnet/execheinz/upgrader/menu/UpgraderMenu$2;<init>(Lnet/execheinz/upgrader/menu/UpgraderMenu;Lnet/minecraft/world/Container;III)V"),
            index = 4, require = 1, allow = 1, remap = false)
    private int ic2upgrader$centerInputY(int y) {
        return y + 1;
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/inventory/Slot;<init>(Lnet/minecraft/world/Container;III)V"),
            index = 2, require = 2, allow = 2, remap = false)
    private int ic2upgrader$centerInventoryX(int x) {
        return x + 1;
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/inventory/Slot;<init>(Lnet/minecraft/world/Container;III)V"),
            index = 3, require = 2, allow = 2, remap = false)
    private int ic2upgrader$centerInventoryY(int y) {
        return y + 1;
    }
}
