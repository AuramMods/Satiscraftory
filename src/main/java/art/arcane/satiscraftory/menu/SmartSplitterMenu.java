package art.arcane.satiscraftory.menu;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.SmartSplitterBlockEntity;
import art.arcane.satiscraftory.data.filter.SplitterOutput;
import art.arcane.satiscraftory.data.filter.SmartSplitterFilters;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class SmartSplitterMenu extends AbstractContainerMenu {
    private final SmartSplitterBlockEntity splitter;

    public SmartSplitterMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, (SmartSplitterBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()));
    }

    public SmartSplitterMenu(int id, Inventory inventory, SmartSplitterBlockEntity splitter) {
        super(Satiscraftory.SMART_SPLITTER_MENU.get(), id);
        this.splitter = splitter;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, 61 + column * 18, 150 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 61 + column * 18, 208));
        }
    }

    public SmartSplitterBlockEntity splitter() {
        return splitter;
    }

    @Override
    public boolean stillValid(Player player) {
        return !splitter.isRemoved() && stillValid(ContainerLevelAccess.create(splitter.getLevel(), splitter.getBlockPos()),
                player, Satiscraftory.SMART_SPLITTER.get());
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!stillValid(player) || id < 0 || id >= SplitterOutput.values().length) {
            return false;
        }
        splitter.cycleMode(SplitterOutput.values()[id]);
        return true;
    }

    public boolean setFilter(Player player, SplitterOutput output, int slot, ItemStack stack) {
        if (!stillValid(player) || slot < 0 || slot >= SmartSplitterFilters.FILTER_SLOTS) {
            return false;
        }
        splitter.setTemplate(output, slot, stack);
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // All machine slots are ghost templates; there is no destination for real inventory transfers.
        return ItemStack.EMPTY;
    }
}
