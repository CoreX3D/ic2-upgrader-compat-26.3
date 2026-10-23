package dev.core.ic2upgrader.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.core.ic2upgrader.BookTargets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.execheinz.upgrader.UpgraderConfig;
import net.execheinz.upgrader.client.UpgraderScreen;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.execheinz.upgrader.value.ItemValues;
import net.execheinz.upgrader.value.UpgradeOdds;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** Native inventory clicks, quotes and accepted upgrade for the four-enchantment helmet reported by the user. */
@SuppressWarnings("UnstableApiUsage")
public final class EquipmentEnchantmentClientChecks {
    private EquipmentEnchantmentClientChecks() { }

    public static void validate(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, List<String> evidence) {
        Saved saved = server.computeOnServer(instance -> {
            UpgraderMenu menu = serverMenu(connection);
            require(!menu.isSpinning() && menu.getCarried().isEmpty(), "Enchantment fixture needs an idle menu");
            return new Saved(menu.getInputStack().copy(), menu.getCarried().copy(), inventory(connection),
                    menu.getTarget(), field(menu, "targetCount", Integer.class), UpgraderConfig.minChance,
                    UpgraderConfig.maxChance, UpgraderConfig.houseEdge, UpgraderConfig.spinTicks,
                    UpgraderConfig.attemptLimit);
        });
        ChatComponent.State chat = context.computeOnClient(client -> client.gui.hud.getChat().storeState());
        try {
            Fixture fixture = server.computeOnServer(instance -> {
                var level = connection.getServerLevel();
                var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
                ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                double bonus = 0;
                String[] ids = {"protection", "thorns", "unbreaking", "mending"};
                int[] levels = {4, 3, 3, 1};
                for (int i = 0; i < ids.length; i++) {
                    Identifier id = Identifier.fromNamespaceAndPath("minecraft", ids[i]);
                    mutable.set(enchantments.get(id).orElseThrow(), levels[i]);
                    bonus += BookTargets.value(level, new BookTargets.Key(id.toString(), levels[i]));
                }
                ItemStack plain = new ItemStack(Items.NETHERITE_HELMET);
                ItemStack namedPlain = plain.copy();
                namedPlain.set(DataComponents.CUSTOM_NAME, Component.literal("Проверка цены шлема"));
                ItemStack enchanted = plain.copy();
                enchanted.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
                ItemStack namedEnchanted = enchanted.copy();
                namedEnchanted.set(DataComponents.CUSTOM_NAME, Component.literal("Зачарованный шлем"));
                return new Fixture(List.of(plain, namedPlain, enchanted, namedEnchanted),
                        ItemValues.stackValue(level, plain), bonus);
            });
            require(fixture.base() > 0 && fixture.bonus() > 0, "Native enchantment fixture has no positive price");
            server.runOnServer(instance -> {
                UpgraderConfig.minChance = 0;
                UpgraderConfig.maxChance = 1;
                UpgraderConfig.houseEdge = 0.9;
                UpgraderConfig.spinTicks = 1_200;
                UpgraderConfig.attemptLimit = 0;
                var menu = serverMenu(connection);
                menu.getSlot(UpgraderMenu.INPUT_SLOT).set(ItemStack.EMPTY);
                menu.setCarried(ItemStack.EMPTY);
                for (int i = 0; i < fixture.stacks().size(); i++) {
                    connection.getServerPlayer().getInventory().setItem(10 + i, fixture.stacks().get(i).copy());
                }
                menu.broadcastChanges();
            });
            connection.waitForClientboundPackets();
            context.waitFor(client -> screen(client).getMenu().getInputStack().isEmpty()
                    && ItemStack.matches(client.player.getInventory().getItem(10), fixture.stacks().getFirst()), 1200);
            Item target = server.computeOnServer(instance -> {
                var level = connection.getServerLevel();
                double threshold = fixture.base() + fixture.bonus();
                Item selected = null;
                double selectedPrice = Double.POSITIVE_INFINITY;
                // Choose an actual permitted recipe target above this world's configured investment.
                // The cheapest qualifying target also keeps both native float odds well above zero.
                for (Item candidate : ItemValues.catalog(level)) {
                    if (candidate == Items.ENCHANTED_BOOK) continue;
                    double value = ItemValues.unitValue(level, candidate);
                    if (Double.isFinite(value) && value > threshold && value < selectedPrice) {
                        selected = candidate;
                        selectedPrice = value;
                    }
                }
                require(selected != null, "Native permitted catalog has no target above the enchanted helmet price "
                        + threshold);
                return selected;
            });
            selectTarget(context, server, connection, target);
            double targetExact = server.computeOnServer(instance -> ItemValues.unitValue(connection.getServerLevel(), target));
            require(targetExact > fixture.base() + fixture.bonus(),
                    "The native selected target must avoid a maximum-chance clamp in the enchanted input fixture");
            evidence.add("Native enchanted-equipment target selected from permitted catalog: "
                    + BuiltInRegistries.ITEM.getKey(target) + ", exact unit value=" + targetExact + ".");
            float plainChance = 0;
            for (int variant = 0; variant < fixture.stacks().size(); variant++) {
                ItemStack expected = fixture.stacks().get(variant);
                double value = fixture.base() + (variant >= 2 ? fixture.bonus() : 0);
                loadInput(context, server, connection, 10 + variant, expected);
                long quote = Math.round(value);
                float shownChance = (float) UpgradeOdds.chance(quote, Math.round(targetExact));
                context.waitFor(client -> field(screen(client), "inputValue", Long.class) == quote, 1200);
                server.runOnServer(instance -> {
                    var menu = serverMenu(connection);
                    require(ItemStack.matches(menu.getInputStack(), expected),
                            "Pricing changed the actual helmet count or components");
                    require(Math.abs(ItemValues.stackValue(connection.getServerLevel(), menu.getInputStack()) - value) < 1e-7,
                            "Native equipment quote omitted or multiplied an enchantment contribution");
                    require(field(menu, "lastSyncedInputValue", Long.class) == quote,
                            "Server synchronization did not send the rounded enchanted equipment value");
                });
                context.runOnClient(client -> {
                    require(ItemStack.matches(screen(client).getMenu().getInputStack(), expected),
                            "The actual inventory packet lost helmet components");
                    require(Math.abs(field(screen(client), "chance", Float.class) - shownChance) < 1e-6,
                            "Displayed odds do not use the authoritative enchanted input quote");
                });
                float actualChance = context.computeOnClient(client -> field(screen(client), "chance", Float.class));
                if (variant == 0) plainChance = actualChance;
                if (variant == 1) require(Math.abs(actualChance - plainChance) < 1e-7,
                        "A custom name alone increased the native input quote or upgrade chance");
                if (variant >= 2) require(actualChance > plainChance,
                        "The actual displayed upgrade chance did not increase for four legal enchantments");
                evidence.add("Native netherite-helmet variant " + variant + ": server/UI inputValue=" + quote
                        + ", displayedChance=" + actualChance + ", preserved full item components.");
                if (variant == fixture.stacks().size() - 1) break;
                unloadInput(context, server, connection, 10 + variant, expected);
            }
            moveRelative(context, 49, 43);
            context.waitTicks(3);
            evidence.add("Four-enchantment netherite helmet quote with its native tooltip: "
                    + context.takeScreenshot(TestScreenshotOptions.of("upgrader-enchanted-equipment-value")
                            .disableCounterPrefix()).toAbsolutePath());

            ItemStack spent = fixture.stacks().getLast();
            List<ItemStack> before = server.computeOnServer(instance -> inventory(connection));
            context.runOnClient(client -> UpgradeSpinSoundLedger.begin());
            clickRelative(context, 78, 133);
            connection.waitForServerboundPackets();
            server.waitFor(instance -> serverMenu(connection).isSpinning());
            boolean won = server.computeOnServer(instance -> {
                UpgraderMenu menu = serverMenu(connection);
                double accepted = field(menu, "ic2Upgrader$committedChance", Double.class);
                double expected = UpgradeOdds.chance(fixture.base() + fixture.bonus(), targetExact);
                require(Math.abs(accepted - expected) < 1e-12,
                        "The actual accepted RNG chance did not use the exact enchanted equipment price");
                require(accepted > UpgradeOdds.chance(fixture.base(), targetExact),
                        "Enchantments increased the preview but not the accepted upgrade chance");
                require(ItemStack.matches(field(menu, "ic2Upgrader$committedInput", ItemStack.class), spent)
                                && ItemStack.matches(menu.getInputStack(), spent),
                        "The committed expense snapshot lost enchantments/custom name or consumed input prematurely");
                evidence.add("Accepted real Upgrade button: exact input=" + (fixture.base() + fixture.bonus())
                        + ", enchantment bonus=" + fixture.bonus() + ", target="
                        + BuiltInRegistries.ITEM.getKey(target) + " (" + targetExact + ")"
                        + ", committed RNG chance=" + accepted + ".");
                return field(menu, "pendingWin", Boolean.class);
            });
            connection.waitForClientboundPackets();
            context.waitFor(client -> field(screen(client), "spinning", Boolean.class), 1200);
            server.runOnServer(instance -> setField(serverMenu(connection), "spinTicksLeft", 2));
            server.waitFor(instance -> !serverMenu(connection).isSpinning());
            ItemStack output = won ? new ItemStack(target) : ItemStack.EMPTY;
            connection.waitForClientboundPackets();
            context.waitFor(client -> !field(screen(client), "spinning", Boolean.class)
                    && ItemStack.matches(screen(client).getMenu().getInputStack(), output), 1200);
            context.waitTicks(4);
            server.runOnServer(instance -> {
                require(ItemStack.matches(serverMenu(connection).getInputStack(), output)
                                && serverMenu(connection).getCarried().isEmpty(),
                        "Enchanted equipment was not consumed once by the actual native result");
                require(ItemStack.listMatches(before, inventory(connection)),
                        "The native enchanted-input attempt duplicated an item into the inventory");
            });
            context.runOnClient(client -> {
                require(ItemStack.listMatches(before, clientInventory(client)),
                        "The enchanted-input result packet duplicated a client inventory item");
                var sounds = UpgradeSpinSoundLedger.snapshot();
                require(sounds.size() == 1 && Boolean.FALSE.equals(sounds.getFirst().spinning())
                                && ItemStack.matches(sounds.getFirst().input(), output),
                        "The enchanted-input attempt did not finish atomically with one terminal sound: " + sounds);
            });
            evidence.add("PASS: four legal equipment enchantments add their individual book prices; renaming adds no"
                    + " price; real slot clicks and server packets preserve every component; actual accepted chance"
                    + " includes the bonus; one native " + (won ? "win" : "loss")
                    + " consumed the exact enchanted input once without inventory duplication or repeated sound.");
        } finally {
            context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
            context.runOnClient(client -> UpgradeSpinSoundLedger.stop());
            server.runOnServer(instance -> {
                var menu = serverMenu(connection);
                if (menu.isSpinning()) {
                    setField(menu, "spinTicksLeft", 1);
                    menu.broadcastChanges();
                }
                UpgraderConfig.minChance = saved.minChance();
                UpgraderConfig.maxChance = saved.maxChance();
                UpgraderConfig.houseEdge = saved.houseEdge();
                UpgraderConfig.spinTicks = saved.spinTicks();
                UpgraderConfig.attemptLimit = saved.attemptLimit();
                menu.getSlot(UpgraderMenu.INPUT_SLOT).set(saved.input().copy());
                menu.setCarried(saved.carried().copy());
                menu.setTarget(connection.getServerPlayer(), saved.target());
                menu.setCount(connection.getServerPlayer(), saved.count());
                for (int i = 0; i < saved.inventory().size(); i++) {
                    connection.getServerPlayer().getInventory().setItem(i, saved.inventory().get(i).copy());
                }
                menu.broadcastChanges();
            });
            connection.waitForClientboundPackets();
            context.waitFor(client -> ItemStack.matches(screen(client).getMenu().getInputStack(), saved.input())
                    && !field(screen(client), "spinning", Boolean.class), 1200);
            context.runOnClient(client -> client.gui.hud.getChat().restoreState(chat));
        }
        evidence.add("PASS: enchanted equipment client fixture restored input, target/count, carried stack,"
                + " full inventory, configuration and chat before the existing regression scenarios.");
    }

