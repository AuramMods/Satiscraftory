package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.ConveyorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class ConveyorEndBlockEntity extends BlockEntity {
    private static final String MASTER_POS_TAG = "master_pos";

    @Nullable
    private BlockPos masterPos;
    private boolean suppressLinkedBreak;

    public ConveyorEndBlockEntity(BlockPos pos, BlockState blockState) {
        super(Satiscraftory.CONVEYOR_END_BLOCK_ENTITY.get(), pos, blockState);
    }

    @Nullable
    public BlockPos getMasterPos() {
        return masterPos;
    }

    public void setMasterPos(@Nullable BlockPos masterPos) {
        BlockPos next = masterPos == null ? null : masterPos.immutable();
        if (next == null ? this.masterPos == null : next.equals(this.masterPos)) {
            return;
        }
        this.masterPos = next;
        setChanged();
    }

    public void suppressLinkedBreakOnce() {
        suppressLinkedBreak = true;
    }

    public void onEndBlockRemoved(Level level, BlockPos pos) {
        if (suppressLinkedBreak) {
            suppressLinkedBreak = false;
            return;
        }

        BlockPos master = masterPos;
        if (master == null || !level.isLoaded(master)) {
            return;
        }

        BlockState masterState = level.getBlockState(master);
        if (masterState.getBlock() instanceof ConveyorBlock) {
            level.destroyBlock(master, true);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (masterPos != null) {
            tag.put(MASTER_POS_TAG, NbtUtils.writeBlockPos(masterPos));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        masterPos = tag.contains(MASTER_POS_TAG, Tag.TAG_COMPOUND)
                ? NbtUtils.readBlockPos(tag.getCompound(MASTER_POS_TAG))
                : null;
        suppressLinkedBreak = false;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ConveyorEndBlockEntity endBlockEntity) {
        if (level.isClientSide) {
            return;
        }
        BlockPos master = endBlockEntity.masterPos;
        if (master == null) {
            return;
        }
        if (!level.isLoaded(master) || !(level.getBlockState(master).getBlock() instanceof ConveyorBlock)) {
            endBlockEntity.suppressLinkedBreakOnce();
            level.removeBlock(pos, false);
        }
    }
}
