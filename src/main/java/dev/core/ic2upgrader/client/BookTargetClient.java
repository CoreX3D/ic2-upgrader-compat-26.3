package dev.core.ic2upgrader.client;

import dev.core.ic2upgrader.BookTargetMenu;
import dev.core.ic2upgrader.BookTargets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.execheinz.upgrader.client.ClientCatalog;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** Client mirrors only the variants and prices advertised by the current server and menu. */
public final class BookTargetClient implements ClientModInitializer {
    private static UpgraderMenu owner;
    private static Map<BookTargets.Key, Double> prices = Map.of();
    private static long revision;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(BookTargets.CatalogPayload.TYPE, (payload, context) -> {
            if (!(context.player().containerMenu instanceof UpgraderMenu menu)
                    || menu.containerId != payload.containerId()) return;
            Map<BookTargets.Key, Double> accepted = new LinkedHashMap<>();
            for (BookTargets.Entry entry : payload.entries()) {
                if (Double.isFinite(entry.value()) && entry.value() > 0
                        && BookTargets.isLegal(context.client().level, entry.key())) {
                    accepted.put(entry.key(), entry.value());
                }
            }
            owner = menu;
            prices = Map.copyOf(accepted);
            revision++;
        });
        ClientPlayNetworking.registerGlobalReceiver(BookTargets.SelectedPayload.TYPE, (payload, context) -> {
            if (!(context.player().containerMenu instanceof UpgraderMenu menu)
                    || menu.containerId != payload.containerId()) return;
            BookTargets.Key key = payload.key();
            if (key != null && (!BookTargets.isLegal(context.client().level, key)
                    || menu.getTarget() != Items.ENCHANTED_BOOK)) return;
            ((BookTargetMenu) menu).ic2upgrader$setBookTarget(key);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    private static void clear() {
        owner = null;
        prices = Map.of();
        revision++;
    }

    private static boolean current() {
        var player = Minecraft.getInstance().player;
        return player != null && player.containerMenu == owner;
    }

    public static long revision() { return revision; }

    public static List<ItemStack> augmentCatalog(List<ItemStack> original) {
        List<ItemStack> result = new ArrayList<>(original.size() + prices.size());
        for (ItemStack stack : original) {
            if (!stack.is(Items.ENCHANTED_BOOK)) result.add(stack);
        }
        if (!current() || prices.isEmpty()) return List.copyOf(result);
        var level = Minecraft.getInstance().level;
        prices.keySet().stream().sorted(java.util.Comparator.comparing(BookTargets.Key::enchantmentId)
                .thenComparingInt(BookTargets.Key::level)).forEach(key -> {
                    ItemStack stack = BookTargets.create(level, key);
                    if (!stack.isEmpty()) result.add(stack);
                });
        return List.copyOf(result);
    }

    /** True means this is a book-variant click and no item-only vanilla packet should follow it. */
    public static boolean select(ItemStack stack) {
        BookTargets.Key key = key(stack);
        if (key == null) return false;
        if (current() && prices.containsKey(key) && !owner.isSpinning()
                && ClientPlayNetworking.canSend(BookTargets.SelectPayload.TYPE)) {
            ClientPlayNetworking.send(new BookTargets.SelectPayload(owner.containerId, key));
        }
        return true;
    }

    public static double value(ItemStack stack) {
        BookTargets.Key key = key(stack);
        return key != null && current() ? prices.getOrDefault(key, 0.0) : ClientCatalog.value(stack.getItem());
    }

    public static Component name(ItemStack stack) {
        if (stack.is(Items.ENCHANTED_BOOK)) {
            ItemEnchantments stored = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            if (stored.size() == 1) {
                var entry = stored.entrySet().iterator().next();
                return Enchantment.getFullname(entry.getKey(), entry.getIntValue());
            }
        }
        return stack.getHoverName();
    }

    public static boolean matches(ItemStack stack, String lowerQuery) {
        if (lowerQuery == null || lowerQuery.isBlank()) return true;
        String query = lowerQuery.toLowerCase(Locale.ROOT).trim();
        BookTargets.Key key = key(stack);
        String haystack = name(stack).getString() + " " + stack.getHoverName().getString()
                + " " + BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key != null) {
            var stored = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            var enchantment = stored.keySet().iterator().next();
            haystack += " " + key.enchantmentId() + " " + key.level()
                    + " " + enchantment.value().description().getString() + " " + key.level();
        }
        return haystack.toLowerCase(Locale.ROOT).contains(query);
    }

    public static ItemStack preview(UpgraderMenu menu, ItemStack original) {
        BookTargets.Key selected = ((BookTargetMenu) menu).ic2upgrader$getBookTarget();
        if (selected == null || !original.is(Items.ENCHANTED_BOOK)) return original;
        ItemStack book = BookTargets.create(Minecraft.getInstance().level, selected);
        return book.isEmpty() ? original : book;
    }

    public static BookTargets.Key key(ItemStack stack) {
        if (!stack.is(Items.ENCHANTED_BOOK)) return null;
        ItemEnchantments stored = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (stored.size() != 1) return null;
        var entry = stored.entrySet().iterator().next();
        var resource = entry.getKey().unwrapKey().orElse(null);
        return resource == null ? null : new BookTargets.Key(resource.identifier().toString(), entry.getIntValue());
    }
}
