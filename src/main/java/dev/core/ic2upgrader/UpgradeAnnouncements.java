package dev.core.ic2upgrader;

import java.math.BigDecimal;
import java.math.RoundingMode;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** A server announcement of an already completed exchange; it never changes its outcome. */
public final class UpgradeAnnouncements {
    private UpgradeAnnouncements() {}

    public static void broadcast(ServerPlayer winner, ItemStack won, ItemStack spent, double chance) {
        if (won.isEmpty() || spent.isEmpty()) return;
        winner.level().getServer().getPlayerList().broadcastSystemMessage(
                message(winner.getDisplayName(), won, spent, chance), false);
    }

    public static Component message(Component winner, ItemStack won, ItemStack spent, double chance) {
        return Component.translatableWithFallback("chat.ic2_upgrader_compat.win",
                "%1$s %2$s выиграл %3$s\n  Шанс: %4$s  •  Потрачено: %5$s",
                Component.translatableWithFallback("chat.ic2_upgrader_compat.badge", "✦ [Апгрейд]")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                winner.copy().withStyle(ChatFormatting.WHITE),
                item(won).withStyle(ChatFormatting.GREEN),
                Component.literal(formatChance(chance)).withStyle(ChatFormatting.GOLD),
                item(spent).withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY);
    }

    private static MutableComponent item(ItemStack stack) {
        // getDisplayName retains the real item tooltip (including custom components) on hover.
        return Component.empty().append(stack.copy().getDisplayName())
                .append(Component.literal(" ×" + stack.getCount()).withStyle(ChatFormatting.WHITE));
    }

    public static String formatChance(double chance) {
        if (!Double.isFinite(chance)) chance = 0;
        double percent = Math.clamp(chance, 0.0, 1.0) * 100.0;
        BigDecimal value = BigDecimal.valueOf(percent);
        int scale = percent > 0 && percent < 0.005 ? 6 : 2;
        BigDecimal rounded = value.setScale(scale, RoundingMode.HALF_UP);
        if (percent > 0 && rounded.signum() == 0) return "<0,000001%";
        if (scale > 2) rounded = rounded.stripTrailingZeros();
        return rounded.toPlainString().replace('.', ',') + "%";
    }
}
