package art.arcane.satiscraftory.client;

import art.arcane.satiscraftory.data.filter.SmartSplitterFilters;
import art.arcane.satiscraftory.data.filter.SplitterFilterMode;
import art.arcane.satiscraftory.data.filter.SplitterOutput;
import art.arcane.satiscraftory.menu.SmartSplitterMenu;
import art.arcane.satiscraftory.network.SmartSplitterNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class SmartSplitterScreen extends AbstractContainerScreen<SmartSplitterMenu> implements GhostItemScreen {
    private final Button[] modeButtons = new Button[SplitterOutput.values().length];

    public SmartSplitterScreen(SmartSplitterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 284;
        imageHeight = 236;
        titleLabelX = 12;
        titleLabelY = 9;
        inventoryLabelX = 61;
        inventoryLabelY = 138;
    }

    private int columnX(SplitterOutput output) {
        return leftPos + 12 + 88 * output.ordinal();
    }

    public Rect2i filterArea(SplitterOutput output, int slot) {
        return new Rect2i(columnX(output) + 13 + (slot % 3) * 18, topPos + 64 + (slot / 3) * 18, 18, 18);
    }

    private Component modeLabel(SplitterOutput output) {
        return Component.translatable("gui.satiscraftory.filter_mode." + menu.splitter().mode(output).name().toLowerCase(Locale.ROOT));
    }

    @Override
    protected void init() {
        super.init();
        for (SplitterOutput output : SplitterOutput.values()) {
            modeButtons[output.ordinal()] = addRenderableWidget(Button.builder(modeLabel(output),
                    button -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, output.ordinal()))
                    .bounds(columnX(output), topPos + 37, 80, 20).build());
        }
    }

    @Override
    public List<GhostTarget> ghostTargets() {
        List<GhostTarget> targets = new ArrayList<>();
        for (SplitterOutput output : SplitterOutput.values()) {
            for (int slot = 0; slot < SmartSplitterFilters.FILTER_SLOTS; slot++) {
                int index = slot;
                targets.add(new GhostTarget(filterArea(output, slot),
                        stack -> SmartSplitterNetwork.setFilter(menu.containerId, output, index, stack)));
            }
        }
        return targets;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff362b20);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xffc78022);
        graphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xffefe6d4);
        for (var slot : menu.slots) {
            drawSlot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1, true);
        }
        for (SplitterOutput output : SplitterOutput.values()) {
            graphics.drawCenteredString(font, Component.translatable("gui.satiscraftory.output." + output.name().toLowerCase(Locale.ROOT)),
                    columnX(output) + 40, topPos + 24, 0x49331f);
            modeButtons[output.ordinal()].setMessage(modeLabel(output));
            SplitterFilterMode mode = menu.splitter().mode(output);
            boolean active = mode == SplitterFilterMode.WHITELIST || mode == SplitterFilterMode.BLACKLIST;
            for (int slot = 0; slot < SmartSplitterFilters.FILTER_SLOTS; slot++) {
                Rect2i area = filterArea(output, slot);
                drawSlot(graphics, area.getX(), area.getY(), active);
                graphics.renderItem(menu.splitter().template(output, slot), area.getX() + 1, area.getY() + 1);
            }
        }
        graphics.drawCenteredString(font, Component.translatable("gui.satiscraftory.ghost_filters"),
                leftPos + imageWidth / 2, topPos + 124, 0x685037);
    }

    private void drawSlot(GuiGraphics graphics, int x, int y, boolean active) {
        graphics.fill(x, y, x + 18, y + 18, 0xff665542);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, active ? 0xffbba98e : 0xffd7cdbb);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 || button == 1) {
            for (GhostTarget target : ghostTargets()) {
                if (target.area().contains((int) mouseX, (int) mouseY)) {
                    // The carried stack is only a template; both empty-cursor buttons clear the slot.
                    target.accept(menu.getCarried());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        for (SplitterOutput output : SplitterOutput.values()) {
            for (int slot = 0; slot < SmartSplitterFilters.FILTER_SLOTS; slot++) {
                if (filterArea(output, slot).contains(mouseX, mouseY)) {
                    List<Component> tooltip = new ArrayList<>();
                    ItemStack stack = menu.splitter().template(output, slot);
                    if (!stack.isEmpty()) {
                        tooltip.add(stack.getHoverName());
                    }
                    tooltip.add(Component.translatable("gui.satiscraftory.filter_set_hint"));
                    tooltip.add(Component.translatable("gui.satiscraftory.filter_clear_hint"));
                    graphics.renderTooltip(font, tooltip, Optional.empty(), mouseX, mouseY);
                }
            }
        }
    }
}
