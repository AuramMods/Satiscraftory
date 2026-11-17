package art.arcane.satiscraftory.client;

import art.arcane.satiscraftory.menu.ManufacturerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class ManufacturerScreen extends CraftingMachineScreen<ManufacturerMenu> {
    public ManufacturerScreen(ManufacturerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
