package art.arcane.satiscraftory.menu;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.ConstructorBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;

public final class ConstructorMenu extends CraftingMachineMenu {
    public ConstructorMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, (ConstructorBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()));
    }

    public ConstructorMenu(int id, Inventory inventory, ConstructorBlockEntity machine) {
        super(Satiscraftory.CONSTRUCTOR_MENU.get(), id, inventory, machine);
    }
}
