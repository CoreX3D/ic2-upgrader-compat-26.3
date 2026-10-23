package dev.core.ic2upgrader.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.core.ic2upgrader.BookTargetMenu;
import dev.core.ic2upgrader.BookTargets;
import dev.core.ic2upgrader.client.BookTargetClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.execheinz.upgrader.UpgraderConfig;
import net.execheinz.upgrader.client.UpgraderScreen;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.execheinz.upgrader.value.UpgradeOdds;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** Exercises the server-authorized book variants through the actual picker and upgrade packets. */
@SuppressWarnings("UnstableApiUsage")
public final class BookUpgradeClientChecks {
    private BookUpgradeClientChecks() { }

    public static void validate(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, List<String> evidence) {
        Saved saved = server.computeOnServer(instance -> {
            UpgraderMenu menu = serverMenu(connection);
            List<ItemStack> inventory = new ArrayList<>();
            var playerInventory = connection.getServerPlayer().getInventory();
            for (int i = 0; i < playerInventory.getContainerSize(); i++) {
                inventory.add(playerInventory.getItem(i).copy());
            }
            return new Saved(menu.getInputStack().copy(), menu.getCarried().copy(), inventory,
                    UpgraderConfig.minChance, UpgraderConfig.maxChance, UpgraderConfig.spinTicks,
                    UpgraderConfig.attemptLimit);
        });
        ChatComponent.State chat = context.computeOnClient(client -> client.gui.hud.getChat().storeState());
        try {
            context.waitFor(client -> !BookTargetClient.augmentCatalog(List.of()).isEmpty(), 1200);
            require(saved.input().is(item("ic2:iron_plate")) && saved.input().getCount() == 16,
                    "Expected the existing sixteen-plate native input fixture");

            Map<BookTargets.Key, Double> expected = server.computeOnServer(instance -> {
                var level = connection.getServerLevel();
                Map<BookTargets.Key, Double> result = new LinkedHashMap<>();
                // Determine legal levels independently from the catalogue implementation.
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements().forEach(holder -> {
                    if (holder.value().getSupportedItems().stream()
                            .noneMatch(supported -> supported.value().isEnabled(level.enabledFeatures()))) return;
                    for (int n = Math.max(1, holder.value().getMinLevel());
                            n <= Math.min(Enchantment.MAX_LEVEL, holder.value().getMaxLevel()); n++) {
                        BookTargets.Key key = new BookTargets.Key(holder.key().identifier().toString(), n);
                        result.put(key, BookTargets.value(level, key));
                    }
                });
                require(!result.isEmpty(), "The world has no legal enchantments for the client fixture");
                return result;
            });

            openPicker(context);
            replaceSearch(context, "");
            context.runOnClient(client -> {
                require(field(screen(client), "ic2upgrader$selectedCategory", String.class).equals("all"),
                        "Book fixture must inspect the actual All category");
                Map<BookTargets.Key, Double> actual = new LinkedHashMap<>();
                for (ItemStack stack : filtered(screen(client))) {
                    if (!stack.is(Items.ENCHANTED_BOOK)) continue;
                    BookTargets.Key key = BookTargetClient.key(stack);
                    require(key != null && stored(stack).size() == 1 && stack.getCount() == 1,
                            "Picker contains a blank, combined or malformed enchanted-book target");
                    require(actual.put(key, BookTargetClient.value(stack)) == null,
                            "Picker duplicated a book enchantment/level " + key);
                }
                require(actual.keySet().equals(expected.keySet()),
                        "Native picker lost or invented legal enchantment levels: expected="
                                + expected.keySet() + ", actual=" + actual.keySet());
                for (var entry : actual.entrySet()) {
                    require(Double.compare(entry.getValue(), expected.get(entry.getKey())) == 0,
                            "The actual book-catalogue packet changed server price for " + entry.getKey());
                }
                evidence.add("PASS: actual All category has " + actual.size()
                        + " distinct single-enchantment book targets covering every enabled registry enchantment"
                        + " and every legal level; every client price equals its server quote.");
            });

            validatePreset(context, server, connection, evidence);
            openPicker(context);
            validateSearch(context, "Шипы", "minecraft:thorns", 3, evidence);
            validateSearch(context, "minecraft:protection", "minecraft:protection", 4, evidence);
            // Also exercise the user's natural Russian search term, even if it matches other protections.
            validateSearch(context, "Защита", "minecraft:protection", 4, evidence);
            validateNumericSearch(context, "Шипы 3", new BookTargets.Key("minecraft:thorns", 3), evidence);
            validateNumericSearch(context, "Защита 4", new BookTargets.Key("minecraft:protection", 4), evidence);
            replaceSearch(context, "Шипы");
            moveRelative(context, 26 + 2 * 18, 52);
            context.waitTicks(3);
            evidence.add("Russian Thorns I/II/III search with native item tooltip: "
                    + context.takeScreenshot(TestScreenshotOptions.of("upgrader-books-thorns-search")
                            .disableCounterPrefix()).toAbsolutePath());

            BookTargets.Key first = new BookTargets.Key("minecraft:thorns", 1);
            BookTargets.Key selected = new BookTargets.Key("minecraft:thorns", 3);
            selectFilteredBook(context, server, connection, first, expected.get(first));
            validateSelectedBook(context, server, connection, first, expected.get(first), evidence);
            openPicker(context);
            replaceSearch(context, "Шипы");
            selectFilteredBook(context, server, connection, selected, expected.get(selected));
            validateSelectedBook(context, server, connection, selected, expected.get(selected), evidence);
            moveRelative(context, 238, 42);
            context.waitTicks(3);
            evidence.add("Selected Thorns III preview and its real tooltip: "
                    + context.takeScreenshot(TestScreenshotOptions.of("upgrader-books-thorns-iii-selected")
                            .disableCounterPrefix()).toAbsolutePath());

            // Force only this disposable world's one real button action to win, then restore all globals.
            context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
            server.runOnServer(instance -> {
                UpgraderConfig.minChance = 1;
                UpgraderConfig.maxChance = 1;
                UpgraderConfig.spinTicks = 2;
                UpgraderConfig.attemptLimit = 0;
            });
            clickRelative(context, 78, 133);
            connection.waitForServerboundPackets();
            server.waitFor(instance -> serverMenu(connection).getInputStack().is(Items.ENCHANTED_BOOK)
                    && !serverMenu(connection).isSpinning());
            connection.waitForClientboundPackets();
            context.waitFor(client -> screen(client).getMenu().getInputStack().is(Items.ENCHANTED_BOOK)
                    && !field(screen(client), "spinning", Boolean.class), 1200);
            server.runOnServer(instance -> {
                var menu = serverMenu(connection);
                ItemStack output = menu.getInputStack();
                require(output.getCount() == 1 && selected.equals(BookTargetClient.key(output)),
                        "Winning book did not preserve the exact selected enchantment and level");
                require(menu.getCarried().isEmpty(), "A book upgrade left a carried duplicate");
                var playerInventory = connection.getServerPlayer().getInventory();
                for (int i = 0; i < saved.inventory().size(); i++) {
                    require(ItemStack.matches(saved.inventory().get(i), playerInventory.getItem(i)),
                            "Book upgrade mutated or duplicated inventory slot " + i);
                }
            });
            context.runOnClient(client -> require(selected.equals(
                            BookTargetClient.key(screen(client).getMenu().getInputStack())),
                    "The output slot packet lost the book's stored enchantment"));

            context.waitFor(client -> !winMessages(client).isEmpty(), 1200);
            context.runOnClient(client -> {
                List<GuiMessage> messages = winMessages(client);
                require(messages.size() == 1 && messages.getFirst().source() == GuiMessageSource.SYSTEM_SERVER,
                        "Book win did not arrive as exactly one real server system announcement");
                TranslatableContents outer = (TranslatableContents) messages.getFirst().content().getContents();
                require(outer.getArgs().length == 5 && outer.getArgs()[2] instanceof Component,
                        "Book announcement lost the structured won-item argument");
                Component won = (Component) outer.getArgs()[2];
                require(hasBookHover(won, selected),
                        "The actual announcement hover lost Thorns III stored enchantments");
                Component spent = (Component) outer.getArgs()[4];
                require(hasExactHover(spent, saved.input()),
                        "The actual announcement did not retain the sixteen spent iron plates");
                require(((Component) outer.getArgs()[3]).getString().equals("100,00%"),
                        "The book announcement used an uncommitted chance");
                evidence.add("PASS: native Upgrade button and actual two-tick server spin consumed exactly"
                        + " sixteen iron plates and created one Thorns III book, preserved it in the actual"
                        + " client slot packet and SYSTEM_SERVER announcement item hover, with no duplicates.");
            });
        } finally {
            context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
            server.runOnServer(instance -> {
                UpgraderConfig.minChance = saved.minChance();
                UpgraderConfig.maxChance = saved.maxChance();
                UpgraderConfig.spinTicks = saved.spinTicks();
                UpgraderConfig.attemptLimit = saved.attemptLimit();
                UpgraderMenu menu = serverMenu(connection);
                menu.getSlot(0).set(saved.input().copy());
                menu.setCarried(saved.carried().copy());
                var inventory = connection.getServerPlayer().getInventory();
                for (int i = 0; i < saved.inventory().size(); i++) {
                    inventory.setItem(i, saved.inventory().get(i).copy());
                }
                menu.broadcastChanges();
            });
            connection.waitForClientboundPackets();
            context.runOnClient(client -> client.gui.hud.getChat().restoreState(chat));
        }

        // Select a normal material through its real cell, proving a book choice cannot leak into it.
        openPicker(context);
        replaceSearch(context, "ic2:copper_plate");
        int copperIndex = context.computeOnClient(client -> {
            List<ItemStack> entries = filtered(screen(client));
            for (int i = 0; i < entries.size(); i++) if (entries.get(i).is(item("ic2:copper_plate"))) return i;
            throw new AssertionError("Normal IC2 material missing after the book fixture");
        });
        clickGrid(context, copperIndex);
        connection.waitForServerboundPackets();
        server.waitFor(instance -> serverMenu(connection).getTarget() == item("ic2:copper_plate")
                && ((BookTargetMenu) serverMenu(connection)).ic2upgrader$getBookTarget() == null);
        connection.waitForClientboundPackets();
        context.waitFor(client -> screen(client).getMenu().getTarget() == item("ic2:copper_plate")
                && ((BookTargetMenu) screen(client).getMenu()).ic2upgrader$getBookTarget() == null);
        evidence.add("PASS: selecting an ordinary IC2 material clears the book variant on both sides;"
                + " all test chance/spin settings, input, inventory, carried stack and prior chat restored.");
    }

