package dev.core.ic2upgrader.gametest;

import dev.core.ic2upgrader.Ic2Pricing;
import ic2.api.item.ElectricItem;
import ic2.core.item.ElectricItemManager;
import ic2.core.item.reactor.AbstractDamageableReactorComponent;
import ic2.core.ref.Ic2Items;
import ic2.core.util.StackUtil;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.execheinz.upgrader.UpgraderConfig;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.execheinz.upgrader.value.ItemValues;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/** Exercises the installed Upgrader API and its real server menu with IC2 recipes and stacks. */
public final class UpgraderCompatGameTests {
  private static final String EMPTY = "ic2_upgrader_compat:gametest/empty3x3x3";
  private static final List<String> UNSUPPORTED = List.of(
      "creative_generator", "debug_item", "crop_seed_bag");

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void reportLoadedIc2PricesAndRecipeProvenance(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture()) {
      ItemValues.invalidate();
      value(helper, Ic2Items.NANO_HELMET);
      System.out.println("IC2_UPGRADER_DIAGNOSTICS " + Ic2Pricing.diagnosticsSummary());
      System.out.println("IC2_UPGRADER_SKIPPED_RECIPES " + Ic2Pricing.diagnostics().skippedRecipes());
      for (Item candidate : BuiltInRegistries.ITEM.stream()
          .filter(item -> BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("ic2"))
          .sorted(java.util.Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString())).toList()) {
        String id = BuiltInRegistries.ITEM.getKey(candidate).toString();
        System.out.println("IC2_UPGRADER_PRICE " + id + " -> " + value(helper, candidate)
            + " -> " + Ic2Pricing.provenance().getOrDefault(id, "UNRESOLVED_FALLBACK")
            + " -> " + (ItemValues.isBlacklisted(candidate) ? "blocked" : "selectable"));
      }
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void machineIntermediatesRetainTheirMaterialCosts(GameTestHelper helper) {
    double iron = value(helper, Items.IRON_INGOT);
    double plate = value(helper, Ic2Items.IRON_PLATE);
    double casing = value(helper, Ic2Items.IRON_CASING);
    assertNear(helper, plate, iron, "rolling one iron ingot makes one plate");
    assertNear(helper, 2 * casing, plate, "rolling one plate makes two casings");

    double coal = value(helper, Items.COAL);
    double coalDust = value(helper, item("coal_dust"));
    double fibre = value(helper, item("carbon_fibre"));
    double mesh = value(helper, item("carbon_mesh"));
    double carbonPlate = value(helper, item("carbon_plate"));
    assertNear(helper, coalDust, coal, "macerating coal does not create free material");
    assertNear(helper, fibre, 4 * coalDust, "carbon fibre needs four coal dust");
    assertNear(helper, mesh, 2 * fibre, "carbon mesh needs two fibres");
    assertNear(helper, carbonPlate, mesh, "compressing carbon mesh preserves its material cost");
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void nanoQuantumAndIridiumDrillUseTheirActualProgressionCosts(GameTestHelper helper) {
    double nanoInputs = 4 * value(helper, item("carbon_plate"))
        + value(helper, item("energy_crystal")) + value(helper, item("night_vision_goggles"));
    double nano = value(helper, Ic2Items.NANO_HELMET);
    assertNear(helper, nano, nanoInputs, "nano helmet includes its four carbon plates and electronics");

    double iridiumInputs = 4 * value(helper, item("iridium_ore"))
        + 4 * value(helper, item("alloy")) + value(helper, Items.DIAMOND);
    assertNear(helper, value(helper, item("iridium")), iridiumInputs,
        "reinforced iridium plate includes four iridium ores, composite and diamond");
    double drillInputs = 3 * value(helper, item("iridium"))
        + value(helper, item("diamond_drill")) + value(helper, item("energy_crystal"));
    assertNear(helper, value(helper, Ic2Items.IRIDIUM_DRILL), drillInputs,
        "iridium drill includes three reinforced plates and the diamond drill");

    double quantumInputs = nano + 2 * value(helper, item("reinforced_glass"))
        + 2 * value(helper, item("iridium")) + value(helper, item("lapotron_crystal"))
        + 2 * value(helper, item("advanced_circuit")) + value(helper, item("hazmat_helmet"));
    double quantum = value(helper, Ic2Items.QUANTUM_HELMET);
    assertNear(helper, quantum, quantumInputs, "quantum helmet includes the complete progression chain");
    helper.assertTrue(quantum > nano && value(helper, Ic2Items.IRIDIUM_DRILL) > nano,
        "advanced equipment must not collapse to Upgrader's generic rarity fallback");
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void catalogContainsUsableIc2TargetsAndOmitsUnusableDefaults(GameTestHelper helper) {
    List<Item> catalog = ItemValues.catalog(helper.getLevel());
    for (String id : UNSUPPORTED) {
      Item unsupported = item(id);
      helper.assertTrue(ItemValues.isBlacklisted(unsupported), id + " must be unavailable");
      helper.assertTrue(!catalog.contains(unsupported), id + " must not be sent as a catalog target");
    }
    for (Item supported : List.of(Ic2Items.NANO_HELMET, Ic2Items.QUANTUM_HELMET,
        Ic2Items.IRIDIUM_DRILL, item("filled_tin_can"), item("uranium_fuel_rod"))) {
      helper.assertTrue(!ItemValues.isBlacklisted(supported) && catalog.contains(supported),
          BuiltInRegistries.ITEM.getKey(supported) + " must remain selectable");
    }
    int ic2Count = 0;
    for (Item candidate : catalog) {
      if (!BuiltInRegistries.ITEM.getKey(candidate).getNamespace().equals("ic2")) continue;
      double price = ItemValues.unitValue(helper.getLevel(), candidate);
      helper.assertTrue(Double.isFinite(price) && price > 0,
          "every selectable IC2 item needs a finite positive price: " + BuiltInRegistries.ITEM.getKey(candidate));
      helper.assertTrue(Ic2Pricing.hasResolvedPrice(candidate),
          "selectable IC2 items must have a material/recipe price instead of a rarity fallback: "
              + BuiltInRegistries.ITEM.getKey(candidate));
      ic2Count++;
    }
    helper.assertTrue(ic2Count > 500, "the complete IC2 catalog must remain available");
    Set<String> expectedUnresolved = UNSUPPORTED.stream().map(id -> "ic2:" + id)
        .collect(java.util.stream.Collectors.toSet());
    helper.assertTrue(Set.copyOf(Ic2Pricing.diagnostics().unresolved()).equals(expectedUnresolved),
        "only the three blocked IC2 defaults may remain unpriced: " + Ic2Pricing.diagnostics().unresolved());
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void runtimeAndLegacyProductsHaveExplicitMaterialPolicies(GameTestHelper helper) {
    double foam = value(helper, item("foam"));
    assertNear(helper, foam * 10, value(helper, item("cf_powder")) + value(helper, Items.OAK_PLANKS),
        "sprayer output prices ten foam blocks from one powder and the water budget");
    assertNear(helper, value(helper, item("red_wall")), foam,
        "hardening and coloring preserve the foam material quote");
    assertNear(helper, value(helper, item("advanced_ejector_upgrade")),
        value(helper, item("ejector_upgrade")) + value(helper, item("advanced_circuit")),
        "legacy advanced ejector includes its base module and advanced filter electronics");
    assertNear(helper, value(helper, item("remote_interface_upgrade")),
        value(helper, item("frequency_transmitter")) + value(helper, item("advanced_circuit")),
        "legacy remote interface includes transmitter and circuit");
    assertNear(helper, value(helper, item("tritium_fuel_rod")), value(helper, item("lithium_fuel_rod")),
        "reactor breeding adds processing rather than new item material");
    helper.assertTrue(value(helper, item("water_cell")) >= value(helper, item("empty_cell")),
        "a filled fluid cell retains its shell; the native bucket recipe may supply water for free");
    helper.assertTrue(value(helper, item("depleted_uranium_fuel_rod")) > 0
            && Ic2Pricing.provenance().get("ic2:depleted_uranium_fuel_rod").startsWith("salvage:"),
        "spent reactor fuel retains its actual deterministic centrifuge salvage value");
    helper.assertTrue(Ic2Pricing.provenance().get("ic2:advanced_ejector_upgrade").startsWith("policy:"),
        "recipe-less legacy targets need an explicit policy rather than a rarity fallback");
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void userOverridesAndValueMultipliersRemainAuthoritative(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture()) {
      Map<String, Double> overrides = new HashMap<>(UpgraderConfig.valueOverrides);
      overrides.put("ic2:nano_helmet", 12_345.0);
      overrides.put("minecraft:diamond", 333.0);
      UpgraderConfig.valueOverrides = Map.copyOf(overrides);
      UpgraderConfig.globalValueMultiplier = 2;
      UpgraderConfig.valueMultipliers = Map.of("ic2:nano_helmet", 3.0);
      ItemValues.invalidate();
      assertNear(helper, value(helper, Ic2Items.NANO_HELMET), 12_345 * 2 * 3,
          "explicit IC2 override precedes the compatibility price and both multipliers apply once");
      assertNear(helper, value(helper, Items.DIAMOND), 333 * 2,
          "vanilla override remains authoritative");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void electricChargeDoesNotChangeMaterialValueOrMutateTheInput(GameTestHelper helper) {
    for (Item electric : List.of(Ic2Items.NANO_HELMET, Ic2Items.QUANTUM_HELMET,
        Ic2Items.IRIDIUM_DRILL, item("re_battery"), item("energy_crystal"))) {
      ItemStack empty = ElectricItemManager.getCharged(electric, 0);
      ItemStack half = ElectricItemManager.getCharged(electric, 50_000);
      ItemStack full = ElectricItemManager.getCharged(electric, Double.POSITIVE_INFINITY);
      double expected = value(helper, electric);
      for (ItemStack stack : List.of(empty, half, full)) {
        ItemStack before = stack.copy();
        assertNear(helper, ItemValues.stackValue(helper.getLevel(), stack), expected,
            "rechargeable EU is not consumed material: " + BuiltInRegistries.ITEM.getKey(electric));
        helper.assertTrue(ItemStack.isSameItemSameComponents(before, stack)
                && before.getCount() == stack.getCount(),
            "valuation must not charge, discharge, normalize or otherwise modify the input");
      }
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void fuelAndReflectorPricesTrackPermanentRemainingLife(GameTestHelper helper) {
    for (String id : List.of("uranium_fuel_rod", "dual_uranium_fuel_rod", "quad_uranium_fuel_rod",
        "mox_fuel_rod", "dual_mox_fuel_rod", "quad_mox_fuel_rod", "neutron_reflector", "thick_neutron_reflector")) {
      Item material = item(id);
      AbstractDamageableReactorComponent component = (AbstractDamageableReactorComponent) material;
      double fresh = value(helper, material);
      for (double spent : List.of(0.0, 0.5, 1.0)) {
        ItemStack stack = new ItemStack(material);
        component.setUse(stack, (int) (component.getMaxUse() * spent));
        ItemStack before = stack.copy();
        assertNear(helper, ItemValues.stackValue(helper.getLevel(), stack), fresh * (1 - spent),
            id + " must account for actual remaining fuel or reflector life");
        helper.assertTrue(ItemStack.isSameItemSameComponents(before, stack), "valuation must not consume reactor life");
      }
    }
    ItemStack hydration = new ItemStack(item("hydration_cell"));
    StackUtil.updateNbtData(hydration, tag -> tag.putInt("uses", 7_500));
    assertNear(helper, ItemValues.stackValue(helper.getLevel(), hydration), value(helper, hydration.getItem()) / 4,
        "hydration cells use their permanent 10000-charge counter");
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void recoverableHeatAndBreedingProgressAreNotPermanentWear(GameTestHelper helper) {
    for (String id : List.of("heat_vent", "advanced_heat_vent", "overclocked_heat_vent",
        "reactor_coolant_cell", "iridium_neutron_reflector", "depleted_isotope_fuel_rod")) {
      ItemStack stack = new ItemStack(item(id));
      StackUtil.updateNbtData(stack, tag -> tag.putInt("use", 500));
      ItemStack before = stack.copy();
      assertNear(helper, ItemValues.stackValue(helper.getLevel(), stack), value(helper, stack.getItem()),
          id + " must not treat reversible heat or progress as spent material");
      helper.assertTrue(ItemStack.isSameItemSameComponents(before, stack), "valuation must not reset component state");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void realMenuWinConsumesInputOnceAndCreatesFreshUnchargedArmor(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture()) {
      deterministicChance(1);
      ServerPlayer player = freshPlayer(helper);
      UpgraderMenu menu = menu(player);
      ItemStack input = ElectricItemManager.getCharged(Ic2Items.IRIDIUM_DRILL, 100_000);
      input.set(DataComponents.CUSTOM_NAME, Component.literal("fixture input name"));
      StackUtil.updateNbtData(input, tag -> tag.putString("compat_fixture", "do not copy"));
      ItemStack before = input.copy();
      menu.getSlot(0).set(input);
      menu.setTarget(player, Ic2Items.NANO_HELMET);
      menu.setCount(player, 64);
      menu.startUpgrade(player);
      helper.assertTrue(menu.isSpinning(), "an ordinary IC2 target must start the actual upgrade");
      menu.startUpgrade(player);
      menu.broadcastChanges();
      helper.assertTrue(menu.isSpinning() && ItemStack.isSameItemSameComponents(menu.getInputStack(), before),
          "a second start during the spin cannot replace or consume the input early");
      menu.broadcastChanges();
      helper.assertTrue(!menu.isSpinning(), "the real broadcast tick must finish the spin");
      ItemStack won = menu.getInputStack();
      helper.assertTrue(won.is(Ic2Items.NANO_HELMET) && won.getCount() == 1,
          "the win replaces the entire input with one unstackable target");
      assertNear(helper, ElectricItem.manager.getCharge(won), 0, "wins cannot manufacture free EU");
      helper.assertTrue(won.get(DataComponents.CUSTOM_NAME) == null
              && StackUtil.getTag(won) == null,
          "winning equipment is a fresh default, without input names or custom state");
      menu.broadcastChanges();
      helper.assertTrue(ItemStack.isSameItemSameComponents(won, menu.getInputStack())
              && menu.getInputStack().getCount() == 1,
          "later menu ticks cannot apply the result a second time");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void realMenuWinHonorsStackableTargetCount(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture()) {
      deterministicChance(1);
      ServerPlayer player = freshPlayer(helper);
      UpgraderMenu menu = menu(player);
      menu.getSlot(0).set(new ItemStack(Items.DIAMOND, 9));
      menu.setTarget(player, item("carbon_fibre"));
      menu.setCount(player, 7);
      menu.startUpgrade(player);
      menu.broadcastChanges();
      menu.broadcastChanges();
      helper.assertTrue(menu.getInputStack().is(item("carbon_fibre")) && menu.getInputStack().getCount() == 7,
          "the actual result must use the requested stackable IC2 target count");
      helper.assertTrue(player.getInventory().isEmpty(), "input must not also be returned to the inventory");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void realMenuLossConsumesTheInputAndCreatesNoOutput(GameTestHelper helper) {
    try (ConfigSnapshot ignored = ConfigSnapshot.capture()) {
      deterministicChance(0);
      ServerPlayer player = freshPlayer(helper);
      UpgraderMenu menu = menu(player);
      menu.getSlot(0).set(new ItemStack(Items.DIAMOND, 9));
      menu.setTarget(player, Ic2Items.QUANTUM_HELMET);
      menu.startUpgrade(player);
      helper.assertTrue(menu.isSpinning(), "a deterministic loss still runs the real spin");
      menu.broadcastChanges();
      menu.broadcastChanges();
      helper.assertTrue(!menu.isSpinning() && menu.getInputStack().isEmpty(),
          "a failed attempt consumes the input without giving a target");
      menu.broadcastChanges();
      menu.removed(player);
      helper.assertTrue(player.getInventory().isEmpty(), "failure or closing cannot refund or duplicate consumed input");
    }
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void closingIdleMenuReturnsTheOriginalChargedAndNamedStack(GameTestHelper helper) {
    ServerPlayer player = freshPlayer(helper);
    UpgraderMenu menu = menu(player);
    ItemStack input = ElectricItemManager.getCharged(Ic2Items.IRIDIUM_DRILL, 123_456);
    input.set(DataComponents.CUSTOM_NAME, Component.literal("original drill"));
    StackUtil.updateNbtData(input, tag -> tag.putInt("compat_fixture", 42));
    ItemStack before = input.copy();
    menu.getSlot(0).set(input);
    menu.broadcastChanges();
    menu.removed(player);
    helper.assertTrue(menu.getInputStack().isEmpty(), "idle closing clears the menu input slot");
    List<ItemStack> retained = player.getInventory().getNonEquipmentItems().stream().filter(s -> !s.isEmpty()).toList();
    helper.assertTrue(retained.size() == 1 && retained.getFirst().getCount() == before.getCount()
            && ItemStack.isSameItemSameComponents(before, retained.getFirst()),
        "idle closing must return the original item, charge and custom components exactly once");
    helper.succeed();
  }

  @GameTest(structure = EMPTY, maxTicks = 100)
  public void realMenuRejectsCreativeDebugAndInvalidStateTargets(GameTestHelper helper) {
    ServerPlayer player = freshPlayer(helper);
    UpgraderMenu menu = menu(player);
    ItemStack input = new ItemStack(Items.DIAMOND, 3);
    menu.getSlot(0).set(input);
    for (String id : UNSUPPORTED) {
      menu.setTarget(player, item(id));
      menu.startUpgrade(player);
      helper.assertTrue(menu.getTarget() == null && !menu.isSpinning(),
          "server menu must reject an unsupported target even if selected outside the catalog: " + id);
      helper.assertTrue(menu.getInputStack().is(Items.DIAMOND) && menu.getInputStack().getCount() == 3,
          "rejected target cannot consume the input");
    }
    menu.setTarget(player, Ic2Items.NANO_HELMET);
    helper.assertTrue(menu.getTarget() == Ic2Items.NANO_HELMET, "supported IC2 armor remains selectable after rejections");
    helper.succeed();
  }

  private static Item item(String path) {
    Identifier id = Identifier.fromNamespaceAndPath("ic2", path);
    Item item = BuiltInRegistries.ITEM.getValue(id);
    if (item == null || item == Items.AIR) throw new IllegalStateException("Missing IC2 test fixture: " + id);
    return item;
  }

  private static double value(GameTestHelper helper, Item item) {
    return ItemValues.unitValue(helper.getLevel(), item);
  }

  private static void assertNear(GameTestHelper helper, double actual, double expected, String reason) {
    helper.assertTrue(Double.isFinite(actual) && Math.abs(actual - expected) <= Math.max(0.000001, Math.abs(expected) * 0.000001),
        reason + ": expected " + expected + ", got " + actual);
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
    UpgraderConfig.attemptLimit = 0;
  }

  /** Tests mutate only in-memory fixture configuration; both configuration and the old cache return. */
  private record ConfigSnapshot(double houseEdge, double minChance, double maxChance, int spinTicks,
      Map<String, Double> overrides, Set<String> blacklist, int maxTargetCount, int attemptLimit,
      int attemptWindowSeconds, double globalMultiplier, Map<String, Double> multipliers,
      Object cachedValues, Object pricingDiagnostics, Object pricingProvenance) implements AutoCloseable {
    private static ConfigSnapshot capture() {
      return new ConfigSnapshot(UpgraderConfig.houseEdge, UpgraderConfig.minChance, UpgraderConfig.maxChance,
          UpgraderConfig.spinTicks, UpgraderConfig.valueOverrides, UpgraderConfig.blacklist,
          UpgraderConfig.maxTargetCount, UpgraderConfig.attemptLimit, UpgraderConfig.attemptWindowSeconds,
          UpgraderConfig.globalValueMultiplier, UpgraderConfig.valueMultipliers,
          getStaticField(ItemValues.class, "values"), getStaticField(Ic2Pricing.class, "last"),
          getStaticField(Ic2Pricing.class, "lastProvenance"));
    }

    @Override
    public void close() {
      UpgraderConfig.apply(houseEdge, minChance, maxChance, spinTicks, overrides, blacklist,
          maxTargetCount, attemptLimit, attemptWindowSeconds, globalMultiplier, multipliers);
      setStaticField(ItemValues.class, "values", cachedValues);
      setStaticField(Ic2Pricing.class, "last", pricingDiagnostics);
      setStaticField(Ic2Pricing.class, "lastProvenance", pricingProvenance);
    }

    private static Object getStaticField(Class<?> owner, String name) {
      try {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
      } catch (ReflectiveOperationException exception) {
        throw new IllegalStateException("Cannot save pre-test " + owner.getSimpleName() + "." + name, exception);
      }
    }

    private static void setStaticField(Class<?> owner, String name, Object value) {
      try {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
      } catch (ReflectiveOperationException exception) {
        throw new IllegalStateException("Cannot restore pre-test " + owner.getSimpleName() + "." + name, exception);
      }
    }
  }
}
