package dev.core.ic2upgrader.gametest;

import dev.core.ic2upgrader.BookTargetMenu;
import dev.core.ic2upgrader.BookTargets;
import dev.core.ic2upgrader.Ic2Pricing;
import dev.core.ic2upgrader.UpgradeAnnouncements;
import ic2.api.item.ElectricItem;
import ic2.core.item.ElectricItemManager;
import ic2.core.ref.Ic2Items;
import ic2.core.util.StackUtil;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.execheinz.upgrader.AttemptLimiter;
import net.execheinz.upgrader.UpgraderConfig;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.execheinz.upgrader.value.ItemValues;
import net.execheinz.upgrader.value.UpgradeOdds;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;

/** Native server coverage of selected enchantment components, their prices, and real menu results. */
public final class BookUpgradeGameTests {
  private static final String EMPTY = "ic2_upgrader_compat:gametest/empty3x3x3";
  private static final ThreadLocal<List<Component>> CAPTURE = new ThreadLocal<>();

  static {
    ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
      List<Component> active = CAPTURE.get();
      if (active != null && !overlay && message.getContents() instanceof TranslatableContents translated
          && translated.getKey().equals("chat.ic2_upgrader_compat.win")) active.add(message.copy());
    });
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void catalogueContainsEveryNativeEnchantmentAndEveryLegalLevel(GameTestHelper helper) {
    Set<BookTargets.Key> expected = new HashSet<>();
    helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements().forEach(holder -> {
      if (holder.value().getSupportedItems().stream()
          .noneMatch(item -> item.value().isEnabled(helper.getLevel().enabledFeatures()))) return;
      for (int level = Math.max(1, holder.value().getMinLevel()); level <= holder.value().getMaxLevel(); level++) {
        expected.add(new BookTargets.Key(holder.key().identifier().toString(), level));
      }
    });
    List<BookTargets.Entry> catalog = BookTargets.catalog(helper.getLevel());
    Set<BookTargets.Key> actual = catalog.stream().map(BookTargets.Entry::key).collect(Collectors.toSet());
    helper.assertTrue(actual.equals(expected) && catalog.size() == expected.size(),
        "The server catalogue must contain exactly all native enchantments and their legal levels, without duplicates");
    helper.assertTrue(actual.contains(key("thorns", 1)) && actual.contains(key("thorns", 3))
            && actual.contains(key("protection", 4)) && actual.contains(key("mending", 1))
            && actual.contains(key("binding_curse", 1)) && actual.contains(key("vanishing_curse", 1)),
        "Requested levels, treasure enchantments and curses must all remain selectable");
    for (BookTargets.Entry entry : catalog) {
      helper.assertTrue(Double.isFinite(entry.value()) && entry.value() > 0, "Every book quote must be finite and positive");
      assertOnlyEnchantment(helper, BookTargets.create(helper.getLevel(), entry.key()), entry.key());
      assertNear(helper, entry.value(), BookTargets.value(helper.getLevel(), entry.key()),
          "The catalogue price must equal the authoritative server quote");
    }
    System.out.println("IC2_UPGRADER_BOOK_VARIANTS " + catalog.size());
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void everyHigherLevelCostsMoreIncludingThornsAndProtection(GameTestHelper helper) {
    for (var holder : helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements().toList()) {
      double previous = 0;
      for (int level = Math.max(1, holder.value().getMinLevel()); level <= holder.value().getMaxLevel(); level++) {
        BookTargets.Key key = new BookTargets.Key(holder.key().identifier().toString(), level);
        if (!BookTargets.isLegal(helper.getLevel(), key)) continue;
        double current = BookTargets.value(helper.getLevel(), key);
        helper.assertTrue(Double.isFinite(current) && current > previous,
            "Higher levels must be strictly more expensive: " + key + ", previous=" + previous + ", current=" + current);
        previous = current;
      }
    }
    double thorns1 = BookTargets.value(helper.getLevel(), key("thorns", 1));
    double thorns3 = BookTargets.value(helper.getLevel(), key("thorns", 3));
    double protection1 = BookTargets.value(helper.getLevel(), key("protection", 1));
    double protection4 = BookTargets.value(helper.getLevel(), key("protection", 4));
    helper.assertTrue(thorns3 > thorns1 && protection4 > protection1,
        "Thorns III and Protection IV cannot inherit the generic empty book's price");
    System.out.println("IC2_UPGRADER_BOOK_PRICES thornsI=" + thorns1 + ", thornsIII=" + thorns3
        + ", protectionI=" + protection1 + ", protectionIV=" + protection4);
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void forgedOrStaleSelectionsCannotChangeAnAuthorizedTarget(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Fixture fixture = new Fixture(helper)) {
      deterministicChance(1);
      UpgraderMenu menu = fixture.menu;
      ItemStack input = new ItemStack(Items.DIAMOND, 3);
      menu.getSlot(0).set(input);
      BookTargets.Key selected = key("protection", 4);
      helper.assertTrue(BookTargets.select(fixture.player, menu.containerId, selected), "A legal initial choice must be accepted");
      List<BookTargets.Key> invalid = List.of(key("protection", 0), key("protection", -1), key("protection", 5),
          key("thorns", 4), key("missing_enchantment", 1), new BookTargets.Key("not an identifier", 1),
          new BookTargets.Key("minecraft:" + "x".repeat(257), 1));
      for (BookTargets.Key candidate : invalid) {
        helper.assertTrue(!BookTargets.isLegal(helper.getLevel(), candidate)
                && BookTargets.create(helper.getLevel(), candidate).isEmpty()
                && BookTargets.value(helper.getLevel(), candidate) == 0
                && !BookTargets.select(fixture.player, menu.containerId, candidate),
            "A client cannot select unknown or illegal enchantments/levels: " + candidate);
        helper.assertTrue(selected.equals(bookTarget(menu)) && !menu.isSpinning() && same(input, menu.getInputStack()),
            "A malformed selection cannot replace a valid target or consume an investment");
      }
      helper.assertTrue(!BookTargets.select(fixture.player, menu.containerId + 1, key("thorns", 3)),
          "A packet for another container must be rejected");
      fixture.player.containerMenu = fixture.player.inventoryMenu;
      helper.assertTrue(!BookTargets.select(fixture.player, menu.containerId, key("thorns", 3)),
          "A stale selection after closing the Upgrader cannot modify its old menu");
      fixture.player.containerMenu = menu;
      helper.assertTrue(selected.equals(bookTarget(menu)) && same(input, menu.getInputStack()),
          "Rejected container packets must preserve the original target and item components");
      // An old or forged item-only Upgrader packet carries no enchantment key or level.
      menu.setTarget(fixture.player, Items.ENCHANTED_BOOK);
      helper.assertTrue(bookTarget(menu) == null, "An item-only selection cannot inherit the previous enchantment key");
      menu.startUpgrade(fixture.player);
      helper.assertTrue(!menu.isSpinning() && same(input, menu.getInputStack()),
          "An unbound generic enchanted-book target must be rejected without consuming input or creating an empty prize");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void nativeInputValuationReadsEveryStoredEnchantmentWithoutMutatingBooks(GameTestHelper helper) {
    BookTargets.Key thorns = key("thorns", 3);
    BookTargets.Key mending = key("mending", 1);
    ItemStack one = BookTargets.create(helper.getLevel(), thorns);
    assertNear(helper, ItemValues.stackValue(helper.getLevel(), one), BookTargets.value(helper.getLevel(), thorns),
        "A real enchanted investment must use its exact level's price");
    ItemEnchantments.Mutable combined = new ItemEnchantments.Mutable(
        one.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
    combined.set(enchantment(helper, "mending"), 1);
    ItemStack multi = new ItemStack(Items.ENCHANTED_BOOK, 2);
    multi.set(DataComponents.STORED_ENCHANTMENTS, combined.toImmutable());
    multi.set(DataComponents.CUSTOM_NAME, Component.literal("two combined investment books"));
    StackUtil.updateNbtData(multi, tag -> tag.putString("book_fixture", "preserve this component"));
    ItemStack before = multi.copy();
    assertNear(helper, ItemValues.stackValue(helper.getLevel(), multi),
        2 * (BookTargets.value(helper.getLevel(), thorns) + BookTargets.value(helper.getLevel(), mending)),
        "Every stored enchantment contributes to the actual investment and count applies exactly once");
    helper.assertTrue(same(before, multi), "Pricing must not normalize, rename or consume an enchanted input");
    ItemStack impostor = new ItemStack(Items.BOOK);
    impostor.set(DataComponents.STORED_ENCHANTMENTS, combined.toImmutable());
    assertNear(helper, ItemValues.stackValue(helper.getLevel(), impostor), ItemValues.unitValue(helper.getLevel(), Items.BOOK),
        "Arbitrary stored components on an ordinary book cannot forge an enchanted-book material quote");
    assertNear(helper, ItemValues.stackValue(helper.getLevel(), new ItemStack(Items.ENCHANTED_BOOK)), 0,
        "A generic enchanted-book shell with no stored enchantments cannot fund an upgrade");
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void acceptedChanceUsesTheSelectedLevelPriceAndExactEnchantedInvestment(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Fixture fixture = new Fixture(helper)) {
      deterministicChance(0);
      UpgraderConfig.houseEdge = 0.9;
      UpgraderConfig.minChance = 0;
      UpgraderConfig.maxChance = 1;
      ItemStack input = BookTargets.create(helper.getLevel(), key("thorns", 1));
      fixture.menu.getSlot(0).set(input);
      BookTargets.Key target = key("thorns", 3);
      helper.assertTrue(BookTargets.select(fixture.player, fixture.menu.containerId, target), "The book choice must be accepted");
      fixture.menu.broadcastChanges();
      double inputValue = ItemValues.stackValue(helper.getLevel(), input);
      double targetValue = BookTargets.value(helper.getLevel(), target);
      helper.assertTrue(((Number) field(fixture.menu, "lastSyncedTargetValue")).longValue() == Math.round(targetValue)
              && ((Number) field(fixture.menu, "lastSyncedInputValue")).longValue() == Math.round(inputValue),
          "The native UI synchronization must send exact variant and investment quotes rather than empty-book rarity");
      fixture.menu.startUpgrade(fixture.player);
      helper.assertTrue(fixture.menu.isSpinning(), "The exact-valued book attempt must begin normally");
      double actual = ((Number) field(fixture.menu, "ic2Upgrader$committedChance")).doubleValue();
      assertNear(helper, actual, 0.9 * inputValue / targetValue,
          "The accepted RNG chance must use the chosen enchantment's cost and actual input components");
      helper.assertTrue(actual < UpgradeOdds.chance(inputValue, ItemValues.unitValue(helper.getLevel(), Items.ENCHANTED_BOOK)),
          "A high-level book must have a lower chance than an otherwise identical generic-book quote");
      // Discard only the test's pending spin so cleanup does not randomly create a result.
      fixture.menu.getSlot(0).set(ItemStack.EMPTY);
      finish(fixture.menu);
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void realWinCreatesExactEnchantmentAndAnnouncesTheAcceptedSnapshotOnce(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Capture messages = new Capture(); Fixture fixture = new Fixture(helper)) {
      deterministicChance(1);
      ItemStack input = ElectricItemManager.getCharged(Ic2Items.IRIDIUM_DRILL, 123_456);
      input.set(DataComponents.CUSTOM_NAME, Component.literal("investment drill before spin"));
      StackUtil.updateNbtData(input, tag -> tag.putString("book_fixture", "must not enter prize"));
      ItemStack before = input.copy();
      fixture.menu.getSlot(0).set(input);
      BookTargets.Key target = key("thorns", 3);
      helper.assertTrue(BookTargets.select(fixture.player, fixture.menu.containerId, target), "Thorns III must be selectable");
      fixture.menu.setCount(fixture.player, 64);
      fixture.menu.startUpgrade(fixture.player);
      helper.assertTrue(fixture.menu.isSpinning() && messages.values.isEmpty(), "A pending book must not be announced early");
      helper.assertTrue(!BookTargets.select(fixture.player, fixture.menu.containerId, key("protection", 1)),
          "A late selection must not replace a spinning book target");
      fixture.menu.setTarget(fixture.player, Ic2Items.NANO_HELMET);
      input.set(DataComponents.CUSTOM_NAME, Component.literal("mutated after spin acceptance"));
      UpgraderConfig.minChance = 0;
      UpgraderConfig.maxChance = 0;
      finish(fixture.menu);
      assertOnlyEnchantment(helper, fixture.menu.getInputStack(), target);
      helper.assertTrue(fixture.menu.getInputStack().get(DataComponents.CUSTOM_NAME) == null
              && StackUtil.getTag(fixture.menu.getInputStack()) == null,
          "The native win must create a fresh enchanted book without input names, custom NBT or electric charge");
      helper.assertTrue(messages.values.size() == 1 && messages.values.getFirst().getString().contains("100"),
          "Exactly one announcement must retain the probability accepted before the configuration changed");
      assertHoverContains(helper, messages.values.getFirst(), before, "The investment hover retains the accepted input snapshot");
      assertHoverContains(helper, messages.values.getFirst(), BookTargets.create(helper.getLevel(), target),
          "The global chat prize hover contains the selected enchantment and exact level");
      helper.assertTrue(fixture.player.getInventory().isEmpty(), "The investment must not also be returned as an inventory item");
      finish(fixture.menu);
      helper.assertTrue(messages.values.size() == 1, "Later ticks must not repeat a book result or its announcement");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void losingBookAttemptConsumesOnceAndStaysSilentWhenClosed(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Capture messages = new Capture(); Fixture fixture = new Fixture(helper)) {
      deterministicChance(0);
      fixture.menu.getSlot(0).set(new ItemStack(Items.DIAMOND, 7));
      helper.assertTrue(BookTargets.select(fixture.player, fixture.menu.containerId, key("protection", 4)), "A valid losing target is selectable");
      fixture.menu.startUpgrade(fixture.player);
      finish(fixture.menu);
      finish(fixture.menu);
      fixture.menu.removed(fixture.player);
      helper.assertTrue(fixture.menu.getInputStack().isEmpty() && fixture.player.getInventory().isEmpty()
              && messages.values.isEmpty(),
          "A losing enchantment upgrade consumes the input once, creates no book, refunds nothing and broadcasts no win");
      helper.assertTrue(bookTarget(fixture.menu) == null, "Closing must clear per-menu enchantment state");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void switchingBackToOrdinaryIc2TargetClearsTheVariantAndKeepsGearUncharged(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Fixture fixture = new Fixture(helper)) {
      deterministicChance(1);
      fixture.menu.getSlot(0).set(BookTargets.create(helper.getLevel(), key("protection", 4)));
      helper.assertTrue(BookTargets.select(fixture.player, fixture.menu.containerId, key("thorns", 3)), "Book selection must work first");
      fixture.menu.setTarget(fixture.player, Ic2Items.NANO_HELMET);
      helper.assertTrue(bookTarget(fixture.menu) == null && fixture.menu.getTarget() == Ic2Items.NANO_HELMET,
          "Selecting an ordinary item must discard the prior book variant");
      fixture.menu.startUpgrade(fixture.player);
      finish(fixture.menu);
      ItemStack prize = fixture.menu.getInputStack();
      helper.assertTrue(prize.is(Ic2Items.NANO_HELMET) && prize.getCount() == 1
              && ElectricItem.manager.getCharge(prize) == 0
              && prize.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty(),
          "The original IC2 win still produces one fresh uncharged helmet without stale book enchantments");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void configurationOverridesMultipliersAndBlacklistControlBookVariants(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Fixture fixture = new Fixture(helper)) {
      BookTargets.Key target = key("protection", 4);
      UpgraderConfig.valueOverrides = Map.of("minecraft:enchanted_book", 100.0);
      UpgraderConfig.globalValueMultiplier = 1;
      UpgraderConfig.valueMultipliers = Map.of();
      ItemValues.invalidate();
      double baseline = BookTargets.value(helper.getLevel(), target);
      UpgraderConfig.globalValueMultiplier = 2;
      UpgraderConfig.valueMultipliers = Map.of("minecraft:enchanted_book", 3.0);
      ItemValues.invalidate();
      assertNear(helper, BookTargets.value(helper.getLevel(), target), baseline * 6,
          "The configured book override and both original multipliers must govern variants exactly once");
      assertNear(helper, ItemValues.stackValue(helper.getLevel(), BookTargets.create(helper.getLevel(), target)), baseline * 6,
          "Investment and target valuation must use the same configuration authority");
      UpgraderConfig.blacklist = Set.of("minecraft:enchanted_book");
      ItemValues.invalidate();
      fixture.menu.getSlot(0).set(new ItemStack(Items.DIAMOND, 2));
      helper.assertTrue(BookTargets.catalog(helper.getLevel()).isEmpty()
              && BookTargets.value(helper.getLevel(), target) == 0
              && !BookTargets.select(fixture.player, fixture.menu.containerId, target),
          "Blacklisting enchanted books must hide and reject every book variant on the server");
      UpgraderConfig.blacklist = Set.of();
      UpgraderConfig.valueOverrides = Map.of("minecraft:enchanted_book", 0.0);
      ItemValues.invalidate();
      helper.assertTrue(BookTargets.catalog(helper.getLevel()).isEmpty()
              && !BookTargets.select(fixture.player, fixture.menu.containerId, target),
          "An intentional zero-value override cannot be revived into a free enchantment target");
    }
    helper.succeed();
  }

  private static BookTargets.Key key(String enchantment, int level) {
    return new BookTargets.Key("minecraft:" + enchantment, level);
  }

  private static Holder<Enchantment> enchantment(GameTestHelper helper, String id) {
    return helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
        .get(Identifier.fromNamespaceAndPath("minecraft", id)).orElseThrow();
  }

  private static BookTargets.Key bookTarget(UpgraderMenu menu) {
    return ((BookTargetMenu) menu).ic2upgrader$getBookTarget();
  }

  private static void assertOnlyEnchantment(GameTestHelper helper, ItemStack stack, BookTargets.Key expected) {
    ItemEnchantments stored = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
    helper.assertTrue(stack.is(Items.ENCHANTED_BOOK) && stack.getCount() == 1 && stored.size() == 1
            && stored.entrySet().stream().anyMatch(entry -> entry.getKey().unwrapKey().orElseThrow().identifier().toString()
                .equals(expected.enchantmentId()) && entry.getIntValue() == expected.level()),
        "The real stack must contain exactly the selected native enchantment and level: " + expected + ", got " + stack);
  }

  private static boolean same(ItemStack first, ItemStack second) {
    return first.getCount() == second.getCount() && ItemStack.isSameItemSameComponents(first, second);
  }

  private static void assertHoverContains(GameTestHelper helper, Component component, ItemStack expected, String reason) {
    List<Style> styles = new ArrayList<>();
    component.visit((style, text) -> { styles.add(style); return Optional.empty(); }, Style.EMPTY);
    helper.assertTrue(styles.stream().map(Style::getHoverEvent).filter(event -> event instanceof HoverEvent.ShowItem)
            .map(event -> ((HoverEvent.ShowItem) event).item().create()).anyMatch(stack -> same(stack, expected)), reason);
  }

  private static void assertNear(GameTestHelper helper, double actual, double expected, String reason) {
    helper.assertTrue(Double.isFinite(actual) && Math.abs(actual - expected) <= Math.max(1e-9, Math.abs(expected) * 1e-9),
        reason + ": expected " + expected + ", got " + actual);
  }

  private static void deterministicChance(double chance) {
    UpgraderConfig.minChance = chance;
    UpgraderConfig.maxChance = chance;
    UpgraderConfig.spinTicks = 2;
    UpgraderConfig.maxTargetCount = 64;
    UpgraderConfig.attemptLimit = 0;
  }

  private static void finish(UpgraderMenu menu) {
    for (int tick = 0; tick < 4; tick++) menu.broadcastChanges();
  }

  private static Object field(Object owner, String name) {
    try {
      Field field = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name);
      field.setAccessible(true);
      return field.get(owner instanceof Class<?> ? null : owner);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("Cannot inspect actual native state " + name, exception);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final ServerPlayer player;
    final UpgraderMenu menu;
    Fixture(GameTestHelper helper) {
      player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(GameType.SURVIVAL);
      player.getInventory().clearContent();
      menu = new UpgraderMenu(1, player.getInventory());
      player.containerMenu = menu;
    }
    @Override public void close() {
      menu.getSlot(0).set(ItemStack.EMPTY);
      menu.removed(player);
      player.getInventory().clearContent();
      player.containerMenu = player.inventoryMenu;
      AttemptLimiter.forget(player.getUUID());
    }
  }

  private static final class Capture implements AutoCloseable {
    final List<Component> values = new ArrayList<>();
    Capture() {
      if (CAPTURE.get() != null) throw new IllegalStateException("Nested native chat capture");
      CAPTURE.set(values);
    }
    @Override public void close() { CAPTURE.remove(); }
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
    private static void restore(Class<?> owner, String name, Object value) {
      try {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
      } catch (ReflectiveOperationException exception) {
        throw new IllegalStateException("Cannot restore pre-test configuration " + name, exception);
      }
    }
  }
}
