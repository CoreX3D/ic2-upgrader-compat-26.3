package dev.core.ic2upgrader.gametest;

import dev.core.ic2upgrader.Ic2Pricing;
import dev.core.ic2upgrader.UpgradeAnnouncements;
import ic2.api.item.ElectricItem;
import ic2.core.item.ElectricItemManager;
import ic2.core.ref.Ic2Items;
import ic2.core.util.StackUtil;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.execheinz.upgrader.AttemptLimiter;
import net.execheinz.upgrader.UpgraderConfig;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.execheinz.upgrader.value.ItemValues;
import net.execheinz.upgrader.value.UpgradeOdds;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/** Observes actual global system-chat broadcasts while exercising the original server menu. */
public final class UpgradeAnnouncementGameTests {
  private static final String EMPTY = "ic2_upgrader_compat:gametest/empty3x3x3";
  private static final ThreadLocal<List<Broadcast>> CAPTURE = new ThreadLocal<>();

  static {
    ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
      List<Broadcast> active = CAPTURE.get();
      if (active != null && message.getContents() instanceof TranslatableContents translated
          && translated.getKey().equals("chat.ic2_upgrader_compat.win")) {
        active.add(new Broadcast(server, message.copy(), overlay));
      }
    });
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void successfulUpgradeBroadcastsOnceAfterTheActualResult(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Capture messages = new Capture()) {
      deterministicChance(1);
      ServerPlayer player = freshPlayer(helper);
      UpgraderMenu menu = menu(player);
      ItemStack spent = named(new ItemStack(Items.DIAMOND, 9), "announcement input nine diamonds");
      menu.getSlot(0).set(spent);
      menu.setTarget(player, Ic2Items.COPPER_PLATE);
      menu.setCount(player, 7);
      menu.startUpgrade(player);
      helper.assertTrue(messages.values.isEmpty(), "Starting a spin must not announce a result early");
      menu.broadcastChanges();
      helper.assertTrue(messages.values.isEmpty(), "An unfinished animation must not announce a result");
      menu.startUpgrade(player);
      menu.broadcastChanges();
      Broadcast announced = onlyBroadcast(helper, messages);
      helper.assertTrue(announced.server == helper.getLevel().getServer() && !announced.overlay,
          "The win must be broadcast globally in chat rather than the action bar");
      String text = announced.message.getString();
      helper.assertTrue(text.contains(player.getName().getString()) && text.contains("100"),
          "The announcement must identify the winner and the original 100 percent chance: " + text);
      assertHoverContains(helper, announced.message, spent,
          "The spent stack tooltip must retain the original name and complete stack count");
      assertHoverContains(helper, announced.message, new ItemStack(Ic2Items.COPPER_PLATE, 7),
          "The prize tooltip must retain the actual seven-item result");
      helper.assertTrue(menu.getInputStack().is(Ic2Items.COPPER_PLATE)
              && menu.getInputStack().getCount() == 7 && player.getInventory().isEmpty(),
          "Announcing a win must preserve the original output and single input consumption");
      long coloredSegments = flatStyles(announced.message).stream()
          .filter(style -> style.getColor() != null).map(style -> style.getColor().getValue()).distinct().count();
      helper.assertTrue(coloredSegments >= 3, "The announcement should use distinct readable accent colors");
      menu.broadcastChanges();
      menu.broadcastChanges();
      menu.removed(player);
      helper.assertTrue(messages.values.size() == 1, "Further ticks and closing must never duplicate a win broadcast");
      AttemptLimiter.forget(player.getUUID());
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void acceptedSpinRetainsOriginalInputAndStartChance(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Capture messages = new Capture()) {
      deterministicChance(0.1234567);
      ServerPlayer player = freshPlayer(helper);
      UpgraderMenu menu = menu(player);
      ItemStack input = named(new ItemStack(Ic2Items.URANIUM_FUEL_ROD, 3), "original fuel investment");
      StackUtil.updateNbtData(input, tag -> tag.putInt("use", 12_345));
      ItemStack expectedSpent = input.copy();
      menu.getSlot(0).set(input);
      menu.setTarget(player, Ic2Items.NANO_HELMET);
      double chance = UpgradeOdds.chance(player.level(), input, Ic2Items.NANO_HELMET, 1);
      helper.assertTrue(Math.abs(chance - 0.1234567) < 1e-12, "The fixture must begin with a nonintegral probability");
      menu.startUpgrade(player);
      // Fix the result only after the native RNG roll; do not change the probability being recorded.
      setField(menu, "pendingWin", true);
      UpgraderConfig.minChance = 1;
      UpgraderConfig.maxChance = 1;
      input.set(DataComponents.CUSTOM_NAME, Component.literal("mutated after acceptance"));
      StackUtil.updateNbtData(input, tag -> tag.putInt("use", 99_999));
      menu.startUpgrade(player); // Repeated clicks while spinning must not replace the first snapshot.
      finish(menu);
      Broadcast announced = onlyBroadcast(helper, messages);
      String text = announced.message.getString().replace(',', '.');
      helper.assertTrue(text.contains("12.35%") && !text.contains("100%"),
          "The announcement must retain the accepted 12.35 percent chance after config changes: " + text);
      assertHoverContains(helper, announced.message, expectedSpent,
          "The investment tooltip must use a deep copy of the original components and count");
      helper.assertTrue(!text.contains("mutated after acceptance"),
          "Later modifications of the mutable menu stack must not alter the announcement");
      helper.assertTrue(menu.getInputStack().is(Ic2Items.NANO_HELMET)
              && ElectricItem.manager.getCharge(menu.getInputStack()) == 0,
          "Announcements must not charge the prize or transfer the investment's components");
      AttemptLimiter.forget(player.getUUID());
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void losingAttemptDoesNotBroadcastOrRefundInput(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Capture messages = new Capture()) {
      deterministicChance(0);
      ServerPlayer player = freshPlayer(helper);
      UpgraderMenu menu = menu(player);
      menu.getSlot(0).set(new ItemStack(Items.DIAMOND, 5));
      menu.setTarget(player, Ic2Items.QUANTUM_HELMET);
      menu.startUpgrade(player);
      finish(menu);
      menu.broadcastChanges();
      menu.removed(player);
      helper.assertTrue(messages.values.isEmpty(), "A failed upgrade must not issue a public winning announcement");
      helper.assertTrue(menu.getInputStack().isEmpty() && player.getInventory().isEmpty(),
          "A failed upgrade still consumes the investment exactly once without a prize or refund");
      AttemptLimiter.forget(player.getUUID());
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void idleClosingAndInvalidAttemptsStaySilent(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Capture messages = new Capture()) {
      deterministicChance(1);
      ServerPlayer player = freshPlayer(helper);
      UpgraderMenu menu = menu(player);
      menu.startUpgrade(player);
      menu.setTarget(player, Ic2Items.NANO_HELMET);
      menu.startUpgrade(player); // No input.
      menu.setTarget(player, null);
      ItemStack input = named(ElectricItemManager.getCharged(Ic2Items.IRIDIUM_DRILL, 100_000), "return intact");
      ItemStack original = input.copy();
      menu.getSlot(0).set(input);
      menu.startUpgrade(player); // No target.
      menu.broadcastChanges();
      menu.removed(player);
      helper.assertTrue(messages.values.isEmpty(), "Rejected attempts and closing an idle station must be silent");
      helper.assertTrue(player.getInventory().getNonEquipmentItems().stream().anyMatch(stack -> same(stack, original)),
          "Closing idle must retain the original charged and named investment");
      AttemptLimiter.forget(player.getUUID());
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void cooldownRejectedAttemptCannotPublishAWin(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Capture messages = new Capture()) {
      deterministicChance(1);
      UpgraderConfig.attemptLimit = 1;
      UpgraderConfig.attemptWindowSeconds = 3600;
      ServerPlayer player = freshPlayer(helper);
      try {
        AttemptLimiter.record(player);
        helper.assertTrue(AttemptLimiter.cooldownRemaining(player) > 0, "The fixture must be in a real attempt cooldown");
        UpgraderMenu menu = menu(player);
        ItemStack input = new ItemStack(Items.DIAMOND, 5);
        menu.getSlot(0).set(input);
        menu.setTarget(player, Ic2Items.NANO_HELMET);
        menu.startUpgrade(player);
        finish(menu);
        helper.assertTrue(!menu.isSpinning() && same(menu.getInputStack(), input),
            "A cooldown rejection must preserve the input and never begin a spin");
        menu.removed(player);
        helper.assertTrue(messages.values.isEmpty(), "A rejected cooldown click must never announce a win");
      } finally {
        AttemptLimiter.forget(player.getUUID());
      }
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void closingAWinningSpinUsesTheOriginalResultOnce(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Capture messages = new Capture()) {
      deterministicChance(1);
      ServerPlayer player = freshPlayer(helper);
      UpgraderMenu menu = menu(player);
      ItemStack investment = new ItemStack(Items.EMERALD, 4);
      menu.getSlot(0).set(investment);
      menu.setTarget(player, Ic2Items.NANO_HELMET);
      menu.startUpgrade(player);
      helper.assertTrue(messages.values.isEmpty(), "The winning result is still pending before close");
      menu.removed(player); // Upgrader intentionally completes a pending result when the menu closes.
      Broadcast announced = onlyBroadcast(helper, messages);
      assertHoverContains(helper, announced.message, investment, "A closed winning spin must show its actual investment");
      List<ItemStack> retained = player.getInventory().getNonEquipmentItems().stream().filter(s -> !s.isEmpty()).toList();
      helper.assertTrue(retained.size() == 1 && retained.getFirst().is(Ic2Items.NANO_HELMET)
              && retained.getFirst().getCount() == 1 && ElectricItem.manager.getCharge(retained.getFirst()) == 0,
          "Closing a winning spin must return only the original uncharged prize");
      menu.removed(player);
      menu.broadcastChanges();
      helper.assertTrue(messages.values.size() == 1, "Closing and post-close ticks must not announce the same success twice");
      AttemptLimiter.forget(player.getUUID());
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void laterAttemptHasItsOwnSnapshotAfterLossAndSuccess(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Capture messages = new Capture()) {
      deterministicChance(0);
      ServerPlayer player = freshPlayer(helper);
      UpgraderMenu menu = menu(player);
      menu.getSlot(0).set(named(new ItemStack(Items.COAL, 2), "lost investment"));
      menu.setTarget(player, Ic2Items.IRON_PLATE);
      menu.startUpgrade(player);
      finish(menu);
      helper.assertTrue(messages.values.isEmpty(), "A preceding loss must be silent");
      deterministicChance(1);
      ItemStack first = named(new ItemStack(Items.GOLD_INGOT, 3), "first winning investment");
      menu.getSlot(0).set(first);
      menu.setTarget(player, Ic2Items.COPPER_PLATE);
      menu.setCount(player, 2);
      menu.startUpgrade(player);
      finish(menu);
      assertHoverContains(helper, onlyBroadcast(helper, messages).message, first,
          "A win after a loss must use the new investment rather than the discarded snapshot");
      ItemStack second = named(new ItemStack(Items.DIAMOND, 6), "second winning investment");
      menu.getSlot(0).set(second);
      menu.setTarget(player, Ic2Items.NANO_HELMET);
      menu.startUpgrade(player);
      finish(menu);
      helper.assertTrue(messages.values.size() == 2, "Each completed success must emit exactly one distinct announcement");
      Broadcast later = messages.values.get(1);
      assertHoverContains(helper, later.message, second, "Consecutive successes must retain independent stack snapshots");
      helper.assertTrue(!later.message.getString().contains("first winning investment")
              && !later.message.getString().contains("lost investment"),
          "The next win must not retain stale investment text");
      AttemptLimiter.forget(player.getUUID());
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void missingInvestmentAtCompletionCannotPublishAnUncreatedPrize(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Capture messages = new Capture()) {
      deterministicChance(1);
      ServerPlayer player = freshPlayer(helper);
      UpgraderMenu menu = menu(player);
      menu.getSlot(0).set(new ItemStack(Items.DIAMOND, 2));
      menu.setTarget(player, Ic2Items.NANO_HELMET);
      menu.startUpgrade(player);
      // Native clicks are locked during spinning, but another mod may remove the stack directly.
      menu.getSlot(0).set(ItemStack.EMPTY);
      finish(menu);
      menu.removed(player);
      helper.assertTrue(messages.values.isEmpty(), "If no input remains and Upgrader makes no prize, no win may be announced");
      helper.assertTrue(menu.getInputStack().isEmpty() && player.getInventory().isEmpty(),
          "The announcement integration must not create output for an aborted native result");
      AttemptLimiter.forget(player.getUUID());
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void blacklistedInputCannotStartOrPublishAWin(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Capture messages = new Capture()) {
      deterministicChance(1);
      ServerPlayer player = freshPlayer(helper);
      UpgraderMenu menu = menu(player);
      UpgraderConfig.blacklist = Set.of("minecraft:diamond");
      ItemStack input = new ItemStack(Items.DIAMOND, 2);
      menu.getSlot(0).set(input);
      menu.setTarget(player, Ic2Items.NANO_HELMET);
      menu.startUpgrade(player);
      finish(menu);
      helper.assertTrue(!menu.isSpinning() && same(menu.getInputStack(), input),
          "The original server blacklist must continue rejecting the input without consumption");
      menu.removed(player);
      helper.assertTrue(messages.values.isEmpty(), "A blacklist rejection must not publish a winning announcement");
      AttemptLimiter.forget(player.getUUID());
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void probabilityTextRetainsSmallChancesWithoutLocaleOrZeroRoundingErrors(GameTestHelper helper) {
    helper.assertTrue(UpgradeAnnouncements.formatChance(1).equals("100,00%"), "A certain win must show 100 percent");
    helper.assertTrue(UpgradeAnnouncements.formatChance(0).equals("0,00%"), "An impossible win must show zero percent");
    helper.assertTrue(UpgradeAnnouncements.formatChance(0.1234567).equals("12,35%"),
        "Ordinary probabilities must use readable two-place Russian percentage formatting");
    helper.assertTrue(UpgradeAnnouncements.formatChance(0.000001).equals("0,0001%"),
        "A positive tiny probability must retain enough decimal places to avoid displaying zero");
    helper.assertTrue(UpgradeAnnouncements.formatChance(1e-12).equals("<0,000001%"),
        "A probability below the display precision must use an explicit upper bound");
    helper.succeed();
  }

  private static List<Style> flatStyles(Component component) {
    List<Style> styles = new ArrayList<>();
    component.visit((style, text) -> { styles.add(style); return Optional.empty(); }, Style.EMPTY);
    return styles;
  }

  private static void assertHoverContains(GameTestHelper helper, Component component, ItemStack expected, String message) {
    boolean found = flatStyles(component).stream().map(Style::getHoverEvent)
        .filter(event -> event instanceof HoverEvent.ShowItem)
        .map(event -> ((HoverEvent.ShowItem) event).item().create()).anyMatch(stack -> same(stack, expected));
    helper.assertTrue(found, message + ": missing " + expected);
  }

  private static boolean same(ItemStack first, ItemStack second) {
    return first.getCount() == second.getCount() && ItemStack.isSameItemSameComponents(first, second);
  }

  private static ItemStack named(ItemStack stack, String name) {
    stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
    return stack;
  }

  private static Broadcast onlyBroadcast(GameTestHelper helper, Capture capture) {
    helper.assertTrue(capture.values.size() == 1,
        "Expected one actual global win broadcast, found " + capture.values.size() + ": " + capture.values);
    return capture.values.getFirst();
  }

  private static void finish(UpgraderMenu menu) {
    for (int i = 0; i < 4; i++) menu.broadcastChanges();
  }

  private static ServerPlayer freshPlayer(GameTestHelper helper) {
    ServerPlayer player = helper.makeMockServerPlayerInLevel();
    player.setGameMode(GameType.SURVIVAL);
    player.getInventory().clearContent();
    return player;
  }

  private static UpgraderMenu menu(ServerPlayer player) {
    UpgraderMenu menu = new UpgraderMenu(1, player.getInventory());
    player.containerMenu = menu;
    return menu;
  }

  private static void deterministicChance(double chance) {
    UpgraderConfig.minChance = chance;
    UpgraderConfig.maxChance = chance;
    UpgraderConfig.spinTicks = 2;
    UpgraderConfig.maxTargetCount = 64;
    UpgraderConfig.attemptLimit = 0;
  }

  private record Broadcast(MinecraftServer server, Component message, boolean overlay) {}

  private static final class Capture implements AutoCloseable {
    final List<Broadcast> values = new ArrayList<>();
    Capture() {
      if (CAPTURE.get() != null) throw new IllegalStateException("Nested announcement captures are unsupported");
      CAPTURE.set(values);
    }
    @Override public void close() { CAPTURE.remove(); }
  }

  private static void setField(Object owner, String name, Object value) {
    try {
      Field field = UpgraderMenu.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(owner, value);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("Cannot set native menu fixture field " + name, exception);
    }
  }

  private record ConfigSnapshot(double houseEdge, double minChance, double maxChance, int spinTicks,
      Map<String, Double> overrides, Set<String> blacklist, int maxTargetCount, int attemptLimit,
      int attemptWindowSeconds, double globalMultiplier, Map<String, Double> multipliers,
      Object cachedValues, Object pricingDiagnostics, Object pricingProvenance) implements AutoCloseable {
    static ConfigSnapshot capture() {
      return new ConfigSnapshot(UpgraderConfig.houseEdge, UpgraderConfig.minChance, UpgraderConfig.maxChance,
          UpgraderConfig.spinTicks, UpgraderConfig.valueOverrides, UpgraderConfig.blacklist,
          UpgraderConfig.maxTargetCount, UpgraderConfig.attemptLimit, UpgraderConfig.attemptWindowSeconds,
          UpgraderConfig.globalValueMultiplier, UpgraderConfig.valueMultipliers,
          field(ItemValues.class, "values"), field(Ic2Pricing.class, "last"), field(Ic2Pricing.class, "lastProvenance"));
    }
    @Override public void close() {
      UpgraderConfig.apply(houseEdge, minChance, maxChance, spinTicks, overrides, blacklist,
          maxTargetCount, attemptLimit, attemptWindowSeconds, globalMultiplier, multipliers);
      restore(ItemValues.class, "values", cachedValues);
      restore(Ic2Pricing.class, "last", pricingDiagnostics);
      restore(Ic2Pricing.class, "lastProvenance", pricingProvenance);
    }
    private static Object field(Class<?> owner, String name) {
      try {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
      } catch (ReflectiveOperationException exception) {
        throw new IllegalStateException("Cannot save test configuration " + name, exception);
      }
    }
    private static void restore(Class<?> owner, String name, Object value) {
      try {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
      } catch (ReflectiveOperationException exception) {
        throw new IllegalStateException("Cannot restore test configuration " + name, exception);
      }
    }
  }
}
