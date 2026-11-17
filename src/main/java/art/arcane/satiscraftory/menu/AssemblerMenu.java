package art.arcane.satiscraftory.menu;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.AssemblerBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;

public final class AssemblerMenu extends CraftingMachineMenu {
    public AssemblerMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, (AssemblerBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()));
    }

    public AssemblerMenu(int id, Inventory inventory, AssemblerBlockEntity machine) {
        super(Satiscraftory.ASSEMBLER_MENU.get(), id, inventory, machine);
    }
}
