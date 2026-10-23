package dev.core.ic2upgrader;

import ic2.api.item.IElectricItem;
import ic2.core.item.reactor.AbstractDamageableReactorComponent;
import ic2.core.item.reactor.ItemReactorReflector;
import ic2.core.item.reactor.ItemReactorUranium;
import ic2.core.util.StackUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** IC2-specific restrictions and consumable wear that Upgrader cannot infer from vanilla damage. */
public final class Ic2StackRules {
  private static final int HYDRATION_CELL_CHARGES = 10_000;

  private Ic2StackRules() {}

  /** Adds restrictions to Upgrader's own blacklist; it does not replace the user's configuration. */
  public static boolean isUnsupportedTarget(Item item) {
    Identifier id = BuiltInRegistries.ITEM.getKey(item);
    if (id == null || !id.getNamespace().equals("ic2")) return false;

    return switch (id.getPath()) {
      case "creative_generator", "debug_item" -> true;
      // Upgrader creates only a fresh item ID, without the crop identity and genetics needed to plant.
      case "crop_seed_bag" -> true;
      default -> false;
    };
  }

  /**
   * Preserves Upgrader's computed material value and normal durability behavior, applying IC2's
   * separate permanent-use counter only where it actually represents consumed item life.
   */
  public static double adjustStackValue(Level level, ItemStack stack, double original) {
    if (stack.isEmpty()) return original;
    Item item = stack.getItem();
    Identifier id = BuiltInRegistries.ITEM.getKey(item);
    if (id == null || !id.getNamespace().equals("ic2")) return original;

    // EU is rechargeable energy, not permanent wear. Winning equipment remains fresh and uncharged.
    if (item instanceof IElectricItem) return original;

    if (item instanceof ItemReactorUranium || item instanceof ItemReactorReflector) {
      AbstractDamageableReactorComponent component = (AbstractDamageableReactorComponent) item;
      return original * remainingFraction(component.getUse(stack), component.getMaxUse());
    }

    // A hydration cell has 10,000 consumable charges stored in custom NBT, no vanilla DAMAGE value.
    if (id.getPath().equals("hydration_cell")) {
      CompoundTag tag = StackUtil.getTag(stack);
      int uses = tag == null ? 0 : tag.getIntOr("uses", 0);
      return original * remainingFraction(uses, HYDRATION_CELL_CHARGES);
    }

    // In other reactor components "use" can be recoverable heat or breeding progress, not wear.
    return original;
  }

  private static double remainingFraction(int used, int capacity) {
    if (capacity <= 0) return 1.0;
    return 1.0 - Math.clamp((double) used / capacity, 0.0, 1.0);
  }
}