    private static void validatePreset(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, List<String> evidence) {
        PresetChoice chosen = context.computeOnClient(client -> {
            UpgraderScreen gui = screen(client);
            List<?> original = field(gui, "catalog", List.class);
            List<ItemStack> catalog = BookTargetClient.augmentCatalog(original.stream().map(ItemStack.class::cast).toList());
            List<Double> earlier = new ArrayList<>();
            for (ItemStack stack : catalog) {
                double value = BookTargetClient.value(stack);
                BookTargets.Key key = BookTargetClient.key(stack);
                if (key != null && earlier.stream().noneMatch(price -> Double.compare(price, value) == 0)) {
                    return new PresetChoice(key, value);
                }
                earlier.add(value);
            }
            throw new AssertionError("No book price can be chosen by native nearest-value presets without an earlier tie");
        });
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitFor(client -> !field(screen(client), "pickerOpen", Boolean.class));
        context.runOnClient(client -> {
            try {
                // This is the same native handler used by the preset buttons. It sends the real selection packet.
                Method preset = UpgraderScreen.class.getDeclaredMethod("applyPreset", double.class);
                preset.setAccessible(true);
                preset.invoke(screen(client), chosen.price());
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Could not invoke the native nearest-value preset handler", exception);
            }
        });
        connection.waitForServerboundPackets();
        server.waitFor(instance -> chosen.key().equals(
                ((BookTargetMenu) serverMenu(connection)).ic2upgrader$getBookTarget()));
        connection.waitForClientboundPackets();
        context.waitFor(client -> chosen.key().equals(
                ((BookTargetMenu) screen(client).getMenu()).ic2upgrader$getBookTarget()));
        validateSelectedBook(context, server, connection, chosen.key(), chosen.price(), evidence);
        evidence.add("PASS: the actual native nearest-value preset handler selects exact book variant "
                + chosen.key() + " through the real client/server selection packet, respecting earlier price ties.");
    }

