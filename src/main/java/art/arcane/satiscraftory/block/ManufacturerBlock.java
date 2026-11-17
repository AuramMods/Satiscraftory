package art.arcane.satiscraftory.block;

import art.arcane.satiscraftory.Satiscraftory;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ManufacturerBlock extends CraftingMachineBlock {
    public ManufacturerBlock(BlockBehaviour.Properties properties) {
        super(properties, Satiscraftory.MANUFACTURER_BLOCK_ENTITY::get);
    }
}
