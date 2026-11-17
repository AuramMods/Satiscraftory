package art.arcane.satiscraftory.menu;

import art.arcane.satiscraftory.block.entity.CraftingMachineBlockEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

public class CraftingMachineMenu extends AbstractContainerMenu {
    private final CraftingMachineBlockEntity machine;
    private final ContainerData data;

    protected CraftingMachineMenu(MenuType<?> type, int id, Inventory inventory, CraftingMachineBlockEntity machine) {
        super(type, id);
        this.machine = machine;
        data = new ContainerData() {
            private final int[] clientValues = new int[2];

            @Override
            public int get(int index) {
                if (inventory.player.level().isClientSide) {
                    return clientValues[index];
                }
                return index == 0 ? machine.powerStored() : machine.workCredit();
            }

            @Override
            public void set(int index, int value) {
                clientValues[index] = value;
            }

            @Override
            public int getCount() {
                return 2;
            }
        };
        addDataSlots(data);
        for (int slot = 0; slot < machine.spec().inputSlots(); slot++) {
            addSlot(new SlotItemHandler(machine.inputInventory(), slot, 18 + 20 * slot, 35) {
                @Override
                public int getMaxStackSize(ItemStack stack) {
                    // Forge's default implementation temporarily mutates the inventory to probe capacity.
                    return Math.min(getMaxStackSize(), stack.getMaxStackSize());
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
        return data.get(0);
    }

    public int workCredit() {
        return data.get(1);
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
        boolean moved = index < inputs ? moveItemStackTo(stack, inputs, slots.size(), true)
                : moveItemStackTo(stack, 0, inputs, false);
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