    private static void validateNumericSearch(ClientGameTestContext context, String query,
            BookTargets.Key expected, List<String> evidence) {
        replaceSearch(context, query);
        context.runOnClient(client -> {
            List<ItemStack> entries = filtered(screen(client));
            require(entries.size() == 1 && expected.equals(BookTargetClient.key(entries.getFirst())),
                    "Numeric localized book search did not uniquely find " + expected + ": " + query);
        });
        evidence.add("PASS: numeric Russian search '" + query + "' finds exactly " + expected + ".");
    }

    private static void validateSearch(ClientGameTestContext context, String query, String enchantment,
            int maximum, List<String> evidence) {
        replaceSearch(context, query);
        context.runOnClient(client -> {
            List<ItemStack> matches = filtered(screen(client)).stream()
                    .filter(stack -> stack.is(Items.ENCHANTED_BOOK))
                    .filter(stack -> {
                        BookTargets.Key key = BookTargetClient.key(stack);
                        return key != null && key.enchantmentId().equals(enchantment);
                    }).toList();
            require(matches.size() == maximum, "Search " + query + " did not expose every level of " + enchantment
                    + "; " + searchDiagnostic(screen(client)));
            double previous = 0;
            for (int n = 1; n <= maximum; n++) {
                ItemStack stack = matches.get(n - 1);
                require(BookTargetClient.key(stack).level() == n,
                        "Search did not order " + enchantment + " by legal enchantment level");
                double value = BookTargetClient.value(stack);
                require(Double.isFinite(value) && value > previous,
                        "Higher book enchantments did not cost strictly more");
                previous = value;
            }
            evidence.add("PASS: native search '" + query + "' exposes " + enchantment + " levels 1.."
                    + maximum + " with strictly increasing authoritative prices "
                    + matches.stream().map(stack -> BookTargetClient.key(stack).level() + "="
                            + BookTargetClient.value(stack)).toList() + ".");
        });
    }

