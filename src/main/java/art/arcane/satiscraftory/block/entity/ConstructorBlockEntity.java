package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.menu.ConstructorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

public final class ConstructorBlockEntity extends CraftingMachineBlockEntity {
    public static final int ENERGY_CAPACITY = 5_000;
    public static final int ENERGY_PER_ITEM = 500;

    public ConstructorBlockEntity(BlockPos pos, BlockState state) {
        super(Satiscraftory.CONSTRUCTOR_BLOCK_ENTITY.get(), pos, state, CraftingMachineSpec.CONSTRUCTOR);
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ConstructorMenu(id, inventory, this);
    }
}
