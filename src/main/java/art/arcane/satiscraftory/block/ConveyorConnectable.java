package art.arcane.satiscraftory.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

public interface ConveyorConnectable {
    Direction getInputDirection(BlockState state);

    Direction getOutputDirection(BlockState state);

    BlockState withInputConnection(BlockState state, Direction inputDirection);

    BlockState withOutputConnection(BlockState state, Direction outputDirection);
}