    private static void validateSelectedBook(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, BookTargets.Key expected, double quote, List<String> evidence) {
        server.runOnServer(instance -> {
            UpgraderMenu menu = serverMenu(connection);
            require(expected.equals(((BookTargetMenu) menu).ic2upgrader$getBookTarget()),
                    "Actual grid event selected a different server book variant");
            require(field(menu, "lastSyncedTargetValue", Long.class) == Math.round(quote),
                    "Server odds still price the item-only enchanted book");
        });
        context.runOnClient(client -> {
            UpgraderScreen gui = screen(client);
            require(expected.equals(((BookTargetMenu) gui.getMenu()).ic2upgrader$getBookTarget()),
                    "The server acknowledgement lost its client book variant");
            ItemStack preview = BookTargetClient.preview(gui.getMenu(), new ItemStack(Items.ENCHANTED_BOOK));
            require(expected.equals(BookTargetClient.key(preview)), "Target preview forgot the selected book level");
            List<Component> tooltip = preview.getTooltipLines(Item.TooltipContext.of(client.level),
                    client.player, TooltipFlag.NORMAL);
            String enchantmentName = BookTargetClient.name(preview).getString();
            require(tooltip.stream().anyMatch(line -> line.getString().contains(enchantmentName)),
                    "Native target tooltip has no actual selected enchantment");
            require(field(gui, "targetValue", Long.class) == Math.round(quote)
                            && field(gui, "targetCount", Integer.class) == 1,
                    "Selected book price/count did not survive the actual synchronization packet");
            double input = field(gui, "inputValue", Long.class);
            require(Math.abs(field(gui, "chance", Float.class) - UpgradeOdds.chance(input, Math.round(quote))) < 1e-6,
                    "Actual book chance still uses an item-only value or stale enchantment level");
            evidence.add("Native book selection " + expected + ": price=" + quote + ", chance="
                    + field(gui, "chance", Float.class) + ", preview/tooltip='" + enchantmentName + "'.");
        });
    }

