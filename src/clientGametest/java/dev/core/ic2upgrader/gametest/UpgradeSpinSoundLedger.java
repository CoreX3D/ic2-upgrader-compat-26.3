package dev.core.ic2upgrader.gametest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.execheinz.upgrader.client.UpgraderScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.item.ItemStack;

/** Test-only observation of real terminal SoundManager requests, including their atomic UI state. */
public final class UpgradeSpinSoundLedger {
    private static final List<Event> events = new ArrayList<>();
    private static boolean active;

    private UpgradeSpinSoundLedger() { }

    public record Event(String identifier, boolean relative, Boolean spinning, ItemStack input) { }

    public static synchronized void begin() {
        events.clear();
        active = true;
    }

    public static synchronized void stop() {
        active = false;
        events.clear();
    }

    public static synchronized List<Event> snapshot() { return List.copyOf(events); }

    public static synchronized void record(SoundInstance sound) {
        if (!active) return;
        String id = sound.getIdentifier().toString();
        if (!id.equals("minecraft:entity.player.levelup") && !id.startsWith("minecraft:block.anvil.")) return;
        Boolean spinning = null;
        ItemStack input = ItemStack.EMPTY;
        if (Minecraft.getInstance().gui.screen() instanceof UpgraderScreen screen) {
            try {
                Field field = UpgraderScreen.class.getDeclaredField("spinning");
                field.setAccessible(true);
                spinning = field.getBoolean(screen);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Could not observe actual wheel state when terminal audio played", exception);
            }
            input = screen.getMenu().getInputStack().copy();
        }
        events.add(new Event(id, sound.isRelative(), spinning, input));
    }
}
