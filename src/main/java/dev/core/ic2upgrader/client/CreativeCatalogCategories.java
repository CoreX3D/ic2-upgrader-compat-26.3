package dev.core.ic2upgrader.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Uses creative tabs only as an index into the server's permitted upgrade targets. */
@Environment(EnvType.CLIENT)
public final class CreativeCatalogCategories {
    private CreativeCatalogCategories() {}

    /**
     * Builds on the client thread while connected to a world. Native tab generation never opens a
     * creative inventory or changes the player's game mode, inventory, permissions or current menu.
     */
    public static List<Category> build(Minecraft client, List<ItemStack> serverCatalog) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(serverCatalog, "serverCatalog");

        Map<Item, List<ItemStack>> permitted = new LinkedHashMap<>();
        List<ItemStack> allItems = new ArrayList<>();
        for (ItemStack stack : serverCatalog) {
            if (stack != null && !stack.isEmpty()) {
                List<ItemStack> variants = permitted.computeIfAbsent(stack.getItem(),
                        ignored -> new ArrayList<>());
                if (variants.stream().noneMatch(existing ->
                        ItemStack.isSameItemSameComponents(existing, stack))) {
                    ItemStack entry = stack.copyWithCount(1);
                    variants.add(entry);
                    allItems.add(entry);
                }
            }
        }

        List<Category> categories = new ArrayList<>();
        categories.add(new Category("all",
                Component.translatable("gui.ic2_upgrader_compat.category.all"),
                new ItemStack(Items.COMPASS), allItems));
        if (client.player == null || client.level == null) {
            return List.copyOf(categories);
        }

        boolean operatorItems = client.player.canUseGameMasterBlocks()
                && client.options.operatorItemsTab().get();
        if (CreativeModeTabs.tryRebuildTabContents(client.player.connection.enabledFeatures(),
                operatorItems, client.level.registryAccess())) {
            // Match the native creative screen's cache refresh. Otherwise opening this picker first
            // would fill the tab cache without populating creative name/tag searches for later use.
            List<ItemStack> searchItems = List.copyOf(CreativeModeTabs.searchTab().getDisplayItems());
            client.player.connection.searchTrees().updateCreativeTooltips(
                    client.level.registryAccess(), searchItems);
            client.player.connection.searchTrees().updateCreativeTags(searchItems);
        }

        Set<Item> categorized = new HashSet<>();
        for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
            if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
                continue;
            }
            Identifier id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (id == null || (!operatorItems && id.equals(CreativeModeTabs.OP_BLOCKS.identifier()))) {
                continue;
            }

            List<ItemStack> matches = new ArrayList<>();
            Set<Item> seen = new HashSet<>();
            for (ItemStack creativeStack : tab.getDisplayItems()) {
                Item item = creativeStack.getItem();
                List<ItemStack> permittedVariants = permitted.get(item);
                if (permittedVariants != null && seen.add(item)) {
                    // Only server entries are selectable. Creative-only charge, tank, potion and
                    // book components are never copied into the authorized target catalogue.
                    matches.addAll(permittedVariants);
                    categorized.add(item);
                }
            }
            if (!matches.isEmpty()) {
                categories.add(new Category(id.toString(), tab.getDisplayName(),
                        tab.getIconItem(), matches));
            }
        }

        List<ItemStack> other = new ArrayList<>();
        for (ItemStack entry : allItems) {
            if (!categorized.contains(entry.getItem())) {
                other.add(entry);
            }
        }
        if (!other.isEmpty()) {
            categories.add(new Category("other",
                    Component.translatable("gui.ic2_upgrader_compat.category.other"),
                    new ItemStack(Items.CHEST), other));
        }
        return List.copyOf(categories);
    }

    /**
     * A category owns copies of its stacks and an unmodifiable item list. Callers display the item
     * stacks as read-only values and copy them before changing counts or components.
     */
    public record Category(String id, Component title, ItemStack icon, List<ItemStack> items) {
        public Category {
            Objects.requireNonNull(id, "id");
            title = Objects.requireNonNull(title, "title").copy();
            icon = Objects.requireNonNull(icon, "icon").copy();
            items = Objects.requireNonNull(items, "items").stream().map(ItemStack::copy).toList();
        }

        @Override
        public Component title() {
            return title.copy();
        }

        @Override
        public ItemStack icon() {
            return icon.copy();
        }
    }
}