    private static void selectFilteredBook(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, BookTargets.Key expected, double price) {
        int index = context.computeOnClient(client -> {
            List<ItemStack> entries = filtered(screen(client));
            for (int i = 0; i < entries.size(); i++) if (expected.equals(BookTargetClient.key(entries.get(i)))) return i;
            throw new AssertionError("Localized native search has no " + expected);
        });
        clickGrid(context, index);
        connection.waitForServerboundPackets();
        server.waitFor(instance -> expected.equals(((BookTargetMenu) serverMenu(connection)).ic2upgrader$getBookTarget())
                && field(serverMenu(connection), "lastSyncedTargetValue", Long.class) == Math.round(price));
        connection.waitForClientboundPackets();
        context.waitFor(client -> !field(screen(client), "pickerOpen", Boolean.class)
                && expected.equals(((BookTargetMenu) screen(client).getMenu()).ic2upgrader$getBookTarget())
                && field(screen(client), "targetValue", Long.class) == Math.round(price), 1200);
    }

    private static void openPicker(ClientGameTestContext context) {
        if (context.computeOnClient(client -> field(screen(client), "pickerOpen", Boolean.class))) return;
        clickRelative(context, 238, 42);
        context.waitFor(client -> field(screen(client), "pickerOpen", Boolean.class));
    }

    private static void replaceSearch(ClientGameTestContext context, String query) {
        clickRelative(context, 30, 30);
        int previousLength = context.computeOnClient(client ->
                field(screen(client), "searchBox", EditBox.class).getValue().length());
        // Fabric's current synthetic KeyEvent has modifiers=0, so holdControl + A cannot reliably
        // select all text in an SDL EditBox. End and individual Backspaces exercise supported real
        // keyboard handlers and avoid depending on accidental native double-click word selection.
        context.getInput().pressKey(InputConstants.KEY_END);
        for (int i = 0; i < previousLength; i++) {
            context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
        }
        waitForSearch(context, "");
        if (!query.isEmpty()) context.getInput().typeChars(query);
        waitForSearch(context, query);
    }

    private static void waitForSearch(ClientGameTestContext context, String query) {
        try {
            context.waitFor(client -> {
                UpgraderScreen gui = screen(client);
                EditBox search = field(gui, "searchBox", EditBox.class);
                if (!search.getValue().equals(query)) return false;
                List<?> original = field(gui, "catalog", List.class);
                List<ItemStack> source = BookTargetClient.augmentCatalog(
                        original.stream().map(ItemStack.class::cast).toList());
                List<ItemStack> expected = source.stream()
                        .filter(stack -> BookTargetClient.matches(stack, query)).toList();
                return ItemStack.listMatches(filtered(gui), expected);
            }, 40);
        } catch (RuntimeException | AssertionError failure) {
            String diagnostic = context.computeOnClient(client -> searchDiagnostic(screen(client)));
            throw new AssertionError("Native search replacement/refilter did not reach expected text='"
                    + query + "'; " + diagnostic, failure);
        }
    }

    private static String searchDiagnostic(UpgraderScreen gui) {
        EditBox search = field(gui, "searchBox", EditBox.class);
        List<ItemStack> entries = filtered(gui);
        return "actualText='" + search.getValue() + "', highlighted='" + search.getHighlighted()
                + "', cursor=" + search.getCursorPosition() + ", focused=" + search.isFocused()
                + ", selectedCategory=" + field(gui, "ic2upgrader$selectedCategory", String.class)
                + ", filteredCount=" + entries.size() + ", firstFiltered="
                + entries.stream().limit(12).map(stack -> BookTargetClient.key(stack) == null
                        ? BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()
                        : BookTargetClient.key(stack).toString()).toList();
    }

