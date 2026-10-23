package dev.core.ic2upgrader.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import ic2.api.item.ElectricItem;
import ic2.api.item.IElectricItem;
import dev.core.ic2upgrader.client.BookTargetClient;
import dev.core.ic2upgrader.client.CreativeCatalogCategories;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.execheinz.upgrader.UpgraderConfig;
import net.execheinz.upgrader.UpgraderRegistry;
import net.execheinz.upgrader.client.ClientCatalog;
import net.execheinz.upgrader.client.UpgraderScreen;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.execheinz.upgrader.value.ItemValues;
import net.execheinz.upgrader.value.UpgradeOdds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Uses the real station item, screens, mouse/keyboard handlers and Upgrader packets. */
@Environment(EnvType.CLIENT)
@SuppressWarnings("UnstableApiUsage")
public final class UpgraderCompatClientGameTest implements FabricClientGameTest {
  @Override public void runTest(ClientGameTestContext context) {
    Path directory = context.computeOnClient(client -> client.gameDirectory.toPath().toAbsolutePath().normalize());
    require(directory.endsWith(Path.of("build", "client-render-smoke")),
        "Client fixtures must run only in build/client-render-smoke: " + directory);
    int[] window = context.computeOnClient(client -> new int[] {client.getWindow().getScreenWidth(),
        client.getWindow().getScreenHeight(), client.options.guiScale().get()});
    String[] language = context.computeOnClient(client -> new String[] {
        client.getLanguageManager().getSelected(), client.options.languageCode});
    List<String> evidence = new ArrayList<>();
    for (String modId : System.getProperty("ic2.upgrader.requiredMods", "").split(",")) {
      if (modId.isBlank()) continue;
      var loaded = FabricLoader.getInstance().getModContainer(modId);
      require(loaded.isPresent(), "Compatibility client did not load required profile mod " + modId);
      evidence.add("Profile mod loaded: " + modId + " "
          + loaded.orElseThrow().getMetadata().getVersion().getFriendlyString());
    }
    try {
      context.runOnClient(client -> {
        client.options.guiScale().set(2);
        client.options.renderDistance().set(5);
        client.options.simulationDistance().set(5);
      });
      context.getInput().resizeWindow(1600, 900);
      context.runOnClient(Minecraft::resizeGui);
      reloadLanguage(context, "ru_ru", "ru_ru");
      try (var world = context.worldBuilder().setUseConsistentSettings(true).create()) {
        evidence.addAll(validate(context, world.getServer(), world.getConnection()));
        evidence.add("Temporary world: " + world.getWorldSave().getSaveDirectory().toAbsolutePath());
      }
      evidence.add("PASS: real Russian Upgrader UI, catalog/value/count/chance packets and successful IC2 equipment upgrade.");
      try { Files.write(directory.resolve("upgrader-validation.txt"), evidence); }
      catch (java.io.IOException exception) { throw new AssertionError("Could not save client evidence", exception); }
    } finally {
      reloadLanguage(context, language[0], language[1]);
      context.runOnClient(client -> client.options.guiScale().set(window[2]));
      context.getInput().resizeWindow(window[0], window[1]);
      context.runOnClient(Minecraft::resizeGui);
    }
  }

