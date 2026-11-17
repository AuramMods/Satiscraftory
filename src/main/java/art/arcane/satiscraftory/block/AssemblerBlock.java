package art.arcane.satiscraftory.block;

import art.arcane.satiscraftory.Satiscraftory;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class AssemblerBlock extends CraftingMachineBlock {
    public AssemblerBlock(BlockBehaviour.Properties properties) {
        super(properties, Satiscraftory.ASSEMBLER_BLOCK_ENTITY::get);
    }
}
