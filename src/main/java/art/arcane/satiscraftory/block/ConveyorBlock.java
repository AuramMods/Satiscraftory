package art.arcane.satiscraftory.block;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity;
import art.arcane.satiscraftory.data.ConveyorTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.extensions.common.IClientBlockExtensions;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;

public class ConveyorBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private final ConveyorTier tier;
    private final ResourceLocation sourceModelJson;

    public ConveyorBlock(BlockBehaviour.Properties properties, ConveyorTier tier, ResourceLocation sourceModelJson) {
        super(properties);
        this.tier = tier;
        this.sourceModelJson = sourceModelJson;
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public ConveyorTier getTier() {
        return tier;
    }

    public int getTravelTicksPerBlock() {
        return tier.getTravelTicksPerBlock();
    }

    public ResourceLocation getSourceModelJson() {
        return sourceModelJson;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public void initializeClient(Consumer<IClientBlockExtensions> consumer) {
        consumer.accept(art.arcane.satiscraftory.client.ConveyorBreakEffects.INSTANCE);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ConveyorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (level.isClientSide) {
            return createTickerHelper(blockEntityType, Satiscraftory.CONVEYOR_BLOCK_ENTITY.get(), ConveyorBlockEntity::clientTick);
        }
        return createTickerHelper(blockEntityType, Satiscraftory.CONVEYOR_BLOCK_ENTITY.get(), ConveyorBlockEntity::serverTick);
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                              @Nullable BlockEntity blockEntity, ItemStack tool) {
        Vec3 impact = blockEntity instanceof ConveyorBlockEntity belt ? belt.miningImpact(player) : null;
        if (!(level instanceof ServerLevel serverLevel) || impact == null) {
            super.playerDestroy(level, player, pos, state, blockEntity, tool);
            return;
        }

        player.awardStat(Stats.BLOCK_MINED.get(this));
        player.causeFoodExhaustion(0.005F);
        popResourcesAt(serverLevel, impact, getDrops(state, serverLevel, pos, blockEntity, player, tool));
        state.spawnAfterBreak(serverLevel, pos, tool, false);
    }

    public static void popResourcesAt(ServerLevel level, Vec3 location, List<ItemStack> drops) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS) || level.restoringBlockSnapshots) {
            return;
        }
        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                ItemEntity item = new ItemEntity(level, location.x, location.y + 0.05D, location.z, drop, 0, 0, 0);
                item.setDefaultPickUpDelay();
                level.addFreshEntity(item);
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof ConveyorBlockEntity splineEntity) {
                splineEntity.removeLinkedEndMarker();
                splineEntity.dropContents(level);
            }
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }
}
