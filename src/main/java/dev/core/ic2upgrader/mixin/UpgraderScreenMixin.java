package dev.core.ic2upgrader.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.core.ic2upgrader.client.BookTargetClient;
import dev.core.ic2upgrader.client.CreativeCatalogCategories;
import dev.core.ic2upgrader.client.CreativeCatalogCategories.Category;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.execheinz.upgrader.client.UpgraderScreen;
import net.execheinz.upgrader.menu.UpgraderMenu;
import net.execheinz.upgrader.network.ServerboundSetTargetPacket;
import net.execheinz.upgrader.network.UpgraderNetwork;
import net.execheinz.upgrader.value.UpgradeOdds;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A modal, creative-style index into the server-authorized upgrade catalogue. */
@Mixin(value = UpgraderScreen.class, remap = false)
public abstract class UpgraderScreenMixin extends AbstractContainerScreen<UpgraderMenu> {
    @Unique private static final int IC2UPGRADER_BAR_X = 271;
    @Unique private static final int IC2UPGRADER_BAR_Y = 44;
    @Unique private static final int IC2UPGRADER_BAR_WIDTH = 8;
    @Unique private static final int IC2UPGRADER_BAR_HEIGHT = 162;
    @Unique private static final int IC2UPGRADER_MIN_THUMB = 16;
    @Unique private static final int IC2UPGRADER_COLUMNS = 14;
    @Unique private static final int IC2UPGRADER_VISIBLE_ROWS = 9;
    @Unique private static final int IC2UPGRADER_TABS_PER_ROW = 7;
    @Unique private static final int IC2UPGRADER_TABS_PER_PAGE = 14;
    @Unique private static final int IC2UPGRADER_TAB_X = 12;
    @Unique private static final int IC2UPGRADER_TAB_STEP = 32;
    @Unique private static final int IC2UPGRADER_TAB_WIDTH = 30;
    @Unique private static final int IC2UPGRADER_TAB_HEIGHT = 22;
    @Unique private static final int IC2UPGRADER_TOP_TABS_Y = -14;
    @Unique private static final int IC2UPGRADER_BOTTOM_TABS_Y = 232;
    @Unique private static final int IC2UPGRADER_PREVIOUS_X = 244;
    @Unique private static final int IC2UPGRADER_NEXT_X = 262;
    @Unique private static final int IC2UPGRADER_PAGE_BUTTON_Y = 214;
    @Unique private static final int IC2UPGRADER_PAGE_BUTTON_SIZE = 14;
    @Unique private static final int IC2UPGRADER_TEXT = 0xffe6e9f0;
    @Unique private static final int IC2UPGRADER_DIM = 0xff98a2b7;
    @Unique private static final int IC2UPGRADER_GOLD = 0xffefc879;
    @Unique private static final int IC2UPGRADER_BODY = 0xff141922;

    @Shadow private boolean pickerOpen;
    @Shadow private int scrollRow;
    @Shadow private List<ItemStack> catalog;
    @Shadow private List<ItemStack> filtered;
    @Shadow private EditBox searchBox;
    @Shadow private long inputValue;

    @Shadow private int maxScrollRow() { throw new AssertionError("Mixin shadow"); }
    @Shadow private void refilter() { throw new AssertionError("Mixin shadow"); }
    @Shadow private void closePicker() { throw new AssertionError("Mixin shadow"); }
    @Shadow private void playUi(SoundEvent event, float pitch) { throw new AssertionError("Mixin shadow"); }
    @Shadow private static String format(long value) { throw new AssertionError("Mixin shadow"); }

    @Unique private boolean ic2upgrader$dragging;
    @Unique private double ic2upgrader$grabOffset;
    @Unique private List<Category> ic2upgrader$categories = List.of();
    @Unique private String ic2upgrader$selectedCategory = "all";
    @Unique private int ic2upgrader$categoryPage;
    @Unique private List<ItemStack> ic2upgrader$sourceCatalog;
    @Unique private List<ItemStack> ic2upgrader$categoryCatalog = List.of();
    @Unique private long ic2upgrader$bookRevision = Long.MIN_VALUE;

