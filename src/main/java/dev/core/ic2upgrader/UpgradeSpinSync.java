package dev.core.ic2upgrader;

import java.util.concurrent.atomic.AtomicLong;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Pairs the animation start with the server's committed inventory result. */
public final class UpgradeSpinSync {
    private static final AtomicLong NEXT_ROUND = new AtomicLong();

    private UpgradeSpinSync() { }

    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(StartPayload.TYPE, StartPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FinishPayload.TYPE, FinishPayload.CODEC);
    }

    public static boolean canSend(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, StartPayload.TYPE)
                && ServerPlayNetworking.canSend(player, FinishPayload.TYPE);
    }

    public static long nextRoundId() {
        return NEXT_ROUND.incrementAndGet();
    }

    /** Zero denotes a native-protocol or already completed attempt. */
    public static long activeRoundId(UpgraderMenu menu) {
        return menu instanceof MenuState state ? state.ic2upgrader$getSpinRoundId() : 0;
    }

    public interface MenuState {
        long ic2upgrader$getSpinRoundId();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("ic2_upgrader_compat", path);
    }

    public record StartPayload(int containerId, long roundId, boolean success, float landingAngle,
            int durationTicks, float chance) implements CustomPacketPayload {
        public static final Type<StartPayload> TYPE = new Type<>(id("spin_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StartPayload> CODEC =
                CustomPacketPayload.codec(StartPayload::write, StartPayload::new);

        private StartPayload(RegistryFriendlyByteBuf buffer) {
            this(buffer.readVarInt(), buffer.readVarLong(), buffer.readBoolean(), buffer.readFloat(),
                    buffer.readVarInt(), buffer.readFloat());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(containerId);
            buffer.writeVarLong(roundId);
            buffer.writeBoolean(success);
            buffer.writeFloat(landingAngle);
            buffer.writeVarInt(durationTicks);
            buffer.writeFloat(chance);
        }

        @Override public Type<StartPayload> type() { return TYPE; }
    }

    public record FinishPayload(int containerId, long roundId, boolean success, boolean playSound,
            ItemStack result, long inputValue) implements CustomPacketPayload {
        public static final Type<FinishPayload> TYPE = new Type<>(id("spin_finish"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FinishPayload> CODEC =
                CustomPacketPayload.codec(FinishPayload::write, FinishPayload::new);

        public FinishPayload {
            result = result.copy();
        }

        private FinishPayload(RegistryFriendlyByteBuf buffer) {
            this(buffer.readVarInt(), buffer.readVarLong(), buffer.readBoolean(), buffer.readBoolean(),
                    ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer), buffer.readVarLong());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(containerId);
            buffer.writeVarLong(roundId);
            buffer.writeBoolean(success);
            buffer.writeBoolean(playSound);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, result);
            buffer.writeVarLong(inputValue);
        }

        @Override public ItemStack result() { return result.copy(); }
        @Override public Type<FinishPayload> type() { return TYPE; }
    }
}
