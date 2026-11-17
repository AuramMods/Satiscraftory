package art.arcane.satiscraftory.block;

import art.arcane.satiscraftory.Satiscraftory;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ConstructorBlock extends CraftingMachineBlock {
    public ConstructorBlock(BlockBehaviour.Properties properties) {
        super(properties, Satiscraftory.CONSTRUCTOR_BLOCK_ENTITY::get);
    }
}
