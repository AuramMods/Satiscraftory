package art.arcane.satiscraftory.client;

import art.arcane.satiscraftory.menu.AssemblerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class AssemblerScreen extends CraftingMachineScreen<AssemblerMenu> {
    public AssemblerScreen(AssemblerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
