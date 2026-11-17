package art.arcane.satiscraftory.menu;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.ManufacturerBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;

public final class ManufacturerMenu extends CraftingMachineMenu {
    public ManufacturerMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, (ManufacturerBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()));
    }

    public ManufacturerMenu(int id, Inventory inventory, ManufacturerBlockEntity machine) {
        super(Satiscraftory.MANUFACTURER_MENU.get(), id, inventory, machine);
    }
}
