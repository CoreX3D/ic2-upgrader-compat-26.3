package dev.core.ic2upgrader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.execheinz.upgrader.value.ItemValues;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;

/** Native, server-authoritative enchantment variants for Upgrader's otherwise item-only catalogue. */
public final class BookTargets {
    private static final int MAX_ENTRIES = 16_384;
    private static final int MAX_ID_LENGTH = 256;
    private static long catalogRevision;

    private BookTargets() { }

    public record Key(String enchantmentId, int level) { }
    public record Entry(Key key, double value) { }

    public static void invalidateCatalog() { catalogRevision++; }
    public static long catalogRevision() { return catalogRevision; }

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(SelectPayload.TYPE, SelectPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CatalogPayload.TYPE, CatalogPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SelectedPayload.TYPE, SelectedPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SelectPayload.TYPE, (payload, context) ->
                select(context.player(), payload.containerId(), payload.key()));
    }

    public static List<Entry> catalog(Level level) {
        if (ItemValues.isBlacklisted(Items.ENCHANTED_BOOK)) return List.of();
        List<Entry> entries = new ArrayList<>();
        var registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        registry.listElements().forEach(holder -> {
            var enchantment = holder.value();
            if (!supported(level, holder)) return;
            String id = holder.key().identifier().toString();
            for (int n = Math.max(1, enchantment.getMinLevel());
                    n <= Math.min(Enchantment.MAX_LEVEL, enchantment.getMaxLevel()); n++) {
                Key key = new Key(id, n);
                double value = value(level, key);
                if (Double.isFinite(value) && value > 0) entries.add(new Entry(key, value));
            }
        });
        entries.sort(Comparator.comparing((Entry entry) -> entry.key().enchantmentId())
                .thenComparingInt(entry -> entry.key().level()));
        return List.copyOf(entries);
    }

    public static boolean isLegal(Level level, Key key) {
        return holder(level, key) != null;
    }

    private static Holder.Reference<Enchantment> holder(Level level, Key key) {
        if (level == null || key == null || key.enchantmentId() == null
                || key.enchantmentId().length() > MAX_ID_LENGTH || key.level() < 1
                || key.level() > Enchantment.MAX_LEVEL) return null;
        Identifier id = Identifier.tryParse(key.enchantmentId());
        if (id == null) return null;
        var holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(id).orElse(null);
        if (holder == null || key.level() < holder.value().getMinLevel()
                || key.level() > holder.value().getMaxLevel() || !supported(level, holder)) return null;
        return holder;
    }

    private static boolean supported(Level level, Holder<Enchantment> holder) {
        return holder.value().getSupportedItems().stream()
                .anyMatch(item -> item.value().isEnabled(level.enabledFeatures()));
    }

    public static ItemStack create(Level level, Key key) {
        var holder = holder(level, key);
        return holder == null ? ItemStack.EMPTY
                : EnchantmentHelper.createBook(new EnchantmentInstance(holder, key.level()));
    }

    /** Each successive level costs twice as much, matching the two-book anvil combination rule. */
    public static double value(Level level, Key key) {
        var holder = holder(level, key);
        if (holder == null || ItemValues.isBlacklisted(Items.ENCHANTED_BOOK)) return 0;
        double base = ItemValues.unitValue(level, Items.ENCHANTED_BOOK);
        if (!Double.isFinite(base) || base <= 0) return 0;
        double price = Math.scalb(base * (1.0 + Math.max(0, holder.value().getAnvilCost())), key.level() - 1);
        return Double.isFinite(price) ? price : Double.MAX_VALUE;
    }

    /** Valuable input books retain all their stored enchantments, including legitimate combinations. */
    public static double stackValue(Level level, ItemStack stack, double original) {
        if (!stack.is(Items.ENCHANTED_BOOK)) return original;
        ItemEnchantments stored = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (stored.isEmpty()) return 0;
        double value = 0;
        for (var entry : stored.entrySet()) {
            var id = entry.getKey().unwrapKey().orElse(null);
            if (id == null) continue;
            value += value(level, new Key(id.identifier().toString(), entry.getIntValue()));
        }
        double total = value * stack.getCount();
        return Double.isFinite(total) ? total : Double.MAX_VALUE;
    }

    public static boolean select(ServerPlayer player, int containerId, Key key) {
        if (!(player.containerMenu instanceof UpgraderMenu menu) || menu.containerId != containerId
                || menu.isSpinning() || !player.isAlive() || !menu.stillValid(player)
                || !isLegal(player.level(), key) || value(player.level(), key) <= 0
                || ItemValues.isBlacklisted(Items.ENCHANTED_BOOK)) return false;
        menu.setTarget(player, Items.ENCHANTED_BOOK);
        ((BookTargetMenu) menu).ic2upgrader$setBookTarget(key);
        // Native count synchronization sends the authoritative new price and a selected-key acknowledgement.
        menu.setCount(player, 1);
        return true;
    }

    public static void sendCatalog(ServerPlayer player, UpgraderMenu menu) {
        if (ServerPlayNetworking.canSend(player, CatalogPayload.TYPE)) {
            ServerPlayNetworking.send(player, new CatalogPayload(menu.containerId, catalog(player.level())));
        }
    }

    public static void sendSelected(ServerPlayer player, UpgraderMenu menu, Key key) {
        if (ServerPlayNetworking.canSend(player, SelectedPayload.TYPE)) {
            ServerPlayNetworking.send(player, new SelectedPayload(menu.containerId, key));
        }
    }

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath("ic2_upgrader_compat", name);
    }

    private static Key readKey(RegistryFriendlyByteBuf buffer) {
        return new Key(buffer.readUtf(MAX_ID_LENGTH), buffer.readVarInt());
    }

    private static void writeKey(RegistryFriendlyByteBuf buffer, Key key) {
        buffer.writeUtf(key.enchantmentId(), MAX_ID_LENGTH);
        buffer.writeVarInt(key.level());
    }

    public record SelectPayload(int containerId, Key key) implements CustomPacketPayload {
        public static final Type<SelectPayload> TYPE = new Type<>(id("select_book"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SelectPayload> CODEC =
                CustomPacketPayload.codec(SelectPayload::write, SelectPayload::new);
        private SelectPayload(RegistryFriendlyByteBuf buffer) { this(buffer.readVarInt(), readKey(buffer)); }
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(containerId);
            writeKey(buffer, key);
        }
        @Override public Type<SelectPayload> type() { return TYPE; }
    }

    public record CatalogPayload(int containerId, List<Entry> entries) implements CustomPacketPayload {
        public static final Type<CatalogPayload> TYPE = new Type<>(id("book_catalog"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CatalogPayload> CODEC =
                CustomPacketPayload.codec(CatalogPayload::write, CatalogPayload::new);
        public CatalogPayload { entries = List.copyOf(entries); }
        private CatalogPayload(RegistryFriendlyByteBuf buffer) { this(buffer.readVarInt(), readEntries(buffer)); }
        private static List<Entry> readEntries(RegistryFriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_ENTRIES) throw new IllegalArgumentException("Invalid book catalogue size");
            List<Entry> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) entries.add(new Entry(readKey(buffer), buffer.readDouble()));
            return entries;
        }
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(containerId);
            buffer.writeVarInt(entries.size());
            for (Entry entry : entries) {
                writeKey(buffer, entry.key());
                buffer.writeDouble(entry.value());
            }
        }
        @Override public Type<CatalogPayload> type() { return TYPE; }
    }

    public record SelectedPayload(int containerId, Key key) implements CustomPacketPayload {
        public static final Type<SelectedPayload> TYPE = new Type<>(id("selected_book"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SelectedPayload> CODEC =
                CustomPacketPayload.codec(SelectedPayload::write, SelectedPayload::new);
        private SelectedPayload(RegistryFriendlyByteBuf buffer) {
            this(buffer.readVarInt(), buffer.readBoolean() ? readKey(buffer) : null);
        }
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(containerId);
            buffer.writeBoolean(key != null);
            if (key != null) writeKey(buffer, key);
        }
        @Override public Type<SelectedPayload> type() { return TYPE; }
    }
}