    private static void clickGrid(ClientGameTestContext context, int index) {
        require(index >= 0 && index < 126, "Book fixture unexpectedly needs scrolling: " + index);
        clickRelative(context, 26 + (index % 14) * 18, 52 + (index / 14) * 18);
    }

    private static void clickRelative(ClientGameTestContext context, int x, int y) {
        moveRelative(context, x, y);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
    }

    private static void moveRelative(ClientGameTestContext context, double x, double y) {
        double[] cursor = context.computeOnClient(client -> {
            UpgraderScreen gui = screen(client);
            int left = field(gui, AbstractContainerScreen.class, "leftPos", Integer.class);
            int top = field(gui, AbstractContainerScreen.class, "topPos", Integer.class);
            var window = client.getWindow();
            return new double[] {(left + x) * (double) window.getScreenWidth() / window.getGuiScaledWidth(),
                    (top + y) * (double) window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
    }

    private static List<ItemStack> filtered(UpgraderScreen screen) {
        List<?> values = field(screen, "filtered", List.class);
        return values.stream().map(ItemStack.class::cast).toList();
    }

    private static ItemEnchantments stored(ItemStack stack) {
        return stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
    }

    private static UpgraderScreen screen(Minecraft client) {
        require(client.gui.screen() instanceof UpgraderScreen, "Expected actual Upgrader screen");
        return (UpgraderScreen) client.gui.screen();
    }

    private static UpgraderMenu serverMenu(TestServerConnection connection) {
        require(connection.getServerPlayer().containerMenu instanceof UpgraderMenu,
                "Expected actual server Upgrader menu");
        return (UpgraderMenu) connection.getServerPlayer().containerMenu;
    }

    private static List<GuiMessage> winMessages(Minecraft client) {
        List<?> messages = field(client.gui.hud.getChat(), ChatComponent.class, "allMessages", List.class);
        return messages.stream().map(GuiMessage.class::cast).filter(message ->
                message.content().getContents() instanceof TranslatableContents translation
                        && translation.getKey().equals("chat.ic2_upgrader_compat.win")).toList();
    }

    private static boolean hasBookHover(Component component, BookTargets.Key key) {
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowItem hover) {
            ItemStack stack = hover.item().create();
            if (stack.getCount() == 1 && key.equals(BookTargetClient.key(stack))) return true;
        }
        for (Component sibling : component.getSiblings()) if (hasBookHover(sibling, key)) return true;
        if (component.getContents() instanceof TranslatableContents translation) {
            for (Object argument : translation.getArgs()) {
                if (argument instanceof Component nested && hasBookHover(nested, key)) return true;
            }
        }
        return false;
    }

    private static boolean hasExactHover(Component component, ItemStack expected) {
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowItem hover
                && ItemStack.matches(hover.item().create(), expected)) return true;
        for (Component sibling : component.getSiblings()) if (hasExactHover(sibling, expected)) return true;
        if (component.getContents() instanceof TranslatableContents translation) {
            for (Object argument : translation.getArgs()) {
                if (argument instanceof Component nested && hasExactHover(nested, expected)) return true;
            }
        }
        return false;
    }

    private static Item item(String id) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        require(item != null && item != Items.AIR, "Missing fixture item " + id);
        return item;
    }

    private static <T> T field(Object instance, String name, Class<T> type) {
        return field(instance, instance.getClass(), name, type);
    }

    private static <T> T field(Object instance, Class<?> owner, String name, Class<T> type) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(instance));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect actual Upgrader field " + owner.getName() + "." + name, exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Saved(ItemStack input, ItemStack carried, List<ItemStack> inventory,
            double minChance, double maxChance, int spinTicks, int attemptLimit) { }

    private record PresetChoice(BookTargets.Key key, double price) { }
}
