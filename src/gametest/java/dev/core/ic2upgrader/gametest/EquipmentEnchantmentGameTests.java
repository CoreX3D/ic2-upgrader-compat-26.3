package dev.core.ic2upgrader.gametest;

import dev.core.ic2upgrader.BookTargets;
import dev.core.ic2upgrader.Ic2Pricing;
import ic2.api.item.ElectricItem;
import ic2.core.item.ElectricItemManager;
import ic2.core.ref.Ic2Items;
import ic2.core.util.StackUtil;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.execheinz.upgrader.AttemptLimiter;
import net.execheinz.upgrader.UpgraderConfig;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.execheinz.upgrader.value.ItemValues;
import net.execheinz.upgrader.value.UpgradeOdds;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;

/** Regressions through the actual patched ItemValues and original server Upgrader menu. */
public final class EquipmentEnchantmentGameTests {
  private static final String EMPTY = "ic2_upgrader_compat:gametest/empty3x3x3";

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void screenshotHelmetAddsAllFourExactLevelBookPricesAndPreservesComponents(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture()) {
      configure();
      ItemStack helmet = screenshotHelmet(helper);
      helmet.set(DataComponents.CUSTOM_NAME, Component.literal("The player's renamed helmet"));
      StackUtil.updateNbtData(helmet, tag -> tag.putString("enchantment_fixture", "must survive valuation"));
      double material = ItemValues.unitValue(helper.getLevel(), Items.NETHERITE_HELMET);
      double bonus = price(helper, "protection", 4) + price(helper, "thorns", 3)
          + price(helper, "unbreaking", 3) + price(helper, "mending", 1);
      assertNear(helper, quote(helper, helmet), material + bonus,
          "Protection IV, Thorns III, Unbreaking III and Mending I must all contribute their existing book prices");
      ItemStack renamed = helmet.copy();
      renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Another name costs no upgrade currency"));
      assertNear(helper, quote(helper, renamed), material + bonus,
          "Renaming the same enchanted helmet cannot create additional value");
      System.out.println("IC2_UPGRADER_EQUIPMENT_HELMET material=" + material + ", enchantments=" + bonus
          + ", total=" + quote(helper, helmet));
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void realToolEnchantmentsAreAdditiveLevelSensitiveAndCountedExactlyOnce(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture()) {
      configure();
      ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
      double base = quote(helper, pickaxe);
      enchant(helper, pickaxe, "efficiency", 1);
      double low = quote(helper, pickaxe);
      assertNear(helper, low, base + price(helper, "efficiency", 1), "Efficiency I must add its level-one book quote");
      enchant(helper, pickaxe, "efficiency", 5);
      enchant(helper, pickaxe, "fortune", 3);
      double high = quote(helper, pickaxe);
      assertNear(helper, high, base + price(helper, "efficiency", 5) + price(helper, "fortune", 3),
          "Efficiency V and Fortune III must add independently instead of retaining the first enchantment only");
      helper.assertTrue(high > low, "Higher levels and an additional enchantment must raise the input valuation");
      // ItemValues must respect the actual incoming stack count, even for component-bearing stacks.
      pickaxe.setCount(3);
      assertNear(helper, quote(helper, pickaxe), high * 3, "The entire enchanted quote must scale with stack count once");
      pickaxe.setCount(1);
      enchant(helper, pickaxe, "efficiency", 6);
      assertNear(helper, quote(helper, pickaxe), base + price(helper, "fortune", 3),
          "An out-of-range Efficiency VI component cannot mint a legal level's value");
      enchant(helper, pickaxe, "vanishing_curse", 1);
      assertNear(helper, quote(helper, pickaxe), base + price(helper, "fortune", 3) + price(helper, "vanishing_curse", 1),
          "A registered legal curse keeps the same valuation policy as its existing book variant");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void permanentDamageDepreciatesBothMaterialAndEnchantmentsIncludingNativeFivePercentFloor(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture()) {
      configure();
      ItemStack helmet = screenshotHelmet(helper);
      double fresh = quote(helper, helmet);
      helper.assertTrue(helmet.isDamageableItem() && helmet.getMaxDamage() > 20,
          "This regression must exercise genuine vanilla durability");
      helmet.setDamageValue(helmet.getMaxDamage() / 2);
      double remaining = 1.0 - (double) helmet.getDamageValue() / helmet.getMaxDamage();
      assertNear(helper, quote(helper, helmet), fresh * remaining,
          "A worn enchanted helmet cannot retain the full value of enchantments on a new helmet");
      helmet.setDamageValue(helmet.getMaxDamage() - 1);
      assertNear(helper, quote(helper, helmet), fresh * 0.05,
          "A nearly broken investment must preserve the original Upgrader five-percent floor for its whole quote");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void electricChargeStaysNeutralWhileIc2EquipmentEnchantmentsIncreaseValue(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture()) {
      configure();
      for (Item electric : List.of(Ic2Items.NANO_HELMET, Ic2Items.IRIDIUM_DRILL)) {
        double material = ItemValues.unitValue(helper.getLevel(), electric);
        String enchantment = electric == Ic2Items.NANO_HELMET ? "protection" : "efficiency";
        int enchantmentLevel = electric == Ic2Items.NANO_HELMET ? 4 : 5;
        double expected = material + price(helper, enchantment, enchantmentLevel) + price(helper, "unbreaking", 3);
        for (double charge : List.of(0.0, 50_000.0, Double.POSITIVE_INFINITY)) {
          ItemStack equipment = ElectricItemManager.getCharged(electric, charge);
          enchant(helper, equipment, enchantment, enchantmentLevel);
          enchant(helper, equipment, "unbreaking", 3);
          double before = ElectricItem.manager.getCharge(equipment);
          assertNear(helper, quote(helper, equipment), expected,
              "EU is rechargeable and must neither depreciate nor multiply the value of real IC2 enchantments");
          assertNear(helper, ElectricItem.manager.getCharge(equipment), before,
              "Reading an enchanted IC2 quote cannot charge or discharge its investment");
        }
      }
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void unenchantedItemsNamesAndStoredComponentsOnOrdinaryGearKeepTheirOriginalValue(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture()) {
      configure();
      for (Item item : List.of(Items.NETHERITE_HELMET, Items.DIAMOND_PICKAXE, Ic2Items.NANO_HELMET, Items.DIAMOND)) {
        ItemStack ordinary = new ItemStack(item);
        double baseline = quote(helper, ordinary);
        assertNear(helper, baseline, ItemValues.unitValue(helper.getLevel(), item), "Plain material prices must be unchanged");
        ordinary.set(DataComponents.CUSTOM_NAME, Component.literal("Protection IV is only a name"));
        StackUtil.updateNbtData(ordinary, tag -> tag.putInt("enchantment_fixture", 999));
        ItemEnchantments.Mutable forged = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        forged.set(enchantment(helper, "protection"), 4);
        ordinary.set(DataComponents.STORED_ENCHANTMENTS, forged.toImmutable());
        assertNear(helper, quote(helper, ordinary), baseline,
            "Names, arbitrary NBT and stored-book components on ordinary material cannot forge active enchantments");
      }
      ItemStack worn = new ItemStack(Items.NETHERITE_HELMET);
      worn.setDamageValue(worn.getMaxDamage() / 2);
      assertNear(helper, quote(helper, worn), ItemValues.unitValue(helper.getLevel(), Items.NETHERITE_HELMET)
              * (1.0 - (double) worn.getDamageValue() / worn.getMaxDamage()),
          "The original unenchanted durability calculation must remain exact");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void existingStoredEnchantedBookQuotesRemainSeparateWithoutDoubleCountingOrOrdinaryBookForgery(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture()) {
      configure();
      ItemStack book = BookTargets.create(helper.getLevel(), key("thorns", 3));
      ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(
          book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
      stored.set(enchantment(helper, "mending"), 1);
      book.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
      book.setCount(2);
      double expected = 2 * (price(helper, "thorns", 3) + price(helper, "mending", 1));
      assertNear(helper, quote(helper, book), expected, "Existing multiple stored enchantments must retain their book-only quote");
      enchant(helper, book, "protection", 4);
      assertNear(helper, quote(helper, book), expected,
          "An active component on an enchanted book cannot double-count its stored-book valuation");
      ItemStack ordinaryBook = new ItemStack(Items.BOOK);
      ordinaryBook.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
      enchant(helper, ordinaryBook, "protection", 4);
      assertNear(helper, quote(helper, ordinaryBook), ItemValues.unitValue(helper.getLevel(), Items.BOOK),
          "A plain book with forged active or stored components cannot impersonate an enchanted book or equipment");
      assertNear(helper, quote(helper, new ItemStack(Items.ENCHANTED_BOOK)), 0,
          "An empty enchanted-book shell still has no valid investment enchantment");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void nativeMenuSynchronizesEnchantedInputQuoteAndUsesItForTheAcceptedRandomChance(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Fixture fixture = new Fixture(helper)) {
      configure();
      ItemStack helmet = screenshotHelmet(helper);
      helmet.set(DataComponents.CUSTOM_NAME, Component.literal("server-valued investment"));
      ItemStack before = helmet.copy();
      double material = ItemValues.unitValue(helper.getLevel(), Items.NETHERITE_HELMET);
      double input = material + price(helper, "protection", 4) + price(helper, "thorns", 3)
          + price(helper, "unbreaking", 3) + price(helper, "mending", 1);
      double target = ItemValues.unitValue(helper.getLevel(), Items.DIAMOND);
      fixture.menu.getSlot(0).set(helmet);
      fixture.menu.setTarget(fixture.player, Items.DIAMOND);
      fixture.menu.setCount(fixture.player, 2);
      fixture.menu.broadcastChanges();
      helper.assertTrue(((Number) field(fixture.menu, "lastSyncedInputValue")).longValue() == Math.round(input)
              && ((Number) field(fixture.menu, "lastSyncedTargetValue")).longValue() == Math.round(target)
              && ((Number) field(fixture.menu, "lastSyncedCount")).intValue() == 2,
          "The original server menu must synchronize the enchanted input's full quote and real target count");
      fixture.menu.startUpgrade(fixture.player);
      helper.assertTrue(fixture.menu.isSpinning(), "A real enchanted equipment investment must start the native upgrade");
      double accepted = ((Number) field(fixture.menu, "ic2Upgrader$committedChance")).doubleValue();
      assertNear(helper, accepted, 0.9 * input / (target * 2),
          "The actual RNG threshold must use all enchantment prices, not only the visible material price");
      helper.assertTrue(accepted > UpgradeOdds.chance(material, target * 2),
          "The enchantments must improve the real accepted upgrade probability");
      helper.assertTrue(same(before, fixture.menu.getInputStack()),
          "Accepting an attempt must preserve the input's exact components until the result commits");
      System.out.println("IC2_UPGRADER_EQUIPMENT_NATIVE_MENU input=" + input + ", targetTotal=" + target * 2
          + ", acceptedChance=" + accepted);
      fixture.menu.getSlot(0).set(ItemStack.EMPTY);
      for (int i = 0; i < 4; i++) fixture.menu.broadcastChanges();
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void zeroValuesAndExplicitBlacklistsCannotBeRevivedByEquipmentEnchantments(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture(); Fixture fixture = new Fixture(helper)) {
      configure();
      ItemStack helmet = screenshotHelmet(helper);
      double material = ItemValues.unitValue(helper.getLevel(), Items.NETHERITE_HELMET);
      UpgraderConfig.valueOverrides = Map.of("minecraft:netherite_helmet", 0.0, "minecraft:enchanted_book", 100.0);
      ItemValues.invalidate();
      assertNear(helper, quote(helper, helmet), 0, "A deliberate zero material override cannot be revived by enchantments");
      UpgraderConfig.valueOverrides = Map.of("minecraft:netherite_helmet", material, "minecraft:enchanted_book", 0.0);
      ItemValues.invalidate();
      assertNear(helper, quote(helper, helmet), material, "A zero book value must suppress its equivalent equipment bonuses");
      UpgraderConfig.valueOverrides = Map.of("minecraft:netherite_helmet", material, "minecraft:enchanted_book", 100.0);
      UpgraderConfig.blacklist = Set.of("minecraft:enchanted_book");
      ItemValues.invalidate();
      assertNear(helper, quote(helper, helmet), material,
          "An administrator's enchanted-book blacklist must suppress corresponding equipment bonuses");
      UpgraderConfig.blacklist = Set.of("minecraft:netherite_helmet");
      ItemValues.invalidate();
      assertNear(helper, quote(helper, helmet), material, "A blacklisted material cannot acquire an enchantment bonus");
      fixture.menu.getSlot(0).set(helmet);
      fixture.menu.setTarget(fixture.player, Items.DIAMOND);
      fixture.menu.startUpgrade(fixture.player);
      helper.assertTrue(!fixture.menu.isSpinning() && same(helmet, fixture.menu.getInputStack()),
          "The actual native menu must still reject a blacklisted enchanted investment without consuming it");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void configuredBookAndEquipmentMultipliersApplyToTheirOwnValuesOnce(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture()) {
      configure();
      double book = price(helper, "protection", 4);
      UpgraderConfig.globalValueMultiplier = 2;
      UpgraderConfig.valueMultipliers = Map.of("minecraft:netherite_helmet", 4.0, "minecraft:enchanted_book", 3.0);
      ItemValues.invalidate();
      ItemStack helmet = new ItemStack(Items.NETHERITE_HELMET);
      enchant(helper, helmet, "protection", 4);
      assertNear(helper, quote(helper, helmet), 1_000 * 2 * 4 + book * 2 * 3,
          "Equipment and existing book overrides must retain their own multipliers without applying either twice");
    }
    helper.succeed();
  }

  private static void configure() {
    UpgraderConfig.houseEdge = 0.9;
    UpgraderConfig.minChance = 0;
    UpgraderConfig.maxChance = 1;
    UpgraderConfig.spinTicks = 2;
    UpgraderConfig.maxTargetCount = 64;
    UpgraderConfig.attemptLimit = 0;
    UpgraderConfig.valueOverrides = Map.of("minecraft:netherite_helmet", 1_000.0,
        "minecraft:diamond_pickaxe", 1_000.0, "ic2:nano_helmet", 2_000.0,
        "ic2:iridium_drill", 5_000.0, "minecraft:enchanted_book", 100.0, "minecraft:diamond", 100_000.0);
    UpgraderConfig.blacklist = Set.of();
    UpgraderConfig.globalValueMultiplier = 1;
    UpgraderConfig.valueMultipliers = Map.of();
    ItemValues.invalidate();
  }

  private static ItemStack screenshotHelmet(GameTestHelper helper) {
    ItemStack helmet = new ItemStack(Items.NETHERITE_HELMET);
    enchant(helper, helmet, "protection", 4);
    enchant(helper, helmet, "thorns", 3);
    enchant(helper, helmet, "unbreaking", 3);
    enchant(helper, helmet, "mending", 1);
    return helmet;
  }

  private static void enchant(GameTestHelper helper, ItemStack stack, String id, int level) {
    ItemEnchantments.Mutable active = new ItemEnchantments.Mutable(
        stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
    active.set(enchantment(helper, id), level);
    stack.set(DataComponents.ENCHANTMENTS, active.toImmutable());
  }

  private static Holder<Enchantment> enchantment(GameTestHelper helper, String id) {
    return helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
        .get(Identifier.fromNamespaceAndPath("minecraft", id)).orElseThrow();
  }

  private static BookTargets.Key key(String id, int level) { return new BookTargets.Key("minecraft:" + id, level); }
  private static double price(GameTestHelper helper, String id, int level) {
    return BookTargets.value(helper.getLevel(), key(id, level));
  }

  private static double quote(GameTestHelper helper, ItemStack stack) {
    ItemStack before = stack.copy();
    double result = ItemValues.stackValue(helper.getLevel(), stack);
    helper.assertTrue(same(before, stack), "Reading the actual ItemValues quote must not mutate any item component or count");
    return result;
  }

  private static boolean same(ItemStack first, ItemStack second) {
    return first.getCount() == second.getCount() && ItemStack.isSameItemSameComponents(first, second);
  }

  private static void assertNear(GameTestHelper helper, double actual, double expected, String reason) {
    helper.assertTrue(Double.isFinite(actual) && Math.abs(actual - expected) <= Math.max(1e-9, Math.abs(expected) * 1e-9),
        reason + ": expected " + expected + ", got " + actual);
  }

  private static Object field(Object owner, String name) {
    try {
      Field field = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name);
      field.setAccessible(true);
      return field.get(owner instanceof Class<?> ? null : owner);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("Cannot inspect native upgrade state " + name, exception);
    }
  }

  private static void restore(Class<?> owner, String name, Object value) {
    try {
      Field field = owner.getDeclaredField(name);
      field.setAccessible(true);
      field.set(null, value);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("Cannot restore native configuration " + name, exception);
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

  private record ConfigSnapshot(double houseEdge, double minChance, double maxChance, int spinTicks,
      Map<String, Double> overrides, Set<String> blacklist, int maxTargetCount, int attemptLimit,
      int attemptWindowSeconds, double globalMultiplier, Map<String, Double> multipliers,
      Object cachedValues, Object pricingDiagnostics, Object pricingProvenance, long bookRevision) implements AutoCloseable {
    static ConfigSnapshot capture() {
      return new ConfigSnapshot(UpgraderConfig.houseEdge, UpgraderConfig.minChance, UpgraderConfig.maxChance,
          UpgraderConfig.spinTicks, UpgraderConfig.valueOverrides, UpgraderConfig.blacklist,
          UpgraderConfig.maxTargetCount, UpgraderConfig.attemptLimit, UpgraderConfig.attemptWindowSeconds,
          UpgraderConfig.globalValueMultiplier, UpgraderConfig.valueMultipliers,
          field(ItemValues.class, "values"), field(Ic2Pricing.class, "last"), field(Ic2Pricing.class, "lastProvenance"),
          BookTargets.catalogRevision());
    }
    @Override public void close() {
      UpgraderConfig.apply(houseEdge, minChance, maxChance, spinTicks, overrides, blacklist,
          maxTargetCount, attemptLimit, attemptWindowSeconds, globalMultiplier, multipliers);
      restore(ItemValues.class, "values", cachedValues);
      restore(Ic2Pricing.class, "last", pricingDiagnostics);
      restore(Ic2Pricing.class, "lastProvenance", pricingProvenance);
      restore(BookTargets.class, "catalogRevision", bookRevision);
    }
  }
}