  private static List<String> validate(ClientGameTestContext context, TestServerContext server,
      TestServerConnection connection) {
    Item ironPlate = item("ic2:iron_plate");
    Item copperPlate = item("ic2:copper_plate");
    Item nanoHelmet = item("ic2:nano_helmet");
    List<String> evidence = new ArrayList<>();
    SavedChat savedChat = context.computeOnClient(client -> new SavedChat(client.gui.hud.getChat().storeState(),
        client.options.chatWidth().get(), client.options.chatScale().get(), client.options.chatColors().get()));
    SavedPlayer saved = server.computeOnServer(instance -> {
      var player = connection.getServerPlayer();
      require(player.containerMenu == player.inventoryMenu, "An unexpected server menu was already open");
      List<ItemStack> inventory = new ArrayList<>();
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
        inventory.add(player.getInventory().getItem(i).copy());
      }
      return new SavedPlayer(inventory, player.getInventory().getSelectedSlot(),
          player.gameMode.getGameModeForPlayer(), player.getX(), player.getY(), player.getZ(),
          player.getYRot(), player.getXRot(), UpgraderConfig.minChance, UpgraderConfig.maxChance,
          UpgraderConfig.spinTicks, UpgraderConfig.attemptLimit);
    });
    try {
      server.runOnServer(instance -> {
        var player = connection.getServerPlayer();
        var level = connection.getServerLevel();
        level.setBlock(new BlockPos(0, 63, 0), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        player.teleportTo(level, 0.5, 64, 0.5, Set.of(), 180f, 0f, true);
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getInventory().setSelectedSlot(0);
        player.getInventory().setItem(0, new ItemStack(UpgraderRegistry.upgraderItem));
        player.getInventory().setItem(9, new ItemStack(ironPlate, 16));
        player.inventoryMenu.broadcastChanges();
        // Only the opening item action runs on the server; it invokes Upgrader's normal openMenu route.
        UpgraderRegistry.upgraderItem.use(level, player, InteractionHand.MAIN_HAND);
        require(player.containerMenu instanceof UpgraderMenu, "Station item failed to open the server menu");
      });
      connection.waitForClientboundPackets();
      context.waitFor(client -> client.gui.screen() instanceof UpgraderScreen && ClientCatalog.isReady(), 1200);
      context.waitFor(client -> !BookTargetClient.augmentCatalog(List.of()).isEmpty(), 1200);
      long[] quotes = server.computeOnServer(instance -> new long[] {
          Math.round(ItemValues.unitValue(connection.getServerLevel(), ironPlate)),
          Math.round(ItemValues.unitValue(connection.getServerLevel(), copperPlate)),
          Math.round(ItemValues.unitValue(connection.getServerLevel(), nanoHelmet))});
      context.runOnClient(client -> {
        require(ClientCatalog.items().containsAll(List.of(ironPlate, copperPlate, nanoHelmet)),
            "Native server catalog did not contain the IC2 fixtures");
        require(ClientCatalog.value(ironPlate) == quotes[0] && ClientCatalog.value(copperPlate) == quotes[1]
                && ClientCatalog.value(nanoHelmet) == quotes[2],
            "Client catalog did not preserve the actual server IC2 prices");
        require(quotes[0] > 0 && quotes[1] > 0 && quotes[2] > quotes[0],
            "IC2 material/equipment prices were not derived from their recipes");
        require(new ItemStack(nanoHelmet).getHoverName().getString().contains("Нановолоконный"),
            "Russian IC2 resources did not load");
      });
      evidence.add("Server catalog arrived through Upgrader networking: iron_plate=" + quotes[0]
          + ", copper_plate=" + quotes[1] + ", nano_helmet=" + quotes[2] + ".");

      moveRelative(context, 49, 43);
      context.waitTicks(3);
      evidence.add("Centered empty input screenshot: " + context.takeScreenshot(
          TestScreenshotOptions.of("upgrader-input-empty-centered").disableCounterPrefix()).toAbsolutePath());

      // Move the supplied plates through two genuine inventory clicks; no client/server slot injection.
      clickRelative(context, 71, 168);
      clickRelative(context, 48, 42);
      connection.waitForServerboundPackets();
      server.waitFor(instance -> serverMenu(connection).getInputStack().is(ironPlate)
          && serverMenu(connection).getInputStack().getCount() == 16);
      connection.waitForClientboundPackets();
      context.waitFor(client -> screen(client).getMenu().getInputStack().is(ironPlate)
          && field(screen(client), "inputValue", Long.class) == quotes[0] * 16);

      moveRelative(context, 49, 43);
      context.waitTicks(3);
      evidence.add("Centered filled input screenshot: " + context.takeScreenshot(
          TestScreenshotOptions.of("upgrader-input-filled-centered").disableCounterPrefix()).toAbsolutePath());

      validateScrollbar(context, server, connection, evidence);
      validateCategories(context, server, connection, evidence);
      BookUpgradeClientChecks.validate(context, server, connection, evidence);
      UpgradeSpinClientChecks.validate(context, server, connection, evidence);
      EquipmentEnchantmentClientChecks.validate(context, server, connection, evidence);

      selectTarget(context, copperPlate, "Медная пластина");
      // The native + control is at the right edge of the target amount selector.
      clickRelative(context, 266, 86);
      connection.waitForServerboundPackets();
      server.waitFor(instance -> field(serverMenu(connection), "targetCount", Integer.class) == 2);
      connection.waitForClientboundPackets();
      context.waitFor(client -> field(screen(client), "targetCount", Integer.class) == 2);
      context.runOnClient(client -> {
        var gui = screen(client);
        long inputValue = field(gui, "inputValue", Long.class);
        long targetValue = field(gui, "targetValue", Long.class);
        float chance = field(gui, "chance", Float.class);
        require(inputValue == quotes[0] * 16 && targetValue == quotes[1],
            "Material input/target values were not synchronized into the screen");
        require(Math.abs(chance - UpgradeOdds.chance(inputValue, targetValue * 2.0)) < 1e-6,
            "Native target-count packet did not update the displayed chance");
        evidence.add("Native material selector: copper_plate x2, input=" + inputValue
            + ", unitTarget=" + targetValue + ", displayedChance=" + chance + ".");
      });

      Item overclockedHeatVent = item("ic2:overclocked_heat_vent");
      long ventUnitQuote = server.computeOnServer(instance -> Math.round(
          ItemValues.unitValue(connection.getServerLevel(), overclockedHeatVent)));
      selectTarget(context, overclockedHeatVent, "ic2:overclocked_heat_vent");
      connection.waitForServerboundPackets();
      server.waitFor(instance -> serverMenu(connection).getTarget() == overclockedHeatVent
          && field(serverMenu(connection), "targetCount", Integer.class) == 1);
      connection.waitForClientboundPackets();
      context.waitFor(client -> screen(client).getMenu().getTarget() == overclockedHeatVent
          && field(screen(client), "targetCount", Integer.class) == 1
          && field(screen(client), "targetValue", Long.class) == ventUnitQuote);
      // Reproduce the reported target card with thirteen real + clicks, awaiting each normal acknowledgement.
      for (int count = 2; count <= 14; count++) {
        clickRelative(context, 266, 86);
        int expectedCount = count;
        connection.waitForServerboundPackets();
        server.waitFor(instance -> field(serverMenu(connection), "targetCount", Integer.class) == expectedCount);
        connection.waitForClientboundPackets();
        context.waitFor(client -> field(screen(client), "targetCount", Integer.class) == expectedCount);
      }
      context.runOnClient(client -> {
        var gui = screen(client);
        require(field(gui, "targetValue", Long.class) == ventUnitQuote,
            "Fourteen heat vents changed the synchronized unit quote");
        evidence.add("Target-card visual fixture: ic2:overclocked_heat_vent, authoritative unit="
            + ventUnitQuote + ", selectedCount=" + field(gui, "targetCount", Integer.class)
            + ", expected displayed total=" + ventUnitQuote * 14
            + ", displayedChance=" + field(gui, "chance", Float.class) + ".");
      });
      moveRelative(context, -12, 110);
      context.waitTicks(3);
      evidence.add("Fourteen-target total-value screenshot: " + context.takeScreenshot(
          TestScreenshotOptions.of("upgrader-target-value-fourteen").disableCounterPrefix()).toAbsolutePath());

      selectTarget(context, nanoHelmet, "Нановолоконный");
      connection.waitForServerboundPackets();
      server.waitFor(instance -> serverMenu(connection).getTarget() == nanoHelmet);
      connection.waitForClientboundPackets();
      context.waitFor(client -> screen(client).getMenu().getTarget() == nanoHelmet
          && field(screen(client), "targetCount", Integer.class) == 1);
      // Try to increment nonstackable equipment through the same + control: it must stay at one.
      clickRelative(context, 266, 86);
      connection.waitForServerboundPackets();
      connection.waitForClientboundPackets();
      server.runOnServer(instance -> require(field(serverMenu(connection), "targetCount", Integer.class) == 1,
          "Native amount control exceeded the IC2 helmet stack limit"));
      context.runOnClient(client -> {
        var gui = screen(client);
        require(field(gui, "targetValue", Long.class) == quotes[2], "Nano helmet price was not synchronized");
        require(Math.abs(field(gui, "chance", Float.class)
            - UpgradeOdds.chance(quotes[0] * 16.0, quotes[2])) < 1e-6,
            "Nano helmet chance differs from Upgrader's configured odds");
        evidence.add("Native equipment selector: nano_helmet x1; nonstackable count clamped to 1, price="
            + quotes[2] + ", displayedChance=" + field(gui, "chance", Float.class) + ".");
      });
      context.waitTicks(3);
      evidence.add("Russian native UI screenshot: " + context.takeScreenshot(
          TestScreenshotOptions.of("upgrader-ic2-nano-helmet").disableCounterPrefix()).toAbsolutePath());

      // Guarantee exactly one successful attempt only in this disposable test; restore every global in finally.
      context.runOnClient(client -> {
        client.options.chatWidth().set(1.0);
        client.options.chatScale().set(0.8);
        client.options.chatColors().set(true);
        client.gui.hud.getChat().clearMessages(false);
      });
      server.runOnServer(instance -> {
        UpgraderConfig.minChance = 1.0;
        UpgraderConfig.maxChance = 1.0;
        UpgraderConfig.spinTicks = 2;
        UpgraderConfig.attemptLimit = 0;
      });
      clickRelative(context, 78, 133);
      connection.waitForServerboundPackets();
      server.waitFor(instance -> serverMenu(connection).getInputStack().is(nanoHelmet)
          && !serverMenu(connection).isSpinning());
      connection.waitForClientboundPackets();
      context.waitFor(client -> screen(client).getMenu().getInputStack().is(nanoHelmet), 1200);
      server.runOnServer(instance -> {
        ItemStack output = serverMenu(connection).getInputStack();
        require(output.getCount() == 1 && ElectricItem.manager.getCharge(output) == 0.0,
            "Winning equipment was duplicated or unexpectedly charged");
        var inventory = connection.getServerPlayer().getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
          require(!inventory.getItem(i).is(ironPlate) && !inventory.getItem(i).is(nanoHelmet),
              "Upgrade left a duplicated input or extra output in the inventory");
        }
        require(serverMenu(connection).getCarried().isEmpty(), "Upgrade left an unexpected carried stack");
      });
      context.waitFor(client -> !field(screen(client), "spinning", Boolean.class), 1200);
      context.runOnClient(client -> {
        require(ElectricItem.manager.getCharge(screen(client).getMenu().getInputStack()) == 0.0,
            "Client output acquired free EU");
        require(field(screen(client), "lastResultWin", Boolean.class),
            "Actual success-result packet did not finish the client wheel animation");
      });
      evidence.add("Successful native Upgrade button/packet/2-tick server spin: all 16 iron plates consumed;"
          + " exactly one nano helmet replaced input; server/client charge=0 EU; no duplicate inventory/carried items.");
      evidence.add("Successful upgrade screenshot: " + context.takeScreenshot(
          TestScreenshotOptions.of("upgrader-ic2-success").disableCounterPrefix()).toAbsolutePath());
      String winner = server.computeOnServer(instance -> connection.getServerPlayer().getDisplayName().getString());
      context.waitFor(client -> !winMessages(client).isEmpty(), 1200);
      context.runOnClient(client -> {
        List<GuiMessage> messages = winMessages(client);
        require(messages.size() == 1, "The native chat received duplicate upgrade-win announcements");
        GuiMessage nativeMessage = messages.getFirst();
        require(nativeMessage.source() == GuiMessageSource.SYSTEM_SERVER,
            "The upgrade-win announcement did not arrive as a real server system message");
        Component message = nativeMessage.content();
        TranslatableContents outer = (TranslatableContents) message.getContents();
        require(outer.getArgs().length == 5, "The received announcement lost its structured arguments");
        Component badge = componentArgument(outer, 0);
        Component winnerName = componentArgument(outer, 1);
        Component won = componentArgument(outer, 2);
        Component chance = componentArgument(outer, 3);
        Component spent = componentArgument(outer, 4);
        require(badge.getString().equals("✦ [Апгрейд]") && badge.getStyle().isBold(),
            "The localized announcement badge lost its bold text");
        require(winnerName.getString().equals(winner), "The announcement named the wrong winner");
        require(won.getString().contains(new ItemStack(nanoHelmet).getHoverName().getString())
            && won.getString().endsWith(" ×1"), "The announcement lost its actual equipment output/count");
        require(spent.getString().contains(new ItemStack(ironPlate).getHoverName().getString())
            && spent.getString().endsWith(" ×16"), "The announcement lost its actual spent input/count");
        require(chance.getString().equals("100,00%"), "The announcement used the wrong committed chance");
        require(hasItemHover(won, nanoHelmet, 1) && hasItemHover(spent, ironPlate, 16),
            "Item tooltip hover data was flattened or lost in the real chat packet");
        require(badge.getStyle().getColor() != null && winnerName.getStyle().getColor() != null
            && won.getStyle().getColor() != null && chance.getStyle().getColor() != null
            && spent.getStyle().getColor() != null,
            "The real server announcement lost its colored component styles");
        require(message.getString().contains("\n  Шанс: 100,00%  •  Потрачено:"),
            "The Russian announcement did not retain the intended two-line format");
        evidence.add("Received exactly one SYSTEM_SERVER win announcement with root translation "
            + outer.getKey() + ": " + message.getString().replace("\n", " \\n ")
            + "; winner/item/count/chance styles and both real item-hover components preserved.");
      });
      // Close the actual menu via the server and open the native chat with its key binding.
      server.runOnServer(instance -> connection.getServerPlayer().closeContainer());
      connection.waitForClientboundPackets();
      context.waitFor(client -> client.gui.screen() == null);
      context.getInput().pressKey(options -> options.keyChat);
      context.waitFor(client -> client.gui.screen() instanceof ChatScreen);
      context.waitTicks(3);
      evidence.add("Native received chat screenshot: " + context.takeScreenshot(
          TestScreenshotOptions.of("upgrader-win-chat").disableCounterPrefix()).toAbsolutePath());
      context.waitTicks(5);
      context.runOnClient(client -> require(winMessages(client).size() == 1,
          "The win announcement was repeated after closing the station"));
      return evidence;
    } finally {
      server.runOnServer(instance -> {
        UpgraderConfig.minChance = saved.minChance();
        UpgraderConfig.maxChance = saved.maxChance();
        UpgraderConfig.spinTicks = saved.spinTicks();
        UpgraderConfig.attemptLimit = saved.attemptLimit();
        var player = connection.getServerPlayer();
        player.closeContainer();
        player.getInventory().clearContent();
        for (int i = 0; i < saved.inventory().size(); i++) player.getInventory().setItem(i, saved.inventory().get(i));
        player.getInventory().setSelectedSlot(saved.selected());
        player.setGameMode(saved.mode());
        player.teleportTo(connection.getServerLevel(), saved.x(), saved.y(), saved.z(), Set.of(),
            saved.yaw(), saved.pitch(), true);
        player.inventoryMenu.broadcastChanges();
      });
      connection.waitForClientboundPackets();
      context.setScreen(() -> null);
      context.runOnClient(client -> {
        client.options.chatWidth().set(savedChat.width());
        client.options.chatScale().set(savedChat.scale());
        client.options.chatColors().set(savedChat.colors());
        client.gui.hud.getChat().restoreState(savedChat.state());
      });
    }
  }

  private static void validateCategories(ClientGameTestContext context, TestServerContext server,
      TestServerConnection connection, List<String> evidence) {
    SavedMenuInteraction untouched = server.computeOnServer(instance -> {
      List<ItemStack> inventory = new ArrayList<>();
      var player = connection.getServerPlayer();
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
        inventory.add(player.getInventory().getItem(i).copy());
      }
      return new SavedMenuInteraction(serverMenu(connection).getTarget(),
          field(serverMenu(connection), "targetCount", Integer.class),
          serverMenu(connection).getInputStack().copy(), serverMenu(connection).getCarried().copy(), inventory);
    });
    clickRelative(context, 238, 42);
    List<CreativeCatalogCategories.Category> categories = context.computeOnClient(client -> categories(screen(client)));
    require(categories.size() > 14, "Creative category fixture did not exercise a second tab page");
    require(categories.getFirst().id().equals("all"), "The full server catalog was not the first category");
    require(categories.stream().map(CreativeCatalogCategories.Category::id).distinct().count() == categories.size(),
        "Creative category IDs were duplicated");
    for (String required : List.of("minecraft:functional_blocks", "ic2:machines", "ic2:combat",
        "ic2:tools_and_utilities")) {
      require(categories.stream().anyMatch(category -> category.id().equals(required)),
          "Native creative category was not reachable: " + required);
    }
    context.runOnClient(client -> {
      Set<Item> permitted = new HashSet<>(ClientCatalog.items());
      Set<Item> categorized = new HashSet<>();
      Set<Item> all = items(categories.getFirst().items());
      require(all.equals(permitted), "All categories lost or invented a server-authorized item ID");
      for (var category : categories) {
        Set<Item> members = items(category.items());
        require(permitted.containsAll(members), "Creative category invented an unauthorized target: " + category.id());
        Set<Item> nonBooks = new HashSet<>();
        for (ItemStack entry : category.items()) {
          require(entry.getCount() == 1, "A category item carried a creative stack count: " + category.id());
          require(!Set.of("ic2:creative_generator", "ic2:debug_item", "ic2:crop_seed_bag")
              .contains(BuiltInRegistries.ITEM.getKey(entry.getItem()).toString()),
              "A blocked IC2 target leaked in through creative categories");
          if (!entry.is(Items.ENCHANTED_BOOK)) {
            require(nonBooks.add(entry.getItem()), "Creative variants duplicated an ordinary target: " + category.id());
            require(ItemStack.isSameItemSameComponents(entry, new ItemStack(entry.getItem())),
                "Native creative-only components leaked into a permitted target: " + category.id());
          }
          if (entry.getItem() instanceof IElectricItem) {
            require(ElectricItem.manager.getCharge(entry) == 0.0,
                "An IC2 category copied free charge from the creative tab");
          }
        }
        if (category.id().equals("all") || category.id().equals("other")) continue;
        var nativeTab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(Identifier.parse(category.id()));
        require(nativeTab != null, "Category is not backed by a real native creative tab: " + category.id());
        Set<Item> nativeMembers = items(new ArrayList<>(nativeTab.getDisplayItems()));
        nativeMembers.retainAll(permitted);
        require(members.equals(nativeMembers), "Category differs from its real creative membership: " + category.id());
        require(category.title().getString().equals(nativeTab.getDisplayName().getString()),
            "Category did not use its localized native creative title");
        categorized.addAll(members);
      }
      Set<Item> otherExpected = new HashSet<>(permitted);
      otherExpected.removeAll(categorized);
      Set<Item> otherActual = categories.stream().filter(category -> category.id().equals("other"))
          .findFirst().map(category -> items(category.items())).orElseGet(HashSet::new);
      require(otherActual.equals(otherExpected), "Other category did not preserve uncategorized server items");
      categorized.addAll(otherActual);
      require(categorized.equals(permitted), "Creative tabs and Other did not cover the full server catalog");
      evidence.add("Creative category index: " + categories.size() + " categories, " + permitted.size()
          + " permitted item IDs, " + categories.getFirst().items().size() + " component-preserving targets; Other="
          + otherActual.size() + ". Every native tab exactly intersects the authorized server IDs; all IC2"
          + " entries are unique, uncharged defaults and blocked targets stay excluded.");
    });

    // Page arrows expose additional tabs while keeping the selected category and actual target intact.
    int lastPage = (categories.size() - 1) / 14;
    for (int page = 1; page <= lastPage; page++) {
      clickRelative(context, 269, 221);
      int expectedPage = page;
      context.runOnClient(client -> require(categoryPage(screen(client)) == expectedPage
          && selectedCategory(screen(client)).equals("all"), "Paging changed selection or skipped a tab page"));
    }
    for (int button : new int[] {InputConstants.MOUSE_BUTTON_RIGHT, InputConstants.MOUSE_BUTTON_MIDDLE}) {
      moveRelative(context, 27, -3);
      context.getInput().pressMouse(button);
      context.waitTick();
      context.runOnClient(client -> require(selectedCategory(screen(client)).equals("all"),
          "A non-left tab click changed the category"));
    }
    clickCategory(context, "all");
    clickRelative(context, 275, 205);
    require(scrollState(context).row() > 0, "All category did not retain a scrollable catalog");
    clickCategory(context, "ic2:machines");
    require(scrollState(context).row() == 0, "Changing the creative category kept a stale scroll row");
    replaceSearch(context, "ic2:nano_helmet");
    require(scrollState(context).count() == 0,
        "The active machine category search leaked equipment from another category");
    replaceSearch(context, "Формовщик");
    context.runOnClient(client -> require(items(filtered(screen(client))).contains(item("ic2:metal_former")),
        "Localized native search failed inside the IC2 machine category"));
    replaceSearch(context, "");
    assertCategoryContents(context, "ic2:machines");
    moveRelative(context, 30, 30);
    context.waitTicks(2);
    evidence.add("Native IC2 Machines category screenshot: " + context.takeScreenshot(
        TestScreenshotOptions.of("upgrader-category-ic2-machines").disableCounterPrefix()).toAbsolutePath());

    var lowerTab = java.util.stream.IntStream.range(0, categories.size())
        .filter(index -> index % 14 >= 7).mapToObj(categories::get).findFirst().orElseThrow();
    clickCategory(context, lowerTab.id());
    assertCategoryContents(context, lowerTab.id());
    clickCategory(context, "minecraft:functional_blocks");
    assertCategoryContents(context, "minecraft:functional_blocks");
    context.runOnClient(client -> require(items(filtered(screen(client))).contains(Items.LANTERN),
        "The functional-block category did not contain the native lantern"));
    moveRelative(context, 30, 30);
    context.waitTicks(2);
    evidence.add("Native vanilla Functional Blocks category screenshot: " + context.takeScreenshot(
        TestScreenshotOptions.of("upgrader-category-functional-blocks").disableCounterPrefix()).toAbsolutePath());
    if (categories.stream().anyMatch(category -> category.id().equals("other"))) {
      clickCategory(context, "other");
      assertCategoryContents(context, "other");
    }
    clickCategory(context, "all");
    assertCategoryContents(context, "all");
    connection.waitForServerboundPackets();
    server.runOnServer(instance -> {
      var menu = serverMenu(connection);
      require(menu.getTarget() == untouched.target()
          && field(menu, "targetCount", Integer.class) == untouched.count(),
          "Category, page, search or scrollbar clicks leaked into the actual server target controls");
      require(ItemStack.matches(menu.getInputStack(), untouched.input())
          && ItemStack.matches(menu.getCarried(), untouched.carried()),
          "Category UI navigation modified the input or cursor stack");
      var inventory = connection.getServerPlayer().getInventory();
      for (int index = 0; index < untouched.inventory().size(); index++) {
        require(ItemStack.matches(inventory.getItem(index), untouched.inventory().get(index)),
            "Category UI navigation leaked into the underlying inventory slot " + index);
      }
    });

    // The targets below travel through the existing real menu packets after selecting their native tabs.
    selectOpenCategoryTarget(context, server, connection, "ic2:machines", item("ic2:metal_former"),
        "Формовщик");
    clickRelative(context, 238, 42);
    selectOpenCategoryTarget(context, server, connection, "ic2:combat", item("ic2:nano_helmet"),
        "Нановолоконный");
    clickRelative(context, 238, 42);
    selectOpenCategoryTarget(context, server, connection, "ic2:tools_and_utilities", item("ic2:diamond_drill"),
        "ic2:diamond_drill");
    clickRelative(context, 238, 42);
    replaceSearch(context, "");
    clickCategory(context, "all");
    assertCategoryContents(context, "all");
    context.getInput().pressKey(InputConstants.KEY_ESCAPE);
    context.waitTick();
    evidence.add("PASS: actual tab-page arrows and upper/lower tabs keep the target/input/inventory untouched;"
        + " non-left clicks do not select tabs; localized category titles, machine-only Russian search, empty"
        + " cross-category search, category changes at row 0, Other coverage and All restoration; metal former,"
        + " nano helmet and diamond drill selected from their own native tabs through real server packets.");
  }

  @SuppressWarnings("unchecked")
  private static List<CreativeCatalogCategories.Category> categories(UpgraderScreen gui) {
    return field(gui, "ic2upgrader$categories", List.class);
  }

  @SuppressWarnings("unchecked")
  private static List<ItemStack> filtered(UpgraderScreen gui) {
    return field(gui, "filtered", List.class);
  }

  private static Set<Item> items(List<ItemStack> stacks) {
    Set<Item> result = new LinkedHashSet<>();
    for (ItemStack stack : stacks) result.add(stack.getItem());
    return result;
  }

  private static String selectedCategory(UpgraderScreen gui) {
    return field(gui, "ic2upgrader$selectedCategory", String.class);
  }

  private static int categoryPage(UpgraderScreen gui) {
    return field(gui, "ic2upgrader$categoryPage", Integer.class);
  }

  private static void clickCategory(ClientGameTestContext context, String id) {
    int index = context.computeOnClient(client -> {
      var categories = categories(screen(client));
      for (int i = 0; i < categories.size(); i++) if (categories.get(i).id().equals(id)) return i;
      throw new AssertionError("Missing native creative category " + id);
    });
    int expectedPage = index / 14;
    for (int attempts = 0; attempts < 10; attempts++) {
      int currentPage = context.computeOnClient(client -> categoryPage(screen(client)));
      if (currentPage == expectedPage) break;
      clickRelative(context, currentPage < expectedPage ? 269 : 251, 221);
    }
    require(context.computeOnClient(client -> categoryPage(screen(client))) == expectedPage,
        "Native page arrows did not expose creative category " + id);
    int slot = index % 14;
    clickRelative(context, 12 + (slot % 7) * 32 + 15, slot < 7 ? -3 : 243);
    context.runOnClient(client -> require(field(screen(client), "pickerOpen", Boolean.class)
        && selectedCategory(screen(client)).equals(id), "Native tab click failed to select " + id));
  }

  private static void assertCategoryContents(ClientGameTestContext context, String id) {
    context.runOnClient(client -> {
      var gui = screen(client);
      require(selectedCategory(gui).equals(id), "Wrong active category after navigation: expected " + id);
      var category = categories(gui).stream().filter(entry -> entry.id().equals(id)).findFirst().orElseThrow();
      require(ItemStack.listMatches(filtered(gui), category.items()),
          "Cleared category search did not restore all permitted component-preserving targets: " + id
              + "; " + searchDiagnostic(gui) + ", expectedCount=" + category.items().size()
              + ", sameList=" + (filtered(gui) == category.items())
              + ", actualFirst=" + filtered(gui).stream().limit(8)
                  .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()).toList()
              + ", expectedFirst=" + category.items().stream().limit(8)
                  .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()).toList());
    });
    require(scrollState(context).row() == 0 && !scrollState(context).dragging(),
        "Changing/clearing a category left a stale scroll or drag: " + id);
  }

  private static void selectOpenCategoryTarget(ClientGameTestContext context, TestServerContext server,
      TestServerConnection connection, String categoryId, Item target, String query) {
    clickCategory(context, categoryId);
    replaceSearch(context, query);
    int index = context.computeOnClient(client -> {
      var filtered = filtered(screen(client));
      for (int i = 0; i < filtered.size(); i++) if (filtered.get(i).is(target)) return i;
      throw new AssertionError("Native category search did not find " + target + " in " + categoryId);
    });
    require(index < 126, "Native category target unexpectedly requires scrolling: " + target);
    clickRelative(context, 18 + (index % 14) * 18 + 8, 44 + (index / 14) * 18 + 8);
    connection.waitForServerboundPackets();
    server.waitFor(instance -> serverMenu(connection).getTarget() == target);
    connection.waitForClientboundPackets();
    context.waitFor(client -> !field(screen(client), "pickerOpen", Boolean.class)
        && screen(client).getMenu().getTarget() == target);
  }

  private static void validateScrollbar(ClientGameTestContext context, TestServerContext server,
      TestServerConnection connection, List<String> evidence) {
    try {
      clickRelative(context, 238, 42);
      ScrollState initial = scrollState(context);
      require(initial.count() > 126 && initial.row() == 0 && !initial.dragging(),
          "The full native catalog must initially fit multiple pages at the top");
      for (int otherButton : new int[] {InputConstants.MOUSE_BUTTON_RIGHT, InputConstants.MOUSE_BUTTON_MIDDLE}) {
        moveRelative(context, 275, 205);
        context.getInput().pressMouse(otherButton);
        context.waitTick();
        require(scrollState(context).row() == 0 && !scrollState(context).dragging(),
            "The scrollbar accepted a non-left mouse button " + otherButton);
      }

      // Track clicks jump to either end without selecting the rightmost item column.
      clickRelative(context, 275, 205);
      ScrollState clickedBottom = scrollState(context);
      if (clickedBottom.row() != initial.maxRow()) {
        Path screenshot = context.takeScreenshot(TestScreenshotOptions.of("upgrader-scrollbar-bottom-failure")
            .disableCounterPrefix()).toAbsolutePath();
        String geometry = context.computeOnClient(client -> {
          var gui = screen(client);
          return "leftPos=" + inheritedField(gui, AbstractContainerScreen.class, "leftPos", Integer.class)
              + ", topPos=" + inheritedField(gui, AbstractContainerScreen.class, "topPos", Integer.class)
              + ", screen=" + client.getWindow().getScreenWidth() + "x" + client.getWindow().getScreenHeight()
              + ", gui=" + client.getWindow().getGuiScaledWidth() + "x" + client.getWindow().getGuiScaledHeight()
              + ", leftButton=" + InputConstants.MOUSE_BUTTON_LEFT;
        });
        String failure = "Bottom track click did not reach the final row: before=" + initial
            + ", after=" + clickedBottom + ", " + geometry + ", relative click=(275,205), screenshot=" + screenshot;
        Path directory = context.computeOnClient(client -> client.gameDirectory.toPath());
        try { Files.writeString(directory.resolve("upgrader-scrollbar-failure.txt"), failure); }
        catch (java.io.IOException exception) { throw new AssertionError(failure, exception); }
        throw new AssertionError(failure);
      }
      clickRelative(context, 275, 44);
      require(scrollState(context).row() == 0, "Top track click did not return to the first row");

      // Grab the actual thumb and move it with the real held mouse, including beyond the panel.
      moveRelative(context, 275, initial.thumbTop() + initial.thumbHeight() / 2.0);
      context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
      context.waitTick();
      require(scrollState(context).row() == 0 && scrollState(context).dragging(),
          "Grabbing the top thumb jumped or did not start dragging");
      moveRelative(context, 275, 125);
      context.waitTick();
      ScrollState middle = scrollState(context);
      require(middle.row() > 0 && middle.row() < middle.maxRow() && middle.dragging(),
          "Native thumb dragging did not reach an intermediate catalog row");
      evidence.add("Scrollable Russian catalog screenshot: " + context.takeScreenshot(
          TestScreenshotOptions.of("upgrader-catalog-scrollbar-midway").disableCounterPrefix()).toAbsolutePath());
      moveRelative(context, 305, 270);
      context.waitTick();
      require(scrollState(context).row() == initial.maxRow() && scrollState(context).dragging(),
          "Dragging below/outside the track did not clamp to the final row");
      context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
      context.waitTick();
      require(!scrollState(context).dragging(), "Releasing outside the panel left the thumb dragging");
      moveRelative(context, 275, 20);
      context.waitTick();
      require(scrollState(context).row() == initial.maxRow(), "Mouse movement scrolled after an outside release");

      ScrollState bottom = scrollState(context);
      moveRelative(context, 275, bottom.thumbTop() + bottom.thumbHeight() / 2.0);
      context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
      context.waitTick();
      require(scrollState(context).row() == bottom.row(), "Grabbing the bottom thumb jumped rows");
      moveRelative(context, 305, 15);
      context.waitTick();
      require(scrollState(context).row() == 0, "Dragging above/outside the track did not clamp to the first row");
      moveRelative(context, 275, 270);
      context.waitTick();
      context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
      context.waitTick();
      require(scrollState(context).row() == initial.maxRow(), "A full thumb drag did not reach the final row");

      // The original wheel still changes one row, and the visible thumb can be grabbed without a jump.
      moveRelative(context, 150, 120);
      context.getInput().scroll(1.0);
      context.waitTick();
      ScrollState wheeled = scrollState(context);
      require(wheeled.row() == initial.maxRow() - 1, "The native wheel stopped scrolling the catalog");
      moveRelative(context, 275, wheeled.thumbTop() + wheeled.thumbHeight() / 2.0);
      context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
      context.waitTick();
      require(scrollState(context).row() == wheeled.row(), "The wheel and thumb position became inconsistent");
      moveRelative(context, 275, 125);
      context.waitTick();
      context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
      context.waitTick();
      ScrollState selectedPage = scrollState(context);
      require(selectedPage.row() > 0 && selectedPage.row() < selectedPage.maxRow(),
          "The scrolled-grid selection fixture was not on an intermediate page");
      Item expected = context.computeOnClient(client -> ((ItemStack)
          field(screen(client), "filtered", List.class).get(selectedPage.row() * 14)).getItem());
      clickRelative(context, 26, 52);
      connection.waitForServerboundPackets();
      server.waitFor(instance -> serverMenu(connection).getTarget() == expected);
      connection.waitForClientboundPackets();
      context.waitFor(client -> !field(screen(client), "pickerOpen", Boolean.class)
          && screen(client).getMenu().getTarget() == expected);
      evidence.add("PASS: native right/middle buttons leave scrolling unchanged; left track clicks at both ends;"
          + " held thumb drags to middle/top/bottom with outside"
          + " clamping; outside release stops dragging; original wheel and thumb remain synchronized; scrolled"
          + " grid selected the actual item " + BuiltInRegistries.ITEM.getKey(expected) + " through the server packet.");

      clickRelative(context, 238, 42);
      require(scrollState(context).row() == 0 && !scrollState(context).dragging(),
          "Reopening the picker kept a stale scroll position or drag");
      clickRelative(context, 275, 205);
      replaceSearch(context, "ic2:nano_helmet");
      ScrollState narrow = scrollState(context);
      require(narrow.count() > 0 && narrow.count() <= 126 && narrow.row() == 0 && narrow.maxRow() == 0
          && !narrow.dragging(), "Narrowing search retained an invalid row or active drag");
      clickRelative(context, 275, 150);
      moveRelative(context, 275, 205);
      context.getInput().scroll(-1.0);
      context.waitTick();
      require(scrollState(context).row() == 0 && !scrollState(context).dragging(),
          "The disabled short-list scrollbar still scrolled or started dragging");

      replaceSearch(context, "ic2:there_is_no_such_item_scrollbar_fixture");
      ScrollState empty = scrollState(context);
      require(empty.count() == 0 && empty.row() == 0 && empty.maxRow() == 0 && !empty.dragging(),
          "The empty search retained a stale scrollbar state");
      clickRelative(context, 275, 150);
      context.getInput().scroll(-1.0);
      context.waitTick();
      require(scrollState(context).row() == 0 && !scrollState(context).dragging(),
          "The empty-list scrollbar accepted a drag or scroll");
      replaceSearch(context, "");
      require(scrollState(context).count() == initial.count() && scrollState(context).row() == 0,
          "Clearing the native search did not restore the full catalog at the top");

      // Reinitializing a live picker must cancel a held drag and use its new screen origin.
      moveRelative(context, 275, 52);
      context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
      context.waitTick();
      moveRelative(context, 275, 125);
      context.waitTick();
      context.getInput().resizeWindow(1280, 800);
      context.runOnClient(Minecraft::resizeGui);
      ScrollState resized = scrollState(context);
      require(resized.row() >= 0 && resized.row() <= resized.maxRow() && !resized.dragging(),
          "Resizing kept an active drag or put the catalog outside its bounds");
      context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
      clickRelative(context, 275, 205);
      require(scrollState(context).row() == scrollState(context).maxRow(),
          "The resized scrollbar no longer accepted clicks at its actual screen origin");
      context.getInput().resizeWindow(1600, 900);
      context.runOnClient(Minecraft::resizeGui);
      context.getInput().pressKey(InputConstants.KEY_ESCAPE);
      context.waitTick();
      context.runOnClient(client -> require(!field(screen(client), "pickerOpen", Boolean.class)
          && !field(screen(client), "ic2upgrader$dragging", Boolean.class),
          "Closing the catalog kept an active picker or drag"));
      evidence.add("PASS: reopening resets the catalog; native search after scrolling resets to row 0 without stale"
          + " dragging; one-page/empty results disable scrolling; clearing search restores all items; live window"
          + " resize cancels dragging and preserves valid bounds and clickable track geometry.");
    } finally {
      context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
    }
  }

  private static ScrollState scrollState(ClientGameTestContext context) {
    return context.computeOnClient(client -> {
      var gui = screen(client);
      require(field(gui, "pickerOpen", Boolean.class), "Expected the native catalog picker to be open");
      int count = field(gui, "filtered", List.class).size();
      int rows = (count + 13) / 14;
      int maximum = Math.max(0, rows - 9);
      int row = field(gui, "scrollRow", Integer.class);
      int height = maximum == 0 ? 162 : Math.max(16, (int) Math.round(162.0 * 9 / rows));
      int top = 44 + (maximum == 0 ? 0 : (int) Math.round((double) row / maximum * (162 - height)));
      return new ScrollState(count, row, maximum, top, height, field(gui, "ic2upgrader$dragging", Boolean.class));
    });
  }

  private static void replaceSearch(ClientGameTestContext context, String query) {
    clickRelative(context, 30, 30);
    int oldLength = context.computeOnClient(client ->
        field(screen(client), "searchBox", EditBox.class).getValue().length());
    // Fabric's native test input emits key events with modifiers=0, even while Control is held.
    // End/backspace exercises the real edit box without relying on a synthetic Ctrl+A shortcut.
    context.getInput().pressKey(InputConstants.KEY_END);
    for (int i = 0; i < oldLength; i++) context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
    try {
      context.waitFor(client -> field(screen(client), "searchBox", EditBox.class).getValue().isEmpty(), 40);
    } catch (RuntimeException | AssertionError exception) {
      String diagnostic = context.computeOnClient(client -> searchDiagnostic(screen(client)));
      throw new AssertionError("Native End/backspace search clearing did not finish within 40 ticks; "
          + diagnostic, exception);
    }
    if (!query.isEmpty()) context.getInput().typeChars(query);
    try {
      context.waitFor(client -> field(screen(client), "searchBox", EditBox.class).getValue().equals(query), 40);
    } catch (RuntimeException | AssertionError exception) {
      String diagnostic = context.computeOnClient(client -> searchDiagnostic(screen(client)));
      throw new AssertionError("Native search replacement did not enter expected text='" + query
          + "' within 40 ticks; " + diagnostic, exception);
    }
    context.runOnClient(client -> {
      var gui = screen(client);
      EditBox search = field(gui, "searchBox", EditBox.class);
      require(search.getValue().equals(query), "Native search replacement did not enter expected text='"
          + query + "'; " + searchDiagnostic(gui));
    });
  }

  private static String searchDiagnostic(UpgraderScreen gui) {
    EditBox search = field(gui, "searchBox", EditBox.class);
    return "actualText='" + search.getValue() + "', highlight='" + search.getHighlighted()
        + "', cursor=" + search.getCursorPosition() + ", focused=" + search.isFocused()
        + ", selectedCategory=" + selectedCategory(gui) + ", filteredCount=" + filtered(gui).size();
  }

  private static void selectTarget(ClientGameTestContext context, Item target, String query) {
    clickRelative(context, 238, 42);
    context.runOnClient(client -> require(field(screen(client), "pickerOpen", Boolean.class),
        "Native target-picker click did not open the catalog"));
    clickCategory(context, "all");
    replaceSearch(context, query);
    int index = context.computeOnClient(client -> {
      List<?> filtered = field(screen(client), "filtered", List.class);
      for (int i = 0; i < filtered.size(); i++) {
        if (((ItemStack) filtered.get(i)).is(target)) return i;
      }
      throw new AssertionError("Localized native catalog search did not find " + query);
    });
    require(index < 126, "Fixture target unexpectedly requires scrolling");
    clickRelative(context, 18 + (index % 14) * 18 + 8, 44 + (index / 14) * 18 + 8);
    context.waitFor(client -> screen(client).getMenu().getTarget() == target);
  }

  private static void clickRelative(ClientGameTestContext context, int x, int y) {
    moveRelative(context, x, y);
    context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
    context.waitTick();
  }

  private static void moveRelative(ClientGameTestContext context, double x, double y) {
    double[] cursor = context.computeOnClient(client -> {
      var gui = screen(client);
      int left = inheritedField(gui, AbstractContainerScreen.class, "leftPos", Integer.class);
      int top = inheritedField(gui, AbstractContainerScreen.class, "topPos", Integer.class);
      var window = client.getWindow();
      return new double[] {(left + x) * (double) window.getScreenWidth() / window.getGuiScaledWidth(),
          (top + y) * (double) window.getScreenHeight() / window.getGuiScaledHeight()};
    });
    context.getInput().setCursorPos(cursor[0], cursor[1]);
  }

  private static UpgraderScreen screen(Minecraft client) {
    require(client.gui.screen() instanceof UpgraderScreen, "Expected the real Upgrader screen");
    return (UpgraderScreen) client.gui.screen();
  }

  private static List<GuiMessage> winMessages(Minecraft client) {
    List<?> messages = inheritedField(client.gui.hud.getChat(), ChatComponent.class, "allMessages", List.class);
    return messages.stream().map(GuiMessage.class::cast).filter(message ->
        message.content().getContents() instanceof TranslatableContents translation
            && translation.getKey().equals("chat.ic2_upgrader_compat.win")).toList();
  }

  private static Component componentArgument(TranslatableContents contents, int index) {
    require(contents.getArgs()[index] instanceof Component,
        "The win announcement flattened component argument " + index);
    return (Component) contents.getArgs()[index];
  }

  private static boolean hasItemHover(Component component, Item item, int count) {
    if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowItem hover
        && hover.item().create().is(item) && hover.item().count() == count) return true;
    for (Component sibling : component.getSiblings()) if (hasItemHover(sibling, item, count)) return true;
    if (component.getContents() instanceof TranslatableContents translation) {
      for (Object argument : translation.getArgs()) {
        if (argument instanceof Component nested && hasItemHover(nested, item, count)) return true;
      }
    }
    return false;
  }

  private static UpgraderMenu serverMenu(TestServerConnection connection) {
    require(connection.getServerPlayer().containerMenu instanceof UpgraderMenu, "Expected the real server Upgrader menu");
    return (UpgraderMenu) connection.getServerPlayer().containerMenu;
  }

  private static <T> T field(Object instance, String name, Class<T> type) {
    return inheritedField(instance, instance.getClass(), name, type);
  }

  private static <T> T inheritedField(Object instance, Class<?> owner, String name, Class<T> type) {
    try {
      Field field = owner.getDeclaredField(name);
      field.setAccessible(true);
      return type.cast(field.get(instance));
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError("Could not inspect native Upgrader field " + owner.getName() + "." + name, exception);
    }
  }

  private static void reloadLanguage(ClientGameTestContext context, String selected, String option) {
    CompletableFuture<Void> reload = context.computeOnClient(client -> {
      client.getLanguageManager().setSelected(selected);
      client.options.languageCode = option;
      return client.reloadResourcePacks();
    });
    context.waitFor(client -> reload.isDone(), 1200);
    reload.join();
  }

  private static Item item(String id) {
    Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    require(item != null && item != Items.AIR, "Missing client fixture item " + id);
    return item;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private record SavedPlayer(List<ItemStack> inventory, int selected, GameType mode,
      double x, double y, double z, float yaw, float pitch,
      double minChance, double maxChance, int spinTicks, int attemptLimit) {}

  private record SavedChat(ChatComponent.State state, double width, double scale, boolean colors) {}

  private record SavedMenuInteraction(Item target, int count, ItemStack input, ItemStack carried,
      List<ItemStack> inventory) {}

  private record ScrollState(int count, int row, int maxRow, int thumbTop, int thumbHeight, boolean dragging) {}
}
