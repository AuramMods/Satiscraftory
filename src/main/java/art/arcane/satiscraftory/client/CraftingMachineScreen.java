package art.arcane.satiscraftory.client;

import art.arcane.satiscraftory.menu.CraftingMachineMenu;
import art.arcane.satiscraftory.network.CraftingMachineNetwork;
import art.arcane.satiscraftory.recipe.CraftingMachineRecipes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

public class CraftingMachineScreen<T extends CraftingMachineMenu> extends AbstractContainerScreen<T> {
    private static final int ROW_Y = 78;
    private static final int ROW_HEIGHT = 18;
    private static final int VISIBLE_ROWS = 3;
    private List<CraftingMachineRecipes.Choice> choices = List.of();
    private ItemStack lastTemplate = ItemStack.EMPTY;
    private int scroll;
    private int refreshTicks;

    protected CraftingMachineScreen(T menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 256;
        imageHeight = 235;
        inventoryLabelX = 47;
        inventoryLabelY = 139;
        titleLabelX = 12;
        titleLabelY = 9;
    }

    public Rect2i recipeArea() {
        return new Rect2i(leftPos + 209, topPos + 34, 18, 18);
    }

    public void setRecipeTemplate(ItemStack stack) {
        CraftingMachineNetwork.setTemplate(menu.containerId, stack);
    }

