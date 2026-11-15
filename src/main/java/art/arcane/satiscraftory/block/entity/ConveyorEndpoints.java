package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.ConveyorBlock;
import art.arcane.satiscraftory.block.ConveyorEndBlock;
import art.arcane.satiscraftory.data.route.BeltHitbox;
import art.arcane.satiscraftory.data.route.BeltPortInsets;
import art.arcane.satiscraftory.data.route.BeltRoute;
import art.arcane.satiscraftory.data.route.BeltRouteMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Objects;

/** Owns endpoint data, route geometry, and the linked destination marker. */
final class ConveyorEndpoints {
    private static final String END_POS_TAG = "end_pos";
    private static final String END_FACING_TAG = "end_facing";
    private static final String ROUTE_MODE_TAG = "route_mode";

    private final ConveyorBlockEntity belt;
    @Nullable
    private BlockPos endPos;
    private Direction endFacing = Direction.NORTH;
    private BeltRouteMode routeMode = BeltRouteMode.SPLINE;
    private BeltRoute route;
    private BeltRoute visualRoute;
    private BeltRoute visualBaseRoute;
    private BeltPortInsets visualPortInsets;
    private long visualRouteTick = Long.MIN_VALUE;
    private BeltRoute hitboxRoute;
    private BeltHitbox hitbox;
    private Direction routeStartFacing;
    private long nextEndMarkerValidationTick = Long.MIN_VALUE;

    ConveyorEndpoints(ConveyorBlockEntity belt) {
        this.belt = belt;
    }

    @Nullable
    BlockPos getEndPos() {
        return endPos;
    }

    Direction getEndFacing() {
        return endFacing;
    }

    Direction getOutputSide() {
        return endFacing.getAxis().isHorizontal() ? endFacing : Direction.NORTH;
    }

    void set(BlockPos pos, Direction facing, BeltRouteMode mode) {
        endPos = pos.immutable();
        endFacing = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        routeMode = mode;
        route = null;
    }

    void save(CompoundTag tag) {
        if (endPos != null) {
            tag.put(END_POS_TAG, NbtUtils.writeBlockPos(endPos));
        }
        tag.putString(END_FACING_TAG, endFacing.getName());
        tag.putString(ROUTE_MODE_TAG, routeMode.id());
    }

    boolean load(CompoundTag tag) {
        nextEndMarkerValidationTick = Long.MIN_VALUE;
        BlockPos previousEndPos = endPos;
        Direction previousEndFacing = endFacing;
        BeltRouteMode previousMode = routeMode;
        endPos = tag.contains(END_POS_TAG, Tag.TAG_COMPOUND)
                ? NbtUtils.readBlockPos(tag.getCompound(END_POS_TAG))
                : null;
        if (tag.contains(END_FACING_TAG, Tag.TAG_STRING)) {
            Direction loaded = Direction.byName(tag.getString(END_FACING_TAG));
            if (loaded != null && loaded.getAxis().isHorizontal()) {
                endFacing = loaded;
            } else {
                endFacing = belt.getStartFacing();
            }
        } else {
            endFacing = belt.getStartFacing();
        }

        BeltRouteMode loadedMode = BeltRouteMode.byId(tag.getString(ROUTE_MODE_TAG));
        routeMode = loadedMode == null ? BeltRouteMode.SPLINE : loadedMode;
        if (!Objects.equals(previousEndPos, endPos) || previousEndFacing != endFacing || previousMode != routeMode) {
            route = null;
            return true;
        }
        return false;
    }

    BeltRoute getRoute() {
        Direction startFacing = belt.getStartFacing();
        if (route == null || routeStartFacing != startFacing) {
            BlockPos target = endPos == null ? belt.getBlockPos().relative(startFacing) : endPos;
            route = routeMode.create(belt.getBlockPos(), startFacing, target, getOutputSide());
            routeStartFacing = startFacing;
        }
        return route;
    }

    BeltHitbox getHitbox() {
        BeltRoute currentRoute = getVisualRoute();
        if (hitboxRoute != currentRoute) {
            hitbox = new BeltHitbox(currentRoute, ((ConveyorBlock) belt.getBlockState().getBlock()).getTier());
            hitboxRoute = currentRoute;
        }
        return hitbox;
    }

