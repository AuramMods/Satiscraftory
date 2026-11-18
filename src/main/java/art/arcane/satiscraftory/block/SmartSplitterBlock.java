package art.arcane.satiscraftory.block;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.SmartSplitterBlockEntity;
import art.arcane.satiscraftory.block.entity.SplitterBlockEntity;
import art.arcane.satiscraftory.item.ConveyorItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;

public final class SmartSplitterBlock extends SplitterBlock {
    public SmartSplitterBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SmartSplitterBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, Satiscraftory.SMART_SPLITTER_BLOCK_ENTITY.get(), SplitterBlockEntity::serverTick);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.getItemInHand(hand).getItem() instanceof ConveyorItem) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof SmartSplitterBlockEntity splitter) {
            NetworkHooks.openScreen(serverPlayer, splitter, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