    private void refreshRecipes() {
        ItemStack template = menu.machine().template();
        if (minecraft.level != null && (!ItemStack.isSameItemSameTags(template, lastTemplate) || refreshTicks >= 20)) {
            lastTemplate = template.copy();
            choices = CraftingMachineRecipes.find(minecraft.level, template, menu.machine().spec().inputSlots());
            scroll = Math.min(scroll, Math.max(0, choices.size() - VISIBLE_ROWS));
            refreshTicks = 0;
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        refreshTicks++;
        refreshRecipes();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        refreshRecipes();
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff362b20);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xffc78022);
        graphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xffefe6d4);
        for (var slot : menu.slots) {
            drawSlot(graphics, slot.x - 1, slot.y - 1);
        }
        drawSlot(graphics, 209, 34);
        graphics.drawString(font, Component.translatable("gui.satiscraftory.input"), leftPos + 15, topPos + 23, 0x49331f, false);
        graphics.drawString(font, Component.translatable("gui.satiscraftory.template"), leftPos + 199, topPos + 23, 0x49331f, false);
        boolean fourInputs = menu.machine().spec().inputSlots() > 2;
        int powerLeft = fourInputs ? 108 : 80;
        int powerWidth = 184 - powerLeft;
        graphics.fill(leftPos + powerLeft, topPos + 35, leftPos + 184, topPos + 45, 0xff594d3d);
        int capacity = menu.machine().spec().energyCapacity();
        int fill = (powerWidth - 2) * menu.energy() / capacity;
        graphics.fill(leftPos + powerLeft + 1, topPos + 36, leftPos + powerLeft + 1 + fill, topPos + 44, 0xffffba29);
        graphics.drawString(font, String.format(java.util.Locale.ROOT, "%,d / %,d RF", menu.energy(), capacity), leftPos + 80, topPos + (fourInputs ? 55 : 49), 0x49331f, false);
        graphics.drawString(font, Component.translatable("gui.satiscraftory.recipes", choices.size()), leftPos + 12, topPos + 66, 0x49331f, false);
        if (menu.workCredit() > 0) {
            graphics.drawString(font, Component.translatable("gui.satiscraftory.work_charge", menu.workCredit()), leftPos + 85, topPos + 66, 0x90500d, false);
        }
        ItemStack template = menu.machine().template();
        if (!template.isEmpty()) {
            graphics.renderItem(template, leftPos + 210, topPos + 35);
        }
        if (choices.isEmpty()) {
            Component message = Component.translatable(template.isEmpty() ? "gui.satiscraftory.choose_output" : "gui.satiscraftory.no_recipes_" + menu.machine().spec().inputSlots());
            graphics.drawCenteredString(font, message, leftPos + imageWidth / 2, topPos + ROW_Y + 20, 0x685037);
        }
        for (int row = 0; row < VISIBLE_ROWS && row + scroll < choices.size(); row++) {
            var choice = choices.get(row + scroll);
            int y = topPos + ROW_Y + row * ROW_HEIGHT;
            boolean selected = choice.id().equals(menu.machine().recipeId());
            graphics.fill(leftPos + 12, y, leftPos + 238, y + ROW_HEIGHT - 1, selected ? 0xffe4ab4c : 0xffd8cbb3);
            for (int inputIndex = 0; inputIndex < choice.inputs().size(); inputIndex++) {
                var input = choice.inputs().get(inputIndex);
                boolean compact = choice.inputs().size() > 2;
                int x = leftPos + 16 + (compact ? 40 : 80) * inputIndex;
                graphics.renderItem(input.stack(), x, y);
                String text = input.count() + "x" + (compact ? "" : " " + input.stack().getHoverName().getString());
                int width = choice.inputs().size() == 1 ? 132 : 56;
                graphics.drawString(font, font.plainSubstrByWidth(text, width), x + 20, y + 5, 0x392c1d, false);
            }
            graphics.drawString(font, "→", leftPos + 177, y + 5, 0x8b4c09, false);
            graphics.renderItem(choice.result(), leftPos + 194, y);
            graphics.drawString(font, Integer.toString(choice.result().getCount()), leftPos + 214, y + 5, 0x392c1d, false);
        }
        if (choices.size() > VISIBLE_ROWS) {
            int height = VISIBLE_ROWS * ROW_HEIGHT;
            graphics.fill(leftPos + 241, topPos + ROW_Y, leftPos + 244, topPos + ROW_Y + height, 0xffad9778);
            int offset = scroll * (height - 12) / (choices.size() - VISIBLE_ROWS);
            graphics.fill(leftPos + 241, topPos + ROW_Y + offset, leftPos + 244, topPos + ROW_Y + offset + 12, 0xffa35913);
        }
    }

    private void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(leftPos + x, topPos + y, leftPos + x + 18, topPos + y + 18, 0xff665542);
        graphics.fill(leftPos + x + 1, topPos + y + 1, leftPos + x + 17, topPos + y + 17, 0xffbba98e);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (recipeArea().contains(mouseX, mouseY)) {
            graphics.renderTooltip(font, List.of(Component.translatable("gui.satiscraftory.template_hint")), Optional.empty(), mouseX, mouseY);
        }
        int index = hoveredRecipe(mouseX, mouseY);
        if (index >= 0) {
            var choice = choices.get(index);
            var tooltip = new java.util.ArrayList<Component>();
            for (var input : choice.inputs()) {
                tooltip.add(Component.literal(input.count() + "x " + input.stack().getHoverName().getString()));
            }
            tooltip.add(Component.literal("→ " + choice.result().getCount() + "x " + choice.result().getHoverName().getString()));
            tooltip.add(Component.literal(choice.id().toString()));
            graphics.renderTooltip(font, tooltip, Optional.empty(), mouseX, mouseY);
        }
    }

    private int hoveredRecipe(double mouseX, double mouseY) {
        if (mouseX < leftPos + 12 || mouseX >= leftPos + 238 || mouseY < topPos + ROW_Y || mouseY >= topPos + ROW_Y + VISIBLE_ROWS * ROW_HEIGHT) {
            return -1;
        }
        int index = scroll + ((int) mouseY - topPos - ROW_Y) / ROW_HEIGHT;
        return index < choices.size() ? index : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (recipeArea().contains((int) mouseX, (int) mouseY)) {
            if (button == 1 || !menu.getCarried().isEmpty()) {
                setRecipeTemplate(button == 1 ? ItemStack.EMPTY : menu.getCarried());
            }
            return true;
        }
        int index = hoveredRecipe(mouseX, mouseY);
        if (button == 0 && index >= 0) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, index);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= leftPos + 12 && mouseX < leftPos + 245 && mouseY >= topPos + ROW_Y && mouseY < topPos + ROW_Y + VISIBLE_ROWS * ROW_HEIGHT) {
            scroll = Math.max(0, Math.min(Math.max(0, choices.size() - VISIBLE_ROWS), scroll - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
}