    BeltRoute getVisualRoute() {
        Level level = belt.getLevel();
        BeltRoute base = getRoute();
        if (level == null || endPos == null) {
            return base;
        }
        long now = level.getGameTime();
        if (visualBaseRoute != base || visualRouteTick != now) {
            BeltPortInsets insets = BeltPortInsets.measure(level, belt.getBlockPos(), routeStartFacing, endPos, getOutputSide());
            if (visualBaseRoute != base || !insets.equals(visualPortInsets)) {
                visualRoute = insets.extend(base, routeStartFacing, getOutputSide());
                visualBaseRoute = base;
                visualPortInsets = insets;
            }
            visualRouteTick = now;
        }
        return visualRoute;
    }

    void removeLinkedEndMarker() {
        Level level = belt.getLevel();
        if (level == null || level.isClientSide || endPos == null) {
            return;
        }
        removeLinkedEndMarkerAt(endPos);
    }

    void validateLinkedEndMarker(long gameTime) {
        Level level = belt.getLevel();
        if (level == null || level.isClientSide || endPos == null) {
            return;
        }
        if (nextEndMarkerValidationTick != Long.MIN_VALUE && gameTime < nextEndMarkerValidationTick) {
            return;
        }
        nextEndMarkerValidationTick = gameTime + 20L;
        ensureLinkedEndMarker();
    }

    void updateLinkedEndMarker(@Nullable BlockPos previousEndPos) {
        nextEndMarkerValidationTick = Long.MIN_VALUE;
        Level level = belt.getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        if (previousEndPos != null && !previousEndPos.equals(endPos)) {
            removeLinkedEndMarkerAt(previousEndPos);
        }
        ensureLinkedEndMarker();
    }

    private void ensureLinkedEndMarker() {
        Level level = belt.getLevel();
        if (level == null || level.isClientSide || endPos == null || !level.isLoaded(endPos)) {
            return;
        }

        Direction outputSide = getOutputSide();
        BlockState desiredState = Satiscraftory.CONVEYOR_END.get().defaultBlockState();
        if (desiredState.hasProperty(ConveyorEndBlock.FACING)) {
            desiredState = desiredState.setValue(ConveyorEndBlock.FACING, outputSide);
        }

        BlockState existingState = level.getBlockState(endPos);
        if (!existingState.is(Satiscraftory.CONVEYOR_END.get())) {
            if (!existingState.canBeReplaced()) {
                return;
            }
            if (!level.setBlock(endPos, desiredState, Block.UPDATE_ALL)) {
                return;
            }
        } else if (existingState.hasProperty(ConveyorEndBlock.FACING)
                && existingState.getValue(ConveyorEndBlock.FACING) != outputSide) {
            level.setBlock(endPos, existingState.setValue(ConveyorEndBlock.FACING, outputSide), Block.UPDATE_ALL);
        }

        BlockEntity endBlockEntity = level.getBlockEntity(endPos);
        if (endBlockEntity instanceof ConveyorEndBlockEntity conveyorEndBlockEntity) {
            conveyorEndBlockEntity.setMasterPos(belt.getBlockPos());
        }
    }

    private void removeLinkedEndMarkerAt(BlockPos markerPos) {
        Level level = belt.getLevel();
        if (level == null || level.isClientSide || !level.isLoaded(markerPos)) {
            return;
        }

        BlockState markerState = level.getBlockState(markerPos);
        if (!markerState.is(Satiscraftory.CONVEYOR_END.get())) {
            return;
        }

        BlockEntity markerBlockEntity = level.getBlockEntity(markerPos);
        if (markerBlockEntity instanceof ConveyorEndBlockEntity conveyorEndBlockEntity) {
            BlockPos linkedMaster = conveyorEndBlockEntity.getMasterPos();
            if (linkedMaster != null && !belt.getBlockPos().equals(linkedMaster)) {
                return;
            }
            conveyorEndBlockEntity.suppressLinkedBreakOnce();
            conveyorEndBlockEntity.setMasterPos(null);
        }

        level.removeBlock(markerPos, false);
    }
}
