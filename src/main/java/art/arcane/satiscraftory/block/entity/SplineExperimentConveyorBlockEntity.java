package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.SplineExperimentConveyorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

public class SplineExperimentConveyorBlockEntity extends BlockEntity {
    private static final String END_POS_TAG = "end_pos";
    private static final String END_FACING_TAG = "end_facing";

    @Nullable
    private BlockPos endPos;
    private Direction endFacing = Direction.NORTH;

    public SplineExperimentConveyorBlockEntity(BlockPos pos, BlockState blockState) {
        super(Satiscraftory.SPLINE_EXPERIMENT_CONVEYOR_BLOCK_ENTITY.get(), pos, blockState);
    }

    @Nullable
    public BlockPos getEndPos() {
        return endPos;
    }

    public Direction getEndFacing() {
        return endFacing;
    }

    public void setEndData(BlockPos endPos, Direction endFacing) {
        this.endPos = endPos.immutable();
        this.endFacing = endFacing.getAxis().isHorizontal() ? endFacing : Direction.NORTH;
        setChanged();
        syncToClient();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (endPos != null) {
            tag.put(END_POS_TAG, NbtUtils.writeBlockPos(endPos));
        }
        tag.putString(END_FACING_TAG, endFacing.getName());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        endPos = tag.contains(END_POS_TAG, Tag.TAG_COMPOUND)
                ? NbtUtils.readBlockPos(tag.getCompound(END_POS_TAG))
                : null;
        if (tag.contains(END_FACING_TAG, Tag.TAG_STRING)) {
            Direction loaded = Direction.byName(tag.getString(END_FACING_TAG));
            if (loaded != null && loaded.getAxis().isHorizontal()) {
                endFacing = loaded;
            } else {
                endFacing = fallbackFacing();
            }
        } else {
            endFacing = fallbackFacing();
        }
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            load(tag);
        }
    }

    @Override
    public AABB getRenderBoundingBox() {
        if (endPos == null) {
            return super.getRenderBoundingBox();
        }

        int minX = Math.min(worldPosition.getX(), endPos.getX());
        int minY = Math.min(worldPosition.getY(), endPos.getY());
        int minZ = Math.min(worldPosition.getZ(), endPos.getZ());
        int maxX = Math.max(worldPosition.getX(), endPos.getX()) + 1;
        int maxY = Math.max(worldPosition.getY(), endPos.getY()) + 1;
        int maxZ = Math.max(worldPosition.getZ(), endPos.getZ()) + 1;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(2.0D);
    }

    private void syncToClient() {
        if (level == null || level.isClientSide) {
            return;
        }

        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
    }

    private Direction fallbackFacing() {
        BlockState state = getBlockState();
        if (state.hasProperty(SplineExperimentConveyorBlock.FACING)) {
            return state.getValue(SplineExperimentConveyorBlock.FACING);
        }
        return Direction.NORTH;
    }
}