    protected UpgraderScreenMixin(UpgraderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 288, 240);
    }

    @Inject(method = {"init", "openPicker", "closePicker"}, at = @At("HEAD"), remap = false)
    private void ic2upgrader$resetDrag(CallbackInfo callback) {
        ic2upgrader$stopDrag();
    }

    @Inject(method = {"init", "openPicker"}, at = @At("TAIL"), remap = false)
    private void ic2upgrader$restorePicker(CallbackInfo callback) {
        if (pickerOpen) refilter();
    }

    @Inject(method = "refilter", at = @At("HEAD"), cancellable = true, remap = false)
    private void ic2upgrader$filterCategory(CallbackInfo callback) {
        ic2upgrader$stopDrag();
        ic2upgrader$ensureCategories();
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        Category active = ic2upgrader$activeCategory();
        List<ItemStack> source = active == null ? ic2upgrader$categoryCatalog : active.items();
        filtered = query.isEmpty() ? source : source.stream()
                .filter(stack -> BookTargetClient.matches(stack, query)).toList();
        scrollRow = 0;
        callback.cancel();
    }

    // The original title and inventory captions belong to the covered station, not the picker.
    @Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true, remap = false)
    private void ic2upgrader$hideStationLabels(GuiGraphicsExtractor graphics, int mouseX,
                                             int mouseY, CallbackInfo callback) {
        if (pickerOpen) callback.cancel();
    }

    @Inject(method = "renderPicker", at = @At("HEAD"), cancellable = true, remap = false)
    private void ic2upgrader$renderCategories(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                            float partialTick, CallbackInfo callback) {
        if (ic2upgrader$ensureCategories()) refilter();
        int x = leftPos;
        int y = topPos;

        // Cover all station slots, including its hotbar, before drawing the modal surface.
        graphics.fill(x, y, x + 288, y + 240, 0xff10131c);
        graphics.fill(x + 8, y + 8, x + 280, y + 233, 0xff46516a);
        graphics.fill(x + 9, y + 9, x + 279, y + 232, IC2UPGRADER_BODY);
        graphics.fill(x + 13, y + 41, x + 280, y + 208, 0xff0b1019);

        Component hoveredTab = ic2upgrader$renderTabs(graphics, mouseX, mouseY);
        Category active = ic2upgrader$activeCategory();
        Component categoryTitle = active == null
                ? Component.translatable("gui.ic2_upgrader_compat.category.all") : active.title();
        graphics.text(font, ic2upgrader$fit(categoryTitle.getString(), 254),
                x + 14, y + 11, IC2UPGRADER_TEXT, false);
        searchBox.extractRenderState(graphics, mouseX, mouseY, partialTick);

        ItemStack hoveredItem = ItemStack.EMPTY;
        int first = scrollRow * IC2UPGRADER_COLUMNS;
        for (int slot = 0; slot < IC2UPGRADER_COLUMNS * IC2UPGRADER_VISIBLE_ROWS; slot++) {
            int cellX = x + 18 + slot % IC2UPGRADER_COLUMNS * 18;
            int cellY = y + 44 + slot / IC2UPGRADER_COLUMNS * 18;
            int index = first + slot;
            boolean populated = index < filtered.size();
            boolean hovered = populated && ic2upgrader$contains(mouseX, mouseY, cellX, cellY, 18, 18);
            graphics.fill(cellX, cellY, cellX + 18, cellY + 18,
                    hovered ? 0xff394354 : 0xff0e1520);
            if (hovered) {
                graphics.fill(cellX, cellY, cellX + 18, cellY + 1, 0xffba995a);
                graphics.fill(cellX, cellY + 17, cellX + 18, cellY + 18, 0xffba995a);
            }
            if (populated) {
                ItemStack stack = filtered.get(index);
                graphics.item(stack, cellX + 1, cellY + 1);
                if (hovered) hoveredItem = stack;
            }
        }
        if (filtered.isEmpty()) {
            graphics.centeredText(font, Component.translatable("gui.ic2_upgrader_compat.categories.empty"),
                    x + 144, y + 119, IC2UPGRADER_DIM);
        }

        ic2upgrader$renderScrollbar(graphics, mouseX, mouseY);
        Component footer = Component.translatable("gui.upgrader.picker_footer", filtered.size(),
                scrollRow + 1, maxScrollRow() + 1);
        graphics.text(font, ic2upgrader$fit(footer.getString(), 224),
                x + 14, y + 216, IC2UPGRADER_DIM, false);
        Component hoveredArrow = ic2upgrader$renderPageButtons(graphics, mouseX, mouseY);

        if (!hoveredItem.isEmpty()) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(BookTargetClient.name(hoveredItem));
            double price = BookTargetClient.value(hoveredItem);
            tooltip.add(Component.translatable("gui.upgrader.value", format(Math.round(price)))
                    .withStyle(ChatFormatting.GRAY));
            if (!menu.getInputStack().isEmpty()) {
                String chance = String.format(Locale.ROOT, "%.2f%%", UpgradeOdds.chance(inputValue, price) * 100);
                tooltip.add(Component.translatable("gui.upgrader.estimate", chance)
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            graphics.setTooltipForNextFrame(font, tooltip, Optional.empty(), mouseX, mouseY);
        } else if (hoveredTab != null || hoveredArrow != null) {
            graphics.setTooltipForNextFrame(font, hoveredTab != null ? hoveredTab : hoveredArrow, mouseX, mouseY);
        } else if (font.width(categoryTitle) > 254
                && ic2upgrader$contains(mouseX, mouseY, x + 14, y + 10, 254, 12)) {
            graphics.setTooltipForNextFrame(font, categoryTitle, mouseX, mouseY);
        }
        callback.cancel();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void ic2upgrader$clickPicker(MouseButtonEvent event, boolean doubleClick,
                                        CallbackInfoReturnable<Boolean> callback) {
        if (!pickerOpen) return;
        // Every button belongs to the modal. In particular, right/middle clicks never select a target.
        callback.setReturnValue(true);
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return;
        if (ic2upgrader$ensureCategories()) refilter();
        if (searchBox.mouseClicked(event, doubleClick)) {
            searchBox.setFocused(true);
            return;
        }
        searchBox.setFocused(false);

        for (int local = 0; local < IC2UPGRADER_TABS_PER_PAGE; local++) {
            int category = ic2upgrader$categoryPage * IC2UPGRADER_TABS_PER_PAGE + local;
            if (category >= ic2upgrader$categories.size()) break;
            if (ic2upgrader$contains(event.x(), event.y(), ic2upgrader$tabX(local),
                    ic2upgrader$tabY(local), IC2UPGRADER_TAB_WIDTH, IC2UPGRADER_TAB_HEIGHT)) {
                String id = ic2upgrader$categories.get(category).id();
                if (!id.equals(ic2upgrader$selectedCategory)) {
                    ic2upgrader$selectedCategory = id;
                    refilter();
                    ic2upgrader$clickSound();
                }
                return;
            }
        }
        for (int direction : new int[] {-1, 1}) {
            int buttonX = leftPos + (direction < 0 ? IC2UPGRADER_PREVIOUS_X : IC2UPGRADER_NEXT_X);
            if (ic2upgrader$contains(event.x(), event.y(), buttonX, topPos + IC2UPGRADER_PAGE_BUTTON_Y,
                    IC2UPGRADER_PAGE_BUTTON_SIZE, IC2UPGRADER_PAGE_BUTTON_SIZE)) {
                int next = Math.clamp(ic2upgrader$categoryPage + direction, 0, ic2upgrader$pageCount() - 1);
                if (next != ic2upgrader$categoryPage) {
                    ic2upgrader$categoryPage = next;
                    ic2upgrader$stopDrag();
                    ic2upgrader$clickSound();
                }
                return;
            }
        }
        if (ic2upgrader$contains(event.x(), event.y(), leftPos + IC2UPGRADER_BAR_X,
                topPos + IC2UPGRADER_BAR_Y, IC2UPGRADER_BAR_WIDTH, IC2UPGRADER_BAR_HEIGHT)) {
            if (maxScrollRow() > 0) {
                int thumbY = ic2upgrader$thumbY();
                int thumbHeight = ic2upgrader$thumbHeight();
                ic2upgrader$dragging = true;
                if (event.y() >= thumbY && event.y() < thumbY + thumbHeight) {
                    ic2upgrader$grabOffset = event.y() - thumbY;
                } else {
                    ic2upgrader$grabOffset = thumbHeight / 2.0;
                    ic2upgrader$scrollTo(event.y());
                }
            }
            return;
        }
        if (ic2upgrader$contains(event.x(), event.y(), leftPos + 18, topPos + 44, 252, 162)) {
            int column = (int) (event.x() - leftPos - 18) / 18;
            int row = (int) (event.y() - topPos - 44) / 18;
            int index = (scrollRow + row) * IC2UPGRADER_COLUMNS + column;
            if (index < filtered.size()) {
                ItemStack stack = filtered.get(index);
                if (!BookTargetClient.select(stack)) {
                    UpgraderNetwork.toServer(ServerboundSetTargetPacket.of(stack.getItem()));
                }
                ic2upgrader$clickSound();
                closePicker();
            }
            return;
        }
        // Empty space inside the new tabs/header keeps the picker open, outside closes it once.
        if (!ic2upgrader$contains(event.x(), event.y(), leftPos + 8, topPos - 14, 272, 268)) {
            closePicker();
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (pickerOpen) {
            if (ic2upgrader$dragging && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                ic2upgrader$scrollTo(event.y());
            } else {
                searchBox.mouseDragged(event, deltaX, deltaY);
            }
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (ic2upgrader$dragging || pickerOpen) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) ic2upgrader$stopDrag();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Unique
    private boolean ic2upgrader$ensureCategories() {
        long revision = BookTargetClient.revision();
        if (catalog == null || (catalog == ic2upgrader$sourceCatalog
                && revision == ic2upgrader$bookRevision && !ic2upgrader$categories.isEmpty())) return false;
        ic2upgrader$sourceCatalog = catalog;
        ic2upgrader$bookRevision = revision;
        ic2upgrader$categoryCatalog = BookTargetClient.augmentCatalog(catalog);
        ic2upgrader$categories = CreativeCatalogCategories.build(minecraft, ic2upgrader$categoryCatalog);
        if (ic2upgrader$activeCategory() == null) ic2upgrader$selectedCategory = "all";
        ic2upgrader$categoryPage = Math.clamp(ic2upgrader$categoryPage, 0, ic2upgrader$pageCount() - 1);
        return true;
    }

    @Unique
    private Category ic2upgrader$activeCategory() {
        for (Category category : ic2upgrader$categories) {
            if (category.id().equals(ic2upgrader$selectedCategory)) return category;
        }
        return null;
    }

    @Unique private int ic2upgrader$pageCount() {
        return Math.max(1, (ic2upgrader$categories.size() + IC2UPGRADER_TABS_PER_PAGE - 1)
                / IC2UPGRADER_TABS_PER_PAGE);
    }
    @Unique private int ic2upgrader$tabX(int local) {
        return leftPos + IC2UPGRADER_TAB_X + local % IC2UPGRADER_TABS_PER_ROW * IC2UPGRADER_TAB_STEP;
    }
    @Unique private int ic2upgrader$tabY(int local) {
        return topPos + (local < IC2UPGRADER_TABS_PER_ROW
                ? IC2UPGRADER_TOP_TABS_Y : IC2UPGRADER_BOTTOM_TABS_Y);
    }

    @Unique
    private Component ic2upgrader$renderTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Component hoveredTab = null;
        for (int local = 0; local < IC2UPGRADER_TABS_PER_PAGE; local++) {
            int index = ic2upgrader$categoryPage * IC2UPGRADER_TABS_PER_PAGE + local;
            if (index >= ic2upgrader$categories.size()) break;
            Category category = ic2upgrader$categories.get(index);
            int x = ic2upgrader$tabX(local);
            int y = ic2upgrader$tabY(local);
            boolean selected = category.id().equals(ic2upgrader$selectedCategory);
            boolean hovered = ic2upgrader$contains(mouseX, mouseY, x, y,
                    IC2UPGRADER_TAB_WIDTH, IC2UPGRADER_TAB_HEIGHT);
            int edge = selected ? IC2UPGRADER_GOLD : hovered ? 0xff929db2 : 0xff46516a;
            int face = selected ? 0xff34302b : hovered ? 0xff293345 : 0xff1b2332;
            graphics.fill(x, y, x + IC2UPGRADER_TAB_WIDTH, y + IC2UPGRADER_TAB_HEIGHT, edge);
            graphics.fill(x + 1, y + 1, x + IC2UPGRADER_TAB_WIDTH - 1,
                    y + IC2UPGRADER_TAB_HEIGHT - 1, face);
            if (selected) {
                int accentY = local < IC2UPGRADER_TABS_PER_ROW ? y : y + IC2UPGRADER_TAB_HEIGHT - 2;
                graphics.fill(x + 1, accentY, x + IC2UPGRADER_TAB_WIDTH - 1, accentY + 2,
                        IC2UPGRADER_GOLD);
                int joinY = local < IC2UPGRADER_TABS_PER_ROW ? y + IC2UPGRADER_TAB_HEIGHT - 1 : y;
                graphics.fill(x + 1, joinY, x + IC2UPGRADER_TAB_WIDTH - 1, joinY + 1, face);
            }
            graphics.item(category.icon(), x + 7, y + 3);
            if (hovered) hoveredTab = category.title();
        }
        Component page = Component.translatable("gui.ic2_upgrader_compat.categories.page",
                ic2upgrader$categoryPage + 1, ic2upgrader$pageCount());
        graphics.centeredText(font, page, leftPos + 260, topPos - 7, IC2UPGRADER_DIM);
        return hoveredTab;
    }

    @Unique
    private Component ic2upgrader$renderPageButtons(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Component tooltip = null;
        for (int direction : new int[] {-1, 1}) {
            int x = leftPos + (direction < 0 ? IC2UPGRADER_PREVIOUS_X : IC2UPGRADER_NEXT_X);
            int y = topPos + IC2UPGRADER_PAGE_BUTTON_Y;
            boolean enabled = direction < 0 ? ic2upgrader$categoryPage > 0
                    : ic2upgrader$categoryPage + 1 < ic2upgrader$pageCount();
            boolean hovered = ic2upgrader$contains(mouseX, mouseY, x, y,
                    IC2UPGRADER_PAGE_BUTTON_SIZE, IC2UPGRADER_PAGE_BUTTON_SIZE);
            graphics.fill(x, y, x + 14, y + 14, enabled && hovered ? 0xffb39153 : 0xff46516a);
            graphics.fill(x + 1, y + 1, x + 13, y + 13, enabled && hovered ? 0xff3b3326 : 0xff1b2332);
            graphics.centeredText(font, direction < 0 ? "<" : ">", x + 7, y + 3,
                    enabled ? IC2UPGRADER_GOLD : 0xff58647a);
            if (hovered) tooltip = Component.translatable(direction < 0
                    ? "gui.ic2_upgrader_compat.categories.previous" : "gui.ic2_upgrader_compat.categories.next");
        }
        return tooltip;
    }

    @Unique
    private void ic2upgrader$renderScrollbar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = leftPos + IC2UPGRADER_BAR_X;
        int y = topPos + IC2UPGRADER_BAR_Y;
        int width = IC2UPGRADER_BAR_WIDTH;
        int height = IC2UPGRADER_BAR_HEIGHT;
        boolean enabled = maxScrollRow() > 0;
        int thumbHeight = ic2upgrader$thumbHeight();
        int thumbY = ic2upgrader$thumbY();
        boolean hovered = ic2upgrader$contains(mouseX, mouseY, x, y, width, height);
        graphics.fill(x, y, x + width, y + height, hovered && enabled ? 0xff6c624f : 0xff343c50);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xff10131c);
        int edge = !enabled ? 0xff465066 : ic2upgrader$dragging ? 0xffffdda0
                : hovered ? 0xffeed39b : 0xffb39153;
        int face = !enabled ? 0xff2c3446 : ic2upgrader$dragging ? 0xffd0a659
                : hovered ? 0xffb48b47 : 0xff8c6b36;
        graphics.fill(x, thumbY, x + width, thumbY + thumbHeight, edge);
        graphics.fill(x + 1, thumbY + 1, x + width - 1, thumbY + thumbHeight - 1, face);
        if (enabled) {
            int middle = thumbY + thumbHeight / 2;
            graphics.fill(x + 2, middle - 2, x + width - 2, middle - 1, edge);
            graphics.fill(x + 2, middle + 1, x + width - 2, middle + 2, edge);
        }
    }

    @Unique private String ic2upgrader$fit(String text, int width) {
        return font.width(text) <= width ? text : font.plainSubstrByWidth(text, width - font.width("…")) + "…";
    }
    @Unique private void ic2upgrader$clickSound() {
        playUi(SoundEvents.UI_BUTTON_CLICK.value(), 1);
    }
    @Unique private void ic2upgrader$stopDrag() {
        ic2upgrader$dragging = false;
        ic2upgrader$grabOffset = 0;
    }
    @Unique private int ic2upgrader$thumbHeight() {
        int rows = Math.max(IC2UPGRADER_VISIBLE_ROWS,
                (filtered.size() + IC2UPGRADER_COLUMNS - 1) / IC2UPGRADER_COLUMNS);
        return Math.clamp((int) Math.round((double) IC2UPGRADER_BAR_HEIGHT
                * IC2UPGRADER_VISIBLE_ROWS / rows), IC2UPGRADER_MIN_THUMB, IC2UPGRADER_BAR_HEIGHT);
    }
    @Unique private int ic2upgrader$thumbY() {
        int max = maxScrollRow();
        int travel = IC2UPGRADER_BAR_HEIGHT - ic2upgrader$thumbHeight();
        return topPos + IC2UPGRADER_BAR_Y + (max == 0 ? 0
                : (int) Math.round((double) Math.clamp(scrollRow, 0, max) * travel / max));
    }
    @Unique private void ic2upgrader$scrollTo(double mouseY) {
        int max = maxScrollRow();
        int travel = IC2UPGRADER_BAR_HEIGHT - ic2upgrader$thumbHeight();
        if (max <= 0 || travel <= 0) {
            scrollRow = 0;
            return;
        }
        double position = (mouseY - topPos - IC2UPGRADER_BAR_Y - ic2upgrader$grabOffset) / travel;
        scrollRow = (int) Math.round(Math.clamp(position, 0.0, 1.0) * max);
    }
    @Unique private static boolean ic2upgrader$contains(double mouseX, double mouseY,
                                                       int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}

