package art.arcane.satiscraftory.menu;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.ConstructorBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

public final class ConstructorMenu extends AbstractContainerMenu {
    private final ConstructorBlockEntity constructor;
    private final ContainerData data;

    public ConstructorMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, (ConstructorBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()));
    }

    public ConstructorMenu(int id, Inventory inventory, ConstructorBlockEntity constructor) {
        super(Satiscraftory.CONSTRUCTOR_MENU.get(), id);
        this.constructor = constructor;
        data = new ContainerData() {
            private final int[] clientValues = new int[2];

            @Override
            public int get(int index) {
                if (inventory.player.level().isClientSide) {
                    return clientValues[index];
                }
                return index == 0 ? constructor.powerStored() : constructor.workCredit();
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
        addSlot(new SlotItemHandler(constructor.inputInventory(), 0, 18, 35) {
            @Override
            public int getMaxStackSize(ItemStack stack) {
                // Forge's default implementation temporarily changes the inventory to probe capacity.
                return Math.min(getMaxStackSize(), stack.getMaxStackSize());
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 47 + column * 18, 151 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 47 + column * 18, 209));
        }
    }

    public ConstructorBlockEntity constructor() {
        return constructor;
    }

    public int energy() {
        return data.get(0);
    }

    public int workCredit() {
        return data.get(1);
    }

    @Override
    public boolean stillValid(Player player) {
        return !constructor.isRemoved() && stillValid(ContainerLevelAccess.create(constructor.getLevel(), constructor.getBlockPos()),
                player, Satiscraftory.CONSTRUCTOR.get());
    }

    @Override
    public boolean clickMenuButton(Player player, int index) {
        if (!stillValid(player)) {
            return false;
        }
        var choices = constructor.choices();
        return index >= 0 && index < choices.size() && constructor.selectRecipe(choices.get(index).id());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean moved = index == 0 ? moveItemStackTo(stack, 1, slots.size(), true) : moveItemStackTo(stack, 0, 1, false);
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
