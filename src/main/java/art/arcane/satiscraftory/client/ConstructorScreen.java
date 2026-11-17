package art.arcane.satiscraftory.client;

import art.arcane.satiscraftory.menu.ConstructorMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class ConstructorScreen extends CraftingMachineScreen<ConstructorMenu> {
    public ConstructorScreen(ConstructorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