    private static void loadInput(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, int inventorySlot, ItemStack expected) {
        clickRelative(context, 71 + (inventorySlot - 9) * 18, 168);
        clickRelative(context, 49, 43);
        connection.waitForServerboundPackets();
        server.waitFor(instance -> ItemStack.matches(serverMenu(connection).getInputStack(), expected)
                && serverMenu(connection).getCarried().isEmpty());
        connection.waitForClientboundPackets();
        context.waitFor(client -> ItemStack.matches(screen(client).getMenu().getInputStack(), expected), 1200);
    }

    private static void unloadInput(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, int inventorySlot, ItemStack expected) {
        clickRelative(context, 49, 43);
        clickRelative(context, 71 + (inventorySlot - 9) * 18, 168);
        connection.waitForServerboundPackets();
        server.waitFor(instance -> serverMenu(connection).getInputStack().isEmpty()
                && ItemStack.matches(connection.getServerPlayer().getInventory().getItem(inventorySlot), expected)
                && serverMenu(connection).getCarried().isEmpty());
        connection.waitForClientboundPackets();
        context.waitFor(client -> screen(client).getMenu().getInputStack().isEmpty(), 1200);
    }

    private static void selectTarget(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, Item target) {
        clickRelative(context, 238, 42);
        for (int i = 0; i < 10 && context.computeOnClient(client ->
                field(screen(client), "ic2upgrader$categoryPage", Integer.class)) > 0; i++) {
            clickRelative(context, 251, 221);
        }
        clickRelative(context, 27, -3); // The actual All creative-category tab.
        clickRelative(context, 30, 30);
        int oldLength = context.computeOnClient(client -> field(screen(client), "searchBox", EditBox.class)
                .getValue().length());
        context.getInput().pressKey(InputConstants.KEY_END);
        for (int i = 0; i < oldLength; i++) context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
        String query = BuiltInRegistries.ITEM.getKey(target).toString();
        context.getInput().typeChars(query);
        context.waitFor(client -> field(screen(client), "searchBox", EditBox.class).getValue()
                .equals(query), 40);
        int index = context.computeOnClient(client -> {
            List<?> filtered = field(screen(client), "filtered", List.class);
            for (int i = 0; i < filtered.size(); i++) if (((ItemStack) filtered.get(i)).is(target)) return i;
            throw new AssertionError("Selected permitted target is missing from the actual picker: " + query);
        });
        require(index < 126, "The selected permitted target unexpectedly requires scrolling: " + query);
        clickRelative(context, 26 + (index % 14) * 18, 52 + (index / 14) * 18);
        connection.waitForServerboundPackets();
        server.waitFor(instance -> serverMenu(connection).getTarget() == target);
        connection.waitForClientboundPackets();
        context.waitFor(client -> !field(screen(client), "pickerOpen", Boolean.class)
                && screen(client).getMenu().getTarget() == target, 1200);
    }

