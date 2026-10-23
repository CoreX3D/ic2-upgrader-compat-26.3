package dev.core.ic2upgrader.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.core.ic2upgrader.UpgradeSpinSync;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.execheinz.upgrader.UpgraderConfig;
import net.execheinz.upgrader.UpgraderRegistry;
import net.execheinz.upgrader.client.UpgraderScreen;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.execheinz.upgrader.value.ItemValues;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Native packet, button, animation and actual SoundManager regression checks for one server commit. */
@SuppressWarnings("UnstableApiUsage")
public final class UpgradeSpinClientChecks {
    private static final int LONG_SPIN_TICKS = 1_200;

    private UpgradeSpinClientChecks() { }

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
                    menu.getTarget(), field(menu, "targetCount", Integer.class), UpgraderConfig.minChance,
                    UpgraderConfig.maxChance, UpgraderConfig.spinTicks, UpgraderConfig.attemptLimit);
        });
        ChatComponent.State chat = context.computeOnClient(client -> client.gui.hud.getChat().storeState());
        try {
            Item plate = item("ic2:iron_plate");
            Item copper = item("ic2:copper_plate");
            Round win = round(context, server, connection, new ItemStack(plate, 16), copper, 1,
                    true, null, evidence);
            Round loss = round(context, server, connection, new ItemStack(plate, 16), copper, 1,
                    false, win.finish(), evidence);
            Round same = round(context, server, connection, new ItemStack(plate, 16), plate, 16,
                    true, loss.finish(), evidence);
            require(ItemStack.matches(same.input(), same.finish().result()),
                    "Same-stack fixture must finish with an item/component/count-identical input");
            closeDuringSpin(context, server, connection, new ItemStack(plate, 16), copper,
                    same.finish(), evidence);
        } finally {
            context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
            context.runOnClient(client -> UpgradeSpinSoundLedger.stop());
            // A failing assertion must not leave a minute-long round running in the following fixtures.
            server.runOnServer(instance -> {
                if (connection.getServerPlayer().containerMenu instanceof UpgraderMenu menu && menu.isSpinning()) {
                    setField(menu, "spinTicksLeft", 1);
                    menu.broadcastChanges();
                }
            });
            connection.waitForClientboundPackets();
            if (!context.computeOnClient(client -> client.gui.screen() instanceof UpgraderScreen)) {
                server.runOnServer(instance -> UpgraderRegistry.upgraderItem.use(connection.getServerLevel(),
                        connection.getServerPlayer(), InteractionHand.MAIN_HAND));
                connection.waitForClientboundPackets();
                context.waitFor(client -> client.gui.screen() instanceof UpgraderScreen, 1200);
            }
            server.runOnServer(instance -> {
                UpgraderConfig.minChance = saved.minChance();
                UpgraderConfig.maxChance = saved.maxChance();
                UpgraderConfig.spinTicks = saved.spinTicks();
                UpgraderConfig.attemptLimit = saved.attemptLimit();
                var player = connection.getServerPlayer();
                var menu = serverMenu(connection);
                menu.getSlot(UpgraderMenu.INPUT_SLOT).set(saved.input().copy());
                menu.setCarried(saved.carried().copy());
                menu.setTarget(player, saved.target());
                menu.setCount(player, saved.count());
                for (int i = 0; i < saved.inventory().size(); i++) {
                    player.getInventory().setItem(i, saved.inventory().get(i).copy());
                }
                menu.broadcastChanges();
            });
            connection.waitForClientboundPackets();
            context.waitFor(client -> ItemStack.matches(screen(client).getMenu().getInputStack(), saved.input())
                    && !field(screen(client), "spinning", Boolean.class), 1200);
            context.runOnClient(client -> client.gui.hud.getChat().restoreState(chat));
        }
        evidence.add("PASS: spin regression fixture restored original menu input, carried stack, target/count,"
                + " inventory, configuration and chat before the remaining native client checks.");
    }

    private static Round round(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, ItemStack input, Item target, int count, boolean win,
            UpgradeSpinSync.FinishPayload oldFinish, List<String> evidence) {
        prepare(context, server, connection, input, target, count, win);
        List<ItemStack> inventoryBefore = server.computeOnServer(instance -> inventory(connection));
        long roundId = start(context, server, connection);
        int containerId = server.computeOnServer(instance -> serverMenu(connection).containerId);

        if (oldFinish != null) {
            server.runOnServer(instance -> ServerPlayNetworking.send(connection.getServerPlayer(), oldFinish));
            connection.waitForClientboundPackets();
            context.runOnClient(client -> {
                require(ItemStack.matches(screen(client).getMenu().getInputStack(), input)
                                && field(screen(client), "spinning", Boolean.class),
                        "An older real Finish packet mutated the next round's slot or stopped its wheel");
                require(UpgradeSpinSoundLedger.snapshot().isEmpty(),
                        "An older real Finish packet replayed a terminal sound during the next round");
            });
        }

        // Reproduce a client timer reaching zero well before the authoritative server countdown.
        context.runOnClient(client -> setField(screen(client), "spinStartMs", System.currentTimeMillis()
                - field(screen(client), "spinDurationMs", Long.class) - 60_000L));
        float waitingAngle = context.computeOnClient(client -> (float) invoke(screen(client), "needleAngle"));
        context.waitTicks(4);
        server.runOnServer(instance -> require(serverMenu(connection).isSpinning()
                        && ItemStack.matches(serverMenu(connection).getInputStack(), input),
                "The native server fixture finished before the deliberately early client deadline"));
        context.runOnClient(client -> {
            UpgraderScreen gui = screen(client);
            require(field(gui, "spinning", Boolean.class),
                    "The client stopped the wheel independently when its wall-clock timer expired");
            require(ItemStack.matches(gui.getMenu().getInputStack(), input),
                    "The client changed the input before the authoritative Finish packet");
            require((float) invoke(gui, "progress") < 1f,
                    "Pending native animation reached terminal progress before the server committed");
            float nextAngle = (float) invoke(gui, "needleAngle");
            require(Float.isFinite(waitingAngle) && Float.isFinite(nextAngle)
                            && Math.abs(nextAngle - waitingAngle) > 0.01f,
                    "Pending wheel visibly froze after its client timer expired while the server was still running");
            require(field(gui, "resultFlashUntil", Long.class) == 0L,
                    "Pending animation displayed a result before server completion");
            require(UpgradeSpinSoundLedger.snapshot().isEmpty(),
                    "The early client deadline played a terminal anvil or level-up sound");
        });

        // Shorten only the disposable fixture's countdown, then let the real server tick/applyResult run.
        server.runOnServer(instance -> setField(serverMenu(connection), "spinTicksLeft", 2));
        server.waitFor(instance -> !serverMenu(connection).isSpinning());
        ItemStack result = server.computeOnServer(instance -> serverMenu(connection).getInputStack().copy());
        long inputValue = server.computeOnServer(instance -> Math.round(
                ItemValues.stackValue(connection.getServerLevel(), result)));
        ItemStack expected = win ? new ItemStack(target, count) : ItemStack.EMPTY;
        require(ItemStack.matches(result, expected), "Native server committed an unexpected spin result");
        connection.waitForClientboundPackets();
        context.waitFor(client -> !field(screen(client), "spinning", Boolean.class)
                && ItemStack.matches(screen(client).getMenu().getInputStack(), expected), 1200);
        context.waitTicks(4); // Flush both the UI completion and any accidental delayed world-sound packet.
        context.runOnClient(client -> {
            require(field(screen(client), "lastResultWin", Boolean.class) == win,
                    "The wheel result differs from the actual server slot result");
            require(field(screen(client), "inputValue", Long.class) == inputValue,
                    "Completion retained the old input price after replacing/removing the input");
            List<UpgradeSpinSoundLedger.Event> sounds = UpgradeSpinSoundLedger.snapshot();
            require(sounds.size() == 1, "One real round played " + sounds.size()
                    + " terminal sounds instead of exactly one: " + sounds);
            var sound = sounds.getFirst();
            require(sound.identifier().equals(win ? "minecraft:entity.player.levelup" : "minecraft:block.anvil.land"),
                    "Completion played the wrong terminal sound: " + sound);
            require(sound.relative(), "Owner completion still played a second positional world sound");
            require(Boolean.FALSE.equals(sound.spinning()) && ItemStack.matches(sound.input(), expected),
                    "The actual terminal sound fired before wheel-stop and input result became atomic");
        });

        var finish = new UpgradeSpinSync.FinishPayload(containerId, roundId, win, true, result.copy(), inputValue);
        server.runOnServer(instance -> {
            ServerPlayNetworking.send(connection.getServerPlayer(), finish);
            ServerPlayNetworking.send(connection.getServerPlayer(), finish);
        });
        connection.waitForClientboundPackets();
        context.waitTicks(3);
        context.runOnClient(client -> {
            require(UpgradeSpinSoundLedger.snapshot().size() == 1,
                    "Repeated real Finish packets replayed the completion sound");
            require(ItemStack.matches(screen(client).getMenu().getInputStack(), expected)
                            && !field(screen(client), "spinning", Boolean.class),
                    "Repeated real Finish packets duplicated the output or restarted the wheel");
            for (int i = 0; i < inventoryBefore.size(); i++) {
                require(ItemStack.matches(client.player.getInventory().getItem(i), inventoryBefore.get(i)),
                        "Spin completion/replayed Finish changed actual client inventory slot " + i);
            }
        });
        server.runOnServer(instance -> {
            require(ItemStack.matches(serverMenu(connection).getInputStack(), expected),
                    "A repeated server-to-client Finish mutated the server's authoritative result");
            require(ItemStack.listMatches(inventory(connection), inventoryBefore),
                    "An open-menu spin or repeated Finish duplicated an item into the server inventory");
        });
        evidence.add("PASS: native Upgrade button round " + roundId + " ("
                + (win ? ItemStack.matches(input, expected) ? "same-stack win" : "win" : "loss")
                + ") stayed pending after a forced early client deadline; one real server commit atomically"
                + " changed the slot, stopped the wheel and played exactly one recorded SoundManager sound;"
                + " duplicate and previous-round Finish packets were inert.");
        if (!win) evidence.add("Single authoritative loss completion: " + context.takeScreenshot(
                TestScreenshotOptions.of("upgrader-spin-single-loss-completion").disableCounterPrefix()).toAbsolutePath());
        return new Round(input.copy(), finish);
    }

    private static void closeDuringSpin(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, ItemStack input, Item target,
            UpgradeSpinSync.FinishPayload priorFinish, List<String> evidence) {
        prepare(context, server, connection, input, target, 1, true);
        long roundId = start(context, server, connection);
        int ownerId = server.computeOnServer(instance -> serverMenu(connection).containerId);
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        connection.waitForServerboundPackets();
        server.waitFor(instance -> connection.getServerPlayer().containerMenu
                == connection.getServerPlayer().inventoryMenu);
        connection.waitForClientboundPackets();
        context.waitFor(client -> !(client.gui.screen() instanceof UpgraderScreen));
        context.waitTicks(4);
        context.runOnClient(client -> require(UpgradeSpinSoundLedger.snapshot().size() == 1,
                "Closing an active real spin did not produce exactly one terminal sound"));

        server.runOnServer(instance -> UpgraderRegistry.upgraderItem.use(connection.getServerLevel(),
                connection.getServerPlayer(), InteractionHand.MAIN_HAND));
        connection.waitForClientboundPackets();
        context.waitFor(client -> client.gui.screen() instanceof UpgraderScreen, 1200);
        ItemStack nextInput = new ItemStack(item("ic2:iron_plate"), 7);
        prepare(context, server, connection, nextInput, target, 1, true);
        context.runOnClient(client -> UpgradeSpinSoundLedger.begin());
        var stale = new UpgradeSpinSync.FinishPayload(ownerId, roundId, true, true, new ItemStack(target), 1L);
        server.runOnServer(instance -> {
            require(serverMenu(connection).containerId != ownerId, "Reopened menu did not get a fresh native ID");
            ServerPlayNetworking.send(connection.getServerPlayer(), stale);
            ServerPlayNetworking.send(connection.getServerPlayer(), priorFinish);
        });
        connection.waitForClientboundPackets();
        context.waitTicks(3);
        context.runOnClient(client -> require(UpgradeSpinSoundLedger.snapshot().isEmpty()
                        && ItemStack.matches(screen(client).getMenu().getInputStack(), nextInput)
                        && !field(screen(client), "spinning", Boolean.class),
                "Stale closed-menu Finish changed the new menu or replayed a completion sound"));
        evidence.add("PASS: Escape during a real pending upgrade committed once and played one terminal sound;"
                + " replayed closed-menu Finish packets neither altered the reopened menu nor replayed audio.");
    }

    private static void prepare(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, ItemStack input, Item target, int count, boolean win) {
        server.runOnServer(instance -> {
            var menu = serverMenu(connection);
            require(!menu.isSpinning(), "A previous fixture left a server round spinning");
            UpgraderConfig.minChance = win ? 1 : 0;
            UpgraderConfig.maxChance = win ? 1 : 0;
            UpgraderConfig.spinTicks = LONG_SPIN_TICKS;
            UpgraderConfig.attemptLimit = 0;
            menu.getSlot(UpgraderMenu.INPUT_SLOT).set(input.copy());
            menu.setTarget(connection.getServerPlayer(), target);
            menu.setCount(connection.getServerPlayer(), count);
            menu.broadcastChanges();
        });
        connection.waitForClientboundPackets();
        context.waitFor(client -> screen(client).getMenu().getTarget() == target
                && field(screen(client), "targetCount", Integer.class) == count
                && ItemStack.matches(screen(client).getMenu().getInputStack(), input), 1200);
        context.runOnClient(client -> UpgradeSpinSoundLedger.begin());
    }

    private static long start(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection) {
        double[] cursor = context.computeOnClient(client -> {
            UpgraderScreen gui = screen(client);
            int left = field(gui, AbstractContainerScreen.class, "leftPos", Integer.class);
            int top = field(gui, AbstractContainerScreen.class, "topPos", Integer.class);
            var window = client.getWindow();
            return new double[] {(left + 78) * (double) window.getScreenWidth() / window.getGuiScaledWidth(),
                    (top + 133) * (double) window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
        connection.waitForServerboundPackets();
        server.waitFor(instance -> serverMenu(connection).isSpinning());
        long id = server.computeOnServer(instance -> UpgradeSpinSync.activeRoundId(serverMenu(connection)));
        require(id > 0, "Native Upgrade button did not create an authoritative round ID");
        connection.waitForClientboundPackets();
        context.waitFor(client -> field(screen(client), "spinning", Boolean.class), 1200);
        return id;
    }

    private static Item item(String id) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        require(item != null && item != Items.AIR, "Missing native spin fixture item " + id);
        return item;
    }

    private static UpgraderScreen screen(Minecraft client) {
        require(client.gui.screen() instanceof UpgraderScreen, "Expected the actual native Upgrader screen");
        return (UpgraderScreen) client.gui.screen();
    }

    private static UpgraderMenu serverMenu(TestServerConnection connection) {
        require(connection.getServerPlayer().containerMenu instanceof UpgraderMenu,
                "Expected the actual native server Upgrader menu");
        return (UpgraderMenu) connection.getServerPlayer().containerMenu;
    }

    private static List<ItemStack> inventory(TestServerConnection connection) {
        List<ItemStack> result = new ArrayList<>();
        var inventory = connection.getServerPlayer().getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) result.add(inventory.getItem(i).copy());
        return result;
    }

    private static Object invoke(Object instance, String name) {
        try {
            Method method = UpgraderScreen.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(instance);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect native Upgrader method " + name, exception);
        }
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
            throw new AssertionError("Could not inspect native Upgrader field " + name, exception);
        }
    }

    private static void setField(Object instance, String name, Object value) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not adjust disposable native spin fixture " + name, exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Saved(ItemStack input, ItemStack carried, List<ItemStack> inventory, Item target, int count,
            double minChance, double maxChance, int spinTicks, int attemptLimit) { }

    private record Round(ItemStack input, UpgradeSpinSync.FinishPayload finish) { }
}
