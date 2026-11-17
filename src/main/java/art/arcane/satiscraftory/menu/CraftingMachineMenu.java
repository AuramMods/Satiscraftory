package art.arcane.satiscraftory.menu;

import art.arcane.satiscraftory.block.entity.CraftingMachineBlockEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

public class CraftingMachineMenu extends AbstractContainerMenu {
    private final CraftingMachineBlockEntity machine;
    private final MenuIntData data;

    protected CraftingMachineMenu(MenuType<?> type, int id, Inventory inventory, CraftingMachineBlockEntity machine) {
        super(type, id);
        this.machine = machine;
        data = new MenuIntData(() -> inventory.player.level().isClientSide, machine::powerStored, machine::workCredit);
        addDataSlots(data);
        for (int slot = 0; slot < machine.spec().inputSlots(); slot++) {
            addSlot(new SlotItemHandler(machine.inputInventory(), slot, 18 + 20 * slot, 35) {
                @Override
                public int getMaxStackSize(ItemStack stack) {
                    // Forge's default implementation temporarily mutates the inventory to probe capacity.
                    return machine.inputLimit(stack);
                }

                @Override
                public ItemStack remove(int amount) {
                    // Keep carried stacks within the item's ordinary limit when taking from a machine buffer.
                    return super.remove(Math.min(amount, getItem().getMaxStackSize()));
                }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 47 + column * 18, 151 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 47 + column * 18, 209));
        }
    }

    public CraftingMachineBlockEntity machine() {
        return machine;
    }

    public int energy() {
        return data.value(0);
    }

    public int workCredit() {
        return data.value(1);
    }

    @Override
    public boolean stillValid(Player player) {
        return !machine.isRemoved() && stillValid(ContainerLevelAccess.create(machine.getLevel(), machine.getBlockPos()),
                player, machine.getBlockState().getBlock());
    }

    @Override
    public boolean clickMenuButton(Player player, int index) {
        if (!stillValid(player)) {
            return false;
        }
        var choices = machine.choices();
        return index >= 0 && index < choices.size() && machine.selectRecipe(choices.get(index).id());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int inputs = machine.spec().inputSlots();
        if (index >= inputs) {
            ItemStack remainder = stack.copy();
            for (int input = 0; input < inputs && !remainder.isEmpty(); input++) {
                remainder = machine.inputInventory().insertItem(input, remainder, false);
            }
            if (remainder.getCount() == original.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.setByPlayer(remainder);
            slot.onTake(player, original.copyWithCount(original.getCount() - remainder.getCount()));
            return original;
        }
        boolean moved = false;
        while (!stack.isEmpty()) {
            ItemStack transfer = stack.copyWithCount(Math.min(stack.getCount(), stack.getMaxStackSize()));
            int amount = transfer.getCount();
            if (!moveItemStackTo(transfer, inputs, slots.size(), true)) {
                break;
            }
            stack.shrink(amount - transfer.getCount());
            moved = true;
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        slot.onTake(player, stack);
        return original;
    }
}
