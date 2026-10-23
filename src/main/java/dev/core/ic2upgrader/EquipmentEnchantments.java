package dev.core.ic2upgrader;

import ic2.api.item.IElectricItem;
import net.execheinz.upgrader.value.ItemValues;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;

/** Prices the actual enchantments on an input item using the existing enchanted-book economy. */
public final class EquipmentEnchantments {
    private EquipmentEnchantments() { }

    /**
     * Adds each active enchantment once per item, after the material value and IC2 wear rules.
     * Stored enchantments belong only to the separate enchanted-book valuation path.
     */
    public static double stackValue(Level level, ItemStack stack, double original) {
        if (level == null || stack.isEmpty() || stack.is(Items.BOOK) || stack.is(Items.ENCHANTED_BOOK)
                || !Double.isFinite(original) || original <= 0
                || ItemValues.isBlacklisted(stack.getItem())) return original;

        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (enchantments.isEmpty()) return original;

        double countAndWear = stack.getCount() * remainingDurability(stack);
        double value = original;
        for (var entry : enchantments.entrySet()) {
            var key = entry.getKey().unwrapKey().orElse(null);
            if (key == null) continue;
            // Resolve through the current registry: invalid levels and unknown enchantments have
            // no value. The book pricing also preserves explicit book bans and zero-price overrides.
            double bookValue = BookTargets.value(level,
                    new BookTargets.Key(key.identifier().toString(), entry.getIntValue()));
            if (!Double.isFinite(bookValue) || bookValue <= 0) continue;
            double bonus = bookValue * countAndWear;
            if (!Double.isFinite(bonus) || bonus > Double.MAX_VALUE - value) return Double.MAX_VALUE;
            value += bonus;
        }
        return value;
    }

    private static double remainingDurability(ItemStack stack) {
        // Charge is recoverable energy, not consumed equipment life.
        if (stack.getItem() instanceof IElectricItem || !stack.isDamageableItem()
                || stack.getMaxDamage() <= 0) return 1.0;
        return Math.max(0.05, 1.0 - Math.clamp((double) stack.getDamageValue() / stack.getMaxDamage(), 0.0, 1.0));
    }
}
