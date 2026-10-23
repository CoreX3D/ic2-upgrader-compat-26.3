package dev.core.ic2upgrader;

import ic2.api.recipe.ICannerBottleRecipeManager;
import ic2.api.recipe.IRecipeInput;
import ic2.api.recipe.MachineRecipe;
import ic2.api.recipe.MachineRecipeWeighted;
import ic2.core.item.tool.ItemToolCrafting;
import ic2.core.recipe.AdvRecipe;
import ic2.core.recipe.AdvShapelessRecipe;
import ic2.core.recipe.input.RecipeInputFluidContainer;
import ic2.core.recipe.v2.RecipeJson;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.execheinz.upgrader.UpgraderConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;

/** Deterministic IC2 material/crafting prices, in Upgrader's existing raw value units. */
public final class Ic2Pricing {
  private static final int MAX_PASSES = 64;
  private static final double MAX_VALUE = 1.0E12;
  private static volatile Diagnostics last = new Diagnostics(0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
  private static volatile Map<String, String> lastProvenance = Map.of();

  private Ic2Pricing() {}

  /** Does not call ItemValues: this is invoked from its compute-return hook. */
  public static Map<Item, Double> adapt(Level level, Map<Item, Double> vanillaTable) {
    Map<Item, Double> result = new HashMap<>(vanillaTable);
    Map<Item, Quote> quotes = new HashMap<>();
    Map<Item, String> sources = new HashMap<>();
    Set<Item> fixed = new HashSet<>();
    List<Item> icItems = BuiltInRegistries.ITEM.stream().filter(Ic2Pricing::isIc2)
        .sorted(Comparator.comparing(Ic2Pricing::id)).toList();
    // Ignore the old rarity fallback for IC2. It cannot price machine-only ingredients.
    vanillaTable.forEach((item, value) -> {
      if (!isIc2(item) && valid(value)) quotes.put(item, new Quote(value, Set.of(item)));
    });
    // Upgrader's table is sparse: unitValue uses a rarity fallback for absent vanilla items
    // (orange dye, for example). Match that existing behavior only in our working inputs.
    // Do not add those entries to the returned map or assign the fallback to an IC2 output.
    BuiltInRegistries.ITEM.stream().filter(item -> !isIc2(item) && !vanillaTable.containsKey(item))
        .forEach(item -> quotes.put(item, new Quote(nonIcFallback(item), Set.of(item))));
    for (Item item : icItems) {
      result.remove(item);
      Double configured = UpgraderConfig.valueOverrides.get(id(item));
      if (configured != null) {
        // Explicit configuration is authoritative, including an intentional zero value.
        result.put(item, configured);
        fixed.add(item);
        sources.put(item, "upgrader:valueOverrides");
        if (valid(configured)) quotes.put(item, new Quote(configured, Set.of(item)));
      }
    }
    seedAnchors(vanillaTable, quotes, fixed, sources);
    List<Edge> recipes = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    collectFluidContainers(vanillaTable, recipes);
    collectRuntimeProducts(vanillaTable, recipes);
    if (level != null && level.recipeAccess() instanceof RecipeManager manager) {
      manager.getRecipes().stream().sorted(Comparator.comparing(holder -> holder.id().toString()))
          .forEach(holder -> collect(holder.value(), holder.id().toString(), level, recipes, skipped));
    }
    int passes = 0;
    for (; passes < MAX_PASSES; passes++) {
      boolean changed = false;
      for (Edge edge : recipes) {
        Quote cost = edge.cost(quotes);
        if (cost == null) continue;
        for (Product output : edge.outputs) {
          Item item = output.item;
          if (!isIc2(item) || fixed.contains(item) || cost.ancestry.contains(item)) continue;
          // Multiple deterministic outputs share the same consumed input budget. Never charge
          // the full recipe price separately for every byproduct.
          double candidate = cost.value / edge.outputCount;
          if (!valid(candidate)) continue;
          Quote old = quotes.get(item);
          if (old == null || candidate < old.value - 1.0E-8) {
            Set<Item> ancestry = new HashSet<>(cost.ancestry);
            ancestry.add(item);
            quotes.put(item, new Quote(candidate, Set.copyOf(ancestry)));
            sources.put(item, edge.id);
            changed = true;
          }
        }
      }
      if (!changed) { passes++; break; }
    }
    int anchored = 0, derived = 0, overrides = 0;
    Map<String, String> provenance = new TreeMap<>();
    List<String> unresolved = new ArrayList<>();
    for (Item item : icItems) {
      String source = sources.get(item);
      Quote quote = quotes.get(item);
      if (source != null && source.equals("upgrader:valueOverrides")) overrides++;
      else if (fixed.contains(item)) anchored++;
      else if (quote != null) derived++;
      if (quote != null) result.put(item, quote.value);
      else if (!result.containsKey(item)) unresolved.add(id(item));
      if (source != null) provenance.put(id(item), source);
    }
    lastProvenance = Map.copyOf(provenance);
    last = new Diagnostics(icItems.size(), anchored + derived + overrides, anchored, derived,
        overrides, recipes.size(), passes, List.copyOf(unresolved), List.copyOf(skipped));
    return result;
  }

  public static Diagnostics diagnostics() { return last; }
  public static Map<String, String> provenance() { return lastProvenance; }
  public static boolean hasResolvedPrice(Item item) {
    return !isIc2(item) || lastProvenance.containsKey(id(item));
  }
  public static String diagnosticsSummary() {
    Diagnostics d = last;
    return "IC2 prices: " + d.priced + "/" + d.total + " (anchors=" + d.anchored
        + ", recipes=" + d.derived + ", overrides=" + d.overridden + "), "
        + d.recipeCount + " recipe edges, " + d.passes + " passes; unresolved=" + d.unresolved;
  }

  public record Diagnostics(int total, int priced, int anchored, int derived, int overridden,
      int recipeCount, int passes, List<String> unresolved, List<String> skippedRecipes) {}

  private static void seedAnchors(Map<Item, Double> base, Map<Item, Quote> quotes,
      Set<Item> fixed, Map<Item, String> sources) {
    double iron = vanilla(base, Items.IRON_INGOT, 40), copper = vanilla(base, Items.COPPER_INGOT, 12);
    double gold = vanilla(base, Items.GOLD_INGOT, 60), diamond = vanilla(base, Items.DIAMOND, 400);
    double coal = vanilla(base, Items.COAL, 8), planks = vanilla(base, Items.OAK_PLANKS, 1);
    Map<String, Double> anchors = new LinkedHashMap<>();
    family(anchors, "copper", copper);
    family(anchors, "iron", iron);
    family(anchors, "gold", gold);
    family(anchors, "tin", iron * 0.6);
    family(anchors, "lead", iron * 0.8);
    family(anchors, "silver", iron * 1.5);
    // Steel adds a long heated blast-furnace process to iron; keep a material anchor so its
    // slag byproduct cannot halve the price of every advanced machine made from steel.
    family(anchors, "steel", iron * 1.25);
    // Bronze combines three copper units and one tin into four units.
    family(anchors, "bronze", (3 * copper + iron * 0.6) / 4);
    anchor(anchors, diamond, "industrial_diamond", "diamond_dust");
    anchor(anchors, diamond / 9, "small_diamond_dust");
    anchor(anchors, coal, "coal_dust", "coal_fuel_dust", "coke");
    anchor(anchors, coal * 9, "coal_block");
    anchor(anchors, vanilla(base, Items.LAPIS_LAZULI, 10), "lapis_dust");
    anchor(anchors, vanilla(base, Items.LAPIS_LAZULI, 10) / 9, "small_lapis_dust");
    anchor(anchors, vanilla(base, Items.OBSIDIAN, 24), "obsidian_dust");
    anchor(anchors, vanilla(base, Items.OBSIDIAN, 24) / 9, "small_obsidian_dust");
    anchor(anchors, vanilla(base, Items.CLAY_BALL, 1), "clay_dust");
    anchor(anchors, vanilla(base, Items.STONE, 1), "stone_dust", "basalt");
    anchor(anchors, vanilla(base, Items.NETHERRACK, 1), "netherrack_dust");
    anchor(anchors, vanilla(base, Items.ENDER_PEARL, 80), "ender_pearl_dust");
    anchor(anchors, vanilla(base, Items.SAND, 1), "silicon_dioxide_dust");
    anchor(anchors, planks * 3, "resin");
    anchor(anchors, planks, "rubber");
    anchor(anchors, vanilla(base, Items.OAK_LOG, planks * 4), "rubber_log", "rubber_wood",
        "stripped_rubber_log", "stripped_rubber_wood");
    anchor(anchors, vanilla(base, Items.OAK_SAPLING, planks), "rubber_sapling");
    anchor(anchors, vanilla(base, Items.OAK_LEAVES, 1), "rubber_leaves");
    anchor(anchors, vanilla(base, Items.WHEAT, 2), "coffee_beans", "hops", "weed", "milk_wart",
        "terra_wart", "oil_berry", "bobs_yer_uncle_ranks_berry");
    anchor(anchors, coal / 4, "ashes", "slag");
    anchor(anchors, iron / 4, "sulfur_dust", "lithium_dust", "iodine");
    anchor(anchors, iron / 36, "small_sulfur_dust", "small_lithium_dust");
    // Natural uranium yields six U-238 plus one small U-235 pile per crushed ore.
    anchor(anchors, iron * 4, "raw_uranium", "uranium_ingot");
    anchor(anchors, iron * 36, "raw_uranium_block", "uranium_block");
    anchor(anchors, iron * 8, "uranium_ore", "deepslate_uranium_ore");
    anchor(anchors, iron * 2, "crushed_uranium", "purified_uranium");
    anchor(anchors, iron / 4, "uranium_238");
    anchor(anchors, iron / 36, "small_uranium_238");
    anchor(anchors, iron * 4.5, "uranium_235");
    anchor(anchors, iron / 2, "small_uranium_235");
    // Plutonium is a reactor-cycle byproduct. It is not a cheap rarity fallback.
    anchor(anchors, iron * 18, "plutonium");
    anchor(anchors, iron * 2, "small_plutonium");
    anchor(anchors, diamond * 12, "iridium_ore");
    anchor(anchors, diamond * 12 / 9, "iridium_shard");
    // Scrap and UU are explicit economic anchors for non-item/energy-only production chains.
    anchor(anchors, planks / 6, "scrap");
    anchor(anchors, diamond * 4, "uu_matter");
    anchor(anchors, iron, "near_depleted_uranium", "re_enriched_uranium");
    // Legacy non-fluid sheets remain obtainable materials in this IC2 port.
    anchor(anchors, planks, "water_sheet");
    anchor(anchors, Math.max(planks, vanilla(base, Items.LAVA_BUCKET, iron * 4) - iron * 3), "lava_sheet");
    for (Map.Entry<String, Double> entry : anchors.entrySet()) {
      Item item = find(entry.getKey());
      if (item == null || fixed.contains(item) || !valid(entry.getValue())) continue;
      fixed.add(item);
      quotes.put(item, new Quote(entry.getValue(), Set.of(item)));
      sources.put(item, "anchor:" + entry.getKey());
    }
    // Containers include their reusable shell. Arbitrary filled states are handled by the
    // catalog policy; these anchors allow machine recipes to account for actual fluid cost.
    double bucket = vanilla(base, Items.BUCKET, iron * 3);
    for (Map.Entry<String, Double> fluid : fluidValues(base).entrySet()) {
      seed(fluid.getKey() + "_bucket", bucket + fluid.getValue(), quotes, fixed, sources);
    }
  }

  private static Map<String, Double> fluidValues(Map<Item, Double> base) {
    double iron = vanilla(base, Items.IRON_INGOT, 40), coal = vanilla(base, Items.COAL, 8);
    double planks = vanilla(base, Items.OAK_PLANKS, 1), diamond = vanilla(base, Items.DIAMOND, 400);
    Map<String, Double> fluids = new LinkedHashMap<>();
    fluids.put("water", planks); fluids.put("distilled_water", planks * 2);
    fluids.put("air", planks); fluids.put("oxygen", planks * 2);
    fluids.put("hydrogen", planks * 2); fluids.put("biomass", planks * 4);
    fluids.put("biogas", planks * 4); fluids.put("creosote", coal / 2);
    fluids.put("coolant", iron / 4); fluids.put("hot_coolant", iron / 4);
    fluids.put("hot_water", planks); fluids.put("steam", planks);
    fluids.put("superheated_steam", planks * 2); fluids.put("heavy_water", planks * 4);
    fluids.put("pahoehoe_lava", iron); fluids.put("weed_ex", iron / 4);
    fluids.put("construction_foam", planks * 4); fluids.put("uu_matter", diamond * 4000);
    fluids.put("lava", iron);
    return fluids;
  }

  private static void collectFluidContainers(Map<Item, Double> base, List<Edge> edges) {
    Item shell = find("empty_cell");
    if (shell == null) return;
    for (Map.Entry<String, Double> fluid : fluidValues(base).entrySet()) {
      Item filled = find(fluid.getKey() + "_cell");
      if (filled == null) continue;
      add(edges, "fluid-fill:" + fluid.getKey(),
          List.of(new Input(List.of(new ItemStack(shell)), 1, false), new Input(fluid.getValue())),
          List.of(new ItemStack(filled)));
    }
  }

  private static void collectRuntimeProducts(Map<Item, Double> base, List<Edge> edges) {
    // One CF-powder plus 1,000 mB water makes 1,000 mB construction foam. The sprayer
    // spends 100 mB per block, and hardening/recoloring does not consume the sprayer.
    policy(edges, "runtime:sprayer", "foam", 10, Map.of("cf_powder", 1),
        fluidValues(base).get("water"));
    for (String color : List.of("white", "orange", "magenta", "light_blue", "yellow", "lime",
        "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")) {
      policy(edges, "runtime:hardened-foam", color + "_wall", 1, Map.of("foam", 1), 0);
    }
    // The intact boat uses a vanilla boat plus five rubber; repair consumes two rubber.
    policy(edges, "runtime:broken-boat-material", "broken_rubber_boat", 1,
        Map.of("minecraft:oak_boat", 1, "rubber", 3), 0);

    // These registered legacy items have no production recipe in alpha.12. Explicit material
    // policies keep them out of Upgrader's generic rarity fallback. They are economic quotes,
    // not new recipes, and all can be replaced by Upgrader's normal valueOverrides.
    policy(edges, "policy:advanced-filter-module", "advanced_ejector_upgrade", 1,
        Map.of("ejector_upgrade", 1, "advanced_circuit", 1), 0);
    policy(edges, "policy:advanced-filter-module", "advanced_pulling_upgrade", 1,
        Map.of("pulling_upgrade", 1, "advanced_circuit", 1), 0);
    policy(edges, "policy:remote-interface", "remote_interface_upgrade", 1,
        Map.of("frequency_transmitter", 1, "advanced_circuit", 1), 0);
    // Historic IC2 crowbar recipe: three bronze ingots and four red dye.
    policy(edges, "policy:legacy-crowbar", "crowbar", 1,
        Map.of("bronze_ingot", 3, "minecraft:red_dye", 4), 0);
    policy(edges, "policy:hydration-cell", "hydration_cell", 1, Map.of("water_cell", 1), 0);
    policy(edges, "policy:electrolyzed-water", "electrolyzed_water_cell", 1,
        Map.of("water_cell", 1), 0);
    policy(edges, "policy:legacy-foam-pellet", "pellet", 1, Map.of("foam", 1), 0);
    policy(edges, "policy:uranium-pellet", "uranium_pellet", 1, Map.of("uranium", 1), 0);
    policy(edges, "policy:lithium-fuel-fill", "lithium_fuel_rod", 1,
        Map.of("fuel_rod", 1, "lithium_dust", 1), 0);
    // Native reactor pulses transmute the existing lithium rod into tritium; there is no
    // additional item input. EU and processing time are not part of material valuation.
    policy(edges, "runtime:lithium-breeding", "tritium_fuel_rod", 1,
        Map.of("lithium_fuel_rod", 1), 0);
    policy(edges, "policy:legacy-isotope-fill", "depleted_isotope_fuel_rod", 1,
        Map.of("fuel_rod", 1, "near_depleted_uranium", 1), 0);
  }

  private static void policy(List<Edge> edges, String source, String output, int count,
      Map<String, Integer> ingredients, double constant) {
    Item product = find(output);
    if (product == null) return;
    List<Input> inputs = new ArrayList<>();
    for (Map.Entry<String, Integer> ingredient : new TreeMap<>(ingredients).entrySet()) {
      Identifier key = ingredient.getKey().contains(":") ? Identifier.parse(ingredient.getKey())
          : Identifier.fromNamespaceAndPath("ic2", ingredient.getKey());
      if (!BuiltInRegistries.ITEM.containsKey(key)) return;
      inputs.add(new Input(List.of(new ItemStack(BuiltInRegistries.ITEM.getValue(key))),
          ingredient.getValue(), false));
    }
    if (constant > 0) inputs.add(new Input(constant));
    add(edges, source + ":" + output, inputs, List.of(new ItemStack(product, count)));
  }

  private static void family(Map<String, Double> values, String metal, double ingot) {
    anchor(values, ingot, metal + "_ingot", metal + "_dust", "crushed_" + metal,
        "purified_" + metal, "raw_" + metal);
    anchor(values, ingot / 9, "small_" + metal + "_dust");
    anchor(values, ingot * 9, metal + "_block", "raw_" + metal + "_block");
    anchor(values, ingot * 2, metal + "_ore", "deepslate_" + metal + "_ore");
  }
  private static void anchor(Map<String, Double> map, double value, String... names) {
    for (String name : names) map.put(name, value);
  }
  private static void seed(String name, double value, Map<Item, Quote> quotes, Set<Item> fixed,
      Map<Item, String> sources) {
    Item item = find(name);
    if (item != null && !fixed.contains(item) && valid(value)) {
      quotes.put(item, new Quote(value, Set.of(item)));
      fixed.add(item); sources.put(item, "anchor:" + name);
    }
  }

  private static void collect(Recipe<?> recipe, String id, Level level, List<Edge> edges,
      List<String> skipped) {
    try {
      if (recipe instanceof AdvRecipe shaped) {
        add(edges, id, icInputs(shaped.input, !shaped.consuming),
            List.of(RecipeJson.materialize(shaped.output)));
      } else if (recipe instanceof AdvShapelessRecipe shapeless) {
        add(edges, id, icInputs(shapeless.input, !shapeless.consuming),
            List.of(RecipeJson.materialize(shapeless.output)));
      } else if (recipe instanceof ic2.core.recipe.v2.RecipeHolder<?, ?> machineHolder) {
        MachineRecipe<?, ?> machine = machineHolder.recipe();
        if (machine instanceof MachineRecipeWeighted<?>) {
          skipped.add(id + ": weighted output deliberately not sampled");
          return;
        }
        List<Input> inputs = machineInputs(machine.getInput());
        if (inputs == null) { skipped.add(id + ": non-item input"); return; }
        Object raw = machine.getOutput(); // Weighted subclass is excluded above.
        List<ItemStack> outputs = new ArrayList<>();
        if (raw instanceof ItemStack stack) outputs.add(stack);
        else if (raw instanceof Collection<?> collection) {
          for (Object output : collection) if (output instanceof ItemStack stack) outputs.add(stack);
        }
        if (!outputs.isEmpty()) {
          add(edges, id, inputs, outputs);
          // Reactor-spent fuel is created by elapsed operation rather than a crafting recipe.
          // Its recoverable material value is the exact deterministic centrifuge salvage.
          if (machine.getInput() instanceof IRecipeInput input) {
            for (ItemStack spent : input.getInputs()) {
              if (!isIc2(spent.getItem()) || !id(spent.getItem()).startsWith("ic2:depleted_")) continue;
              List<Input> salvage = outputs.stream().filter(stack -> !stack.isEmpty())
                  .map(stack -> new Input(List.of(stack), stack.getCount(), false)).toList();
              add(edges, "salvage:" + id, salvage, List.of(spent));
            }
          }
        }
      } else {
        List<ItemStack> output = recipe.display().stream().map(display -> display.result()
            .resolveForFirstStack(SlotDisplayContext.fromLevel(level))).filter(s -> !s.isEmpty()).toList();
        if (output.isEmpty() || !isIc2(output.getFirst().getItem())) return;
        List<Input> inputs = new ArrayList<>();
        for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
          inputs.add(new Input(ingredient.items().map(holder -> new ItemStack(holder.value())).toList(), 1, false));
        }
        add(edges, id, inputs, List.of(output.getFirst()));
      }
    } catch (RuntimeException exception) {
      skipped.add(id + ": " + exception.getClass().getSimpleName());
    }
  }
  private static List<Input> icInputs(IRecipeInput[] inputs, boolean reusable) {
    List<Input> result = new ArrayList<>();
    for (IRecipeInput input : inputs) {
      if (input instanceof RecipeInputFluidContainer fluid) {
        // The dynamic discovery list can be cached before fluid item defaults are bound.
        // The static ingredient uses dedicated registered buckets and classic filled cells.
        result.add(new Input(fluid.getIngredient().items()
            .map(holder -> new ItemStack(holder.value())).toList(), 1, reusable));
      } else if (input != null) result.add(new Input(input.getInputs(), input.getAmount(), reusable));
    }
    return result;
  }
  private static List<Input> machineInputs(Object input) {
    if (input instanceof IRecipeInput ic) return List.of(new Input(ic.getInputs(), ic.getAmount(), false));
    if (input instanceof ICannerBottleRecipeManager.Input bottle) {
      return icInputs(new IRecipeInput[]{bottle.container(), bottle.fill()}, false);
    }
    if (input instanceof Collection<?> collection) {
      List<Input> combined = new ArrayList<>();
      for (Object entry : collection) {
        List<Input> part = machineInputs(entry);
        if (part == null) return null;
        combined.addAll(part);
      }
      return combined;
    }
    return null;
  }
  private static void add(List<Edge> edges, String id, List<Input> inputs, List<ItemStack> outputs) {
    if (inputs == null || inputs.isEmpty()
        || inputs.stream().anyMatch(in -> in.options.isEmpty() && !Double.isFinite(in.constant))) return;
    List<Product> products = outputs.stream().filter(stack -> !stack.isEmpty())
        .map(stack -> new Product(stack.getItem(), stack.getCount())).toList();
    if (products.isEmpty()) return;
    int count = products.stream().mapToInt(Product::count).sum();
    if (count > 0) edges.add(new Edge(id, inputs, products, count));
  }

