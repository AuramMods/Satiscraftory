package art.arcane.satiscraftory.block;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.SmelterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

import javax.annotation.Nullable;

public final class SmelterBlock extends BaseEntityBlock implements ConveyorConnectable {
    public static final DirectionProperty INPUT_FACING = BlockStateProperties.HORIZONTAL_FACING;

    public SmelterBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(INPUT_FACING, Direction.NORTH));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(INPUT_FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public Direction getInputDirection(BlockState state) {
        return state.getValue(INPUT_FACING);
    }

    @Override
    public Direction getOutputDirection(BlockState state) {
        return getInputDirection(state).getOpposite();
    }

    @Override
    public BlockState withInputConnection(BlockState state, Direction direction) {
        return state.setValue(INPUT_FACING, direction);
    }

    @Override
    public BlockState withOutputConnection(BlockState state, Direction direction) {
        return withInputConnection(state, direction.getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return withInputConnection(state, rotation.rotate(getInputDirection(state)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(getInputDirection(state)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(INPUT_FACING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SmelterBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, Satiscraftory.SMELTER_BLOCK_ENTITY.get(), SmelterBlockEntity::serverTick);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock())) {
            if (level.getBlockEntity(pos) instanceof SmelterBlockEntity smelter) {
                smelter.dropContents();
            }
            super.onRemove(state, level, pos, next, moving);
        }
    }
}
