package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.menu.ManufacturerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

public final class ManufacturerBlockEntity extends CraftingMachineBlockEntity {
    public static final int ENERGY_CAPACITY = 40_000;
    public static final int ENERGY_PER_CRAFT = 4_000;

    public ManufacturerBlockEntity(BlockPos pos, BlockState state) {
        super(Satiscraftory.MANUFACTURER_BLOCK_ENTITY.get(), pos, state, CraftingMachineSpec.MANUFACTURER);
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ManufacturerMenu(id, inventory, this);
    }
}