    private static List<ItemStack> inventory(TestServerConnection connection) {
        var inventory = connection.getServerPlayer().getInventory();
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) result.add(inventory.getItem(i).copy());
        return result;
    }

    private static List<ItemStack> clientInventory(Minecraft client) {
        var inventory = client.player.getInventory();
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) result.add(inventory.getItem(i).copy());
        return result;
    }

    private static void clickRelative(ClientGameTestContext context, int x, int y) {
        moveRelative(context, x, y);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
    }

    private static void moveRelative(ClientGameTestContext context, double x, double y) {
        double[] cursor = context.computeOnClient(client -> {
            var gui = screen(client);
            int left = field(gui, AbstractContainerScreen.class, "leftPos", Integer.class);
            int top = field(gui, AbstractContainerScreen.class, "topPos", Integer.class);
            var window = client.getWindow();
            return new double[] {(left + x) * (double) window.getScreenWidth() / window.getGuiScaledWidth(),
                    (top + y) * (double) window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
    }

    private static UpgraderScreen screen(Minecraft client) {
        require(client.gui.screen() instanceof UpgraderScreen, "Expected the actual Upgrader screen");
        return (UpgraderScreen) client.gui.screen();
    }

    private static UpgraderMenu serverMenu(TestServerConnection connection) {
        require(connection.getServerPlayer().containerMenu instanceof UpgraderMenu, "Expected the actual Upgrader menu");
        return (UpgraderMenu) connection.getServerPlayer().containerMenu;
    }

    private static Item item(String id) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        require(item != null && item != Items.AIR, "Missing equipment fixture item " + id);
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
            throw new AssertionError("Could not inspect native enchantment fixture field " + name, exception);
        }
    }

    private static void setField(Object instance, String name, Object value) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not shorten native fixture countdown " + name, exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Fixture(List<ItemStack> stacks, double base, double bonus) { }
    private record Saved(ItemStack input, ItemStack carried, List<ItemStack> inventory, Item target, int count,
            double minChance, double maxChance, double houseEdge, int spinTicks, int attemptLimit) { }
}
