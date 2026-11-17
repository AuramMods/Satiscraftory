package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.menu.AssemblerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

public final class AssemblerBlockEntity extends CraftingMachineBlockEntity {
    public static final int ENERGY_CAPACITY = 10_000;
    public static final int ENERGY_PER_CRAFT = 1_000;

    public AssemblerBlockEntity(BlockPos pos, BlockState state) {
        super(Satiscraftory.ASSEMBLER_BLOCK_ENTITY.get(), pos, state, CraftingMachineSpec.ASSEMBLER);
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new AssemblerMenu(id, inventory, this);
    }
}