  private record Quote(double value, Set<Item> ancestry) {}
  private record Product(Item item, int count) {}
  private record Input(List<ItemStack> options, int amount, boolean reusable, double constant) {
    Input(List<ItemStack> options, int amount, boolean reusable) {
      this(options, amount, reusable, Double.NaN);
    }
    Input(double constant) { this(List.of(), 1, false, constant); }
    Quote cost(Map<Item, Quote> quotes) {
      if (Double.isFinite(constant)) return new Quote(Math.max(0, constant), Set.of());
      Quote cheapest = null;
      for (ItemStack option : options) {
        Quote quote = quotes.get(option.getItem());
        if (quote == null) continue;
        double value = quote.value;
        Set<Item> ancestry = new HashSet<>(quote.ancestry);
        if (reusable && option.getItem() instanceof ItemToolCrafting && option.getMaxDamage() > 0) {
          value /= option.getMaxDamage();
        } else if (reusable) {
          var remainderTemplate = option.getCraftingRemainder();
          ItemStack remainder = remainderTemplate == null ? ItemStack.EMPTY : remainderTemplate.create();
          if (!remainder.isEmpty()) {
            Quote returned = quotes.get(remainder.getItem());
            if (returned != null) value = Math.max(0, value - returned.value * remainder.getCount());
          }
        }
        value *= amount;
        if (Double.isFinite(value) && value >= 0 && (cheapest == null || value < cheapest.value)) {
          cheapest = new Quote(value, Set.copyOf(ancestry));
        }
      }
      return cheapest;
    }
  }
  private record Edge(String id, List<Input> inputs, List<Product> outputs, int outputCount) {
    Quote cost(Map<Item, Quote> quotes) {
      double sum = 0;
      Set<Item> ancestry = new LinkedHashSet<>();
      for (Input input : inputs) {
        Quote cost = input.cost(quotes);
        if (cost == null) return null;
        sum += cost.value; ancestry.addAll(cost.ancestry);
      }
      return valid(sum) ? new Quote(sum, Set.copyOf(ancestry)) : null;
    }
  }
  private static boolean valid(Double value) {
    return value != null && Double.isFinite(value) && value > 1.0E-9 && value <= MAX_VALUE;
  }
  private static double vanilla(Map<Item, Double> base, Item item, double fallback) {
    Double value = base.get(item);
    return valid(value) ? value : fallback;
  }
  private static double nonIcFallback(Item item) {
    return switch (item.getDefaultInstance().getRarity()) {
      case UNCOMMON -> 150;
      case RARE -> 600;
      case EPIC -> 2500;
      default -> 3;
    };
  }
  private static Item find(String path) {
    Identifier id = Identifier.fromNamespaceAndPath("ic2", path);
    return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.getValue(id) : null;
  }
  private static boolean isIc2(Item item) { return BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("ic2"); }
  private static String id(Item item) { return BuiltInRegistries.ITEM.getKey(item).toString(); }
}
