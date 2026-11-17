package art.arcane.satiscraftory.block;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.ConstructorBlockEntity;
import art.arcane.satiscraftory.item.ConveyorItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;

public final class ConstructorBlock extends BaseEntityBlock implements ConveyorConnectable {
    public static final DirectionProperty OUTPUT_FACING = BlockStateProperties.HORIZONTAL_FACING;

    public ConstructorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(OUTPUT_FACING, Direction.NORTH));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(OUTPUT_FACING, context.getHorizontalDirection());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public Direction getInputDirection(BlockState state) {
        return getOutputDirection(state).getOpposite();
    }

    @Override
    public Direction getOutputDirection(BlockState state) {
        return state.getValue(OUTPUT_FACING);
    }

    @Override
    public boolean acceptsInput(BlockState state, Direction side) {
        return side != getOutputDirection(state);
    }

    @Override
    public BlockState withInputConnection(BlockState state, Direction side) {
        return withOutputConnection(state, side.getOpposite());
    }

    @Override
    public BlockState withOutputConnection(BlockState state, Direction side) {
        return state.setValue(OUTPUT_FACING, side);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return withOutputConnection(state, rotation.rotate(getOutputDirection(state)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(getOutputDirection(state)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OUTPUT_FACING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ConstructorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, Satiscraftory.CONSTRUCTOR_BLOCK_ENTITY.get(), ConstructorBlockEntity::serverTick);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.getItemInHand(hand).getItem() instanceof ConveyorItem) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof ConstructorBlockEntity constructor) {
            NetworkHooks.openScreen(serverPlayer, constructor, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock())) {
            if (level.getBlockEntity(pos) instanceof ConstructorBlockEntity constructor) {
                constructor.dropContents();
            }
            super.onRemove(state, level, pos, next, moving);
        }
    }
}
