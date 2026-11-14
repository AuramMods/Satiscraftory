package art.arcane.satiscraftory.item;

import art.arcane.satiscraftory.block.ConveyorBlock;
import art.arcane.satiscraftory.block.ConveyorConnectable;
import art.arcane.satiscraftory.block.ConveyorEndBlock;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity;
import art.arcane.satiscraftory.block.entity.MergerBlockEntity;
import art.arcane.satiscraftory.block.entity.SplitterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import javax.annotation.Nullable;

public class ConveyorItem extends BlockItem {
    private static final String PLACEMENT_STATE_TAG = "satiscraftory_spline_experiment_state";
    private static final String START_POS_TAG = "start_pos";
    private static final String START_FACING_TAG = "start_facing";
    private static final String START_DIMENSION_TAG = "start_dimension";
    public static final int MAX_SPLINE_LENGTH_BLOCKS = 16;
    private static final int LENGTH_SAMPLE_SEGMENTS = 80;
    private static final double EDGE_OFFSET = 0.5D;

    public ConveyorItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (getActivePlacement(player, level) == null) {
            return InteractionResultHolder.pass(stack);
        }

        clearPlacementState(player);
        if (!level.isClientSide) {
            player.displayClientMessage(Component.literal("Spline placement canceled."), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return super.useOn(context);
        }

        Level level = context.getLevel();
        PlacementState state = readPlacementState(player);

        if (state == null) {
            BlockPos startPos;
            Direction startFacing = null;
            if (player.isShiftKeyDown()) {
                Direction face = context.getClickedFace();
                BlockPos containerPos = context.getClickedPos();
                if (!face.getAxis().isHorizontal() || !hasContainerInventory(level, containerPos, face)) {
                    return InteractionResult.PASS;
                }

                startPos = containerPos.relative(face);
                if (!level.getBlockState(startPos).canBeReplaced()) {
                    if (!level.isClientSide) {
                        player.displayClientMessage(Component.literal("Spline start is blocked: " + formatPos(startPos)), true);
                    }
                    return InteractionResult.FAIL;
                }
                startFacing = face;
            } else {
                startPos = resolvePlacementPos(context);
            }

            storePlacementState(player, new PlacementState(startPos, level.dimension().location().toString(), startFacing));
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("Spline start set: " + formatPos(startPos)), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (!state.dimensionId.equals(level.dimension().location().toString())) {
            clearPlacementState(player);
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("Spline start cleared (dimension changed)."), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        BlockPos startPos = state.startPos;
        BlockPos selectedPos = resolvePlacementPos(context);
        BlockState targetState = level.getBlockState(startPos);
        boolean blockedStart = !targetState.canBeReplaced();

        Direction startFacing = state.resolveStartFacing(level, selectedPos, player.getDirection());
        Direction endFacing = resolveEndFacing(level, startPos, selectedPos, player.getDirection());
        int estimatedLength = estimateSplineLengthBlocks(startPos, startFacing, selectedPos, endFacing);
        boolean overMaxLength = estimatedLength > MAX_SPLINE_LENGTH_BLOCKS;

        if (level.isClientSide) {
            // Keep the hologram active on rejected placements so the player can adjust the endpoint.
            if (!blockedStart && !overMaxLength) {
                clearPlacementState(player);
            }
            return InteractionResult.SUCCESS;
        }

        if (blockedStart) {
            player.displayClientMessage(Component.literal("Spline start is blocked: " + formatPos(startPos)), true);
            return InteractionResult.FAIL;
        }

        if (overMaxLength) {
            player.displayClientMessage(
                    Component.literal("Spline too long (" + estimatedLength + " > " + MAX_SPLINE_LENGTH_BLOCKS + " blocks)."),
                    true
            );
            return InteractionResult.FAIL;
        }

        BlockState placeState = getBlock().defaultBlockState();
        if (placeState.hasProperty(ConveyorBlock.FACING)) {
            placeState = placeState.setValue(ConveyorBlock.FACING, startFacing);
        }

        if (!level.setBlock(startPos, placeState, Block.UPDATE_ALL)) {
            player.displayClientMessage(Component.literal("Failed to place spline start block."), true);
            return InteractionResult.FAIL;
        }

        BlockEntity blockEntity = level.getBlockEntity(startPos);
        if (blockEntity instanceof ConveyorBlockEntity splineBlockEntity) {
            splineBlockEntity.setEndData(selectedPos, endFacing);
        }

        playPlaceSound(level, startPos, placeState);
        level.gameEvent(player, GameEvent.BLOCK_PLACE, startPos);

        ItemStack stack = context.getItemInHand();
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }

        clearPlacementState(player);
        player.displayClientMessage(Component.literal("Spline endpoint set: " + formatPos(selectedPos)), true);
        return InteractionResult.SUCCESS;
    }

    private static void playPlaceSound(Level level, BlockPos pos, BlockState state) {
        SoundType soundType = state.getSoundType(level, pos, null);
        level.playSound(
                null,
                pos,
                soundType.getPlaceSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F
        );
    }

    private static BlockPos resolvePlacementPos(UseOnContext context) {
        BlockPlaceContext placeContext = new BlockPlaceContext(context);
        BlockPos clickedPos = placeContext.getClickedPos();
        BlockState clickedState = context.getLevel().getBlockState(clickedPos);
        if (clickedState.canBeReplaced(placeContext)) {
            return clickedPos;
        }
        return clickedPos.relative(placeContext.getClickedFace());
    }

    private static boolean hasContainerInventory(Level level, BlockPos pos, Direction face) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null || entity instanceof ConveyorBlockEntity
                || entity instanceof SplitterBlockEntity || entity instanceof MergerBlockEntity) {
            return false;
        }

        return entity.getCapability(ForgeCapabilities.ITEM_HANDLER, face).isPresent()
                || entity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent();
    }

    @Nullable
    private static PlacementState readPlacementState(Player player) {
        CompoundTag persistentData = player.getPersistentData();
        if (!persistentData.contains(PLACEMENT_STATE_TAG, Tag.TAG_COMPOUND)) {
            return null;
        }

        CompoundTag placementData = persistentData.getCompound(PLACEMENT_STATE_TAG);
        if (!placementData.contains(START_POS_TAG, Tag.TAG_COMPOUND)
                || !placementData.contains(START_DIMENSION_TAG, Tag.TAG_STRING)) {
            return null;
        }

        BlockPos startPos = NbtUtils.readBlockPos(placementData.getCompound(START_POS_TAG));
        String dimensionId = placementData.getString(START_DIMENSION_TAG);
        Direction startFacing = Direction.byName(placementData.getString(START_FACING_TAG));
        if (startFacing != null && !startFacing.getAxis().isHorizontal()) {
            startFacing = null;
        }
        return new PlacementState(startPos, dimensionId, startFacing);
    }

    private static void storePlacementState(Player player, PlacementState state) {
        CompoundTag placementData = new CompoundTag();
        placementData.put(START_POS_TAG, NbtUtils.writeBlockPos(state.startPos));
        placementData.putString(START_DIMENSION_TAG, state.dimensionId);
        if (state.startFacing != null) {
            placementData.putString(START_FACING_TAG, state.startFacing.getName());
        }
        player.getPersistentData().put(PLACEMENT_STATE_TAG, placementData);
    }

    private static void clearPlacementState(Player player) {
        player.getPersistentData().remove(PLACEMENT_STATE_TAG);
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    @Nullable
    public static PlacementState getActivePlacement(Player player, Level level) {
        PlacementState state = readPlacementState(player);
        if (state == null || !state.dimensionId.equals(level.dimension().location().toString())) {
            return null;
        }
        return state;
    }

    public static BlockPos resolvePreviewEndPos(Level level, BlockPos clickedPos, Direction clickedFace) {
        BlockState clickedState = level.getBlockState(clickedPos);
        if (clickedState.canBeReplaced()) {
            return clickedPos;
        }
        return clickedPos.relative(clickedFace);
    }

    public static Direction resolveDominantFacing(BlockPos startPos, BlockPos endPos, Direction fallback) {
        int dx = endPos.getX() - startPos.getX();
        int dz = endPos.getZ() - startPos.getZ();
        if (dx == 0 && dz == 0) {
            return fallback.getAxis().isHorizontal() ? fallback : Direction.NORTH;
        }
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    public static Direction resolveStartFacing(Level level, BlockPos startPos, BlockPos endPos, Direction fallback) {
        Direction dominant = resolveDominantFacing(startPos, endPos, fallback);
        Direction preferredSourceDirection = dominant.getOpposite();
        Direction sourceDirection = chooseNeighborDirection(level, startPos, preferredSourceDirection);
        if (sourceDirection != null) {
            return sourceDirection.getOpposite();
        }
        return dominant;
    }

    public static Direction resolveEndFacing(Level level, BlockPos startPos, BlockPos endPos, Direction fallback) {
        Direction dominant = resolveDominantFacing(startPos, endPos, fallback);
        Direction sinkDirection = chooseNeighborDirection(level, endPos, dominant);
        if (sinkDirection != null) {
            return sinkDirection;
        }
        return dominant;
    }

    public static int estimateSplineLengthBlocks(BlockPos startPos, Direction startFacing, BlockPos endPos, Direction endFacing) {
        Direction safeStartFacing = startFacing.getAxis().isHorizontal() ? startFacing : Direction.NORTH;
        Direction safeEndFacing = endFacing.getAxis().isHorizontal() ? endFacing : Direction.NORTH;

        Vec3 start = anchorToEdge(new Vec3(
                startPos.getX() + 0.5D,
                startPos.getY(),
                startPos.getZ() + 0.5D
        ), safeStartFacing.getOpposite());

        Vec3 end = anchorToEdge(new Vec3(
                endPos.getX() + 0.5D,
                endPos.getY(),
                endPos.getZ() + 0.5D
        ), safeEndFacing);

        Vec3 startForward = directionVector(safeStartFacing);
        Vec3 endForward = directionVector(safeEndFacing);

        Vec3 flatDelta = new Vec3(end.x - start.x, 0.0D, end.z - start.z);
        double horizontalDistance = Math.sqrt(flatDelta.lengthSqr());
        double tangentLength = Math.max(0.85D, Math.min(4.0D, horizontalDistance * 0.45D));

        Vec3 c1 = start.add(startForward.scale(tangentLength));
        Vec3 c2 = end.subtract(endForward.scale(tangentLength));

        Vec3 previous = sampleBezier(start, c1, c2, end, 0.0D);
        double arcLength = 0.0D;
        for (int i = 1; i <= LENGTH_SAMPLE_SEGMENTS; i++) {
            double t = i / (double) LENGTH_SAMPLE_SEGMENTS;
            Vec3 current = sampleBezier(start, c1, c2, end, t);
            arcLength += current.distanceTo(previous);
            previous = current;
        }

        if (!Double.isFinite(arcLength)) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(arcLength));
    }

    @Nullable
    private static Direction chooseNeighborDirection(Level level, BlockPos centerPos, Direction preferredDirection) {
        Direction best = null;
        int bestScore = Integer.MAX_VALUE;

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = centerPos.relative(direction);
            if (!isConnectionCandidate(level, neighborPos)) {
                continue;
            }

            int score = directionTurnCost(direction, preferredDirection);
            if (score < bestScore) {
                bestScore = score;
                best = direction;
                continue;
            }
            if (score == bestScore && best != null && directionPriority(direction) < directionPriority(best)) {
                best = direction;
            }
        }

        return best;
    }

    private static boolean isConnectionCandidate(Level level, BlockPos neighborPos) {
        BlockState state = level.getBlockState(neighborPos);
        if (state.getBlock() instanceof ConveyorConnectable
                || state.getBlock() instanceof ConveyorBlock
                || state.getBlock() instanceof ConveyorEndBlock) {
            return true;
        }

        BlockEntity blockEntity = level.getBlockEntity(neighborPos);
        if (blockEntity == null) {
            return false;
        }

        if (blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent()) {
            return true;
        }

        for (Direction side : Direction.values()) {
            if (blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).isPresent()) {
                return true;
            }
        }

        return false;
    }

    private static int directionTurnCost(Direction direction, Direction preferredDirection) {
        if (direction == preferredDirection) {
            return 0;
        }
        if (direction == preferredDirection.getClockWise() || direction == preferredDirection.getCounterClockWise()) {
            return 1;
        }
        return 2;
    }

    private static int directionPriority(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 99;
        };
    }

    private static Vec3 sampleBezier(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double oneMinus = 1.0D - t;
        double oneMinus2 = oneMinus * oneMinus;
        double oneMinus3 = oneMinus2 * oneMinus;
        double t2 = t * t;
        double t3 = t2 * t;
        return new Vec3(
                (p0.x * oneMinus3) + (3.0D * p1.x * oneMinus2 * t) + (3.0D * p2.x * oneMinus * t2) + (p3.x * t3),
                (p0.y * oneMinus3) + (3.0D * p1.y * oneMinus2 * t) + (3.0D * p2.y * oneMinus * t2) + (p3.y * t3),
                (p0.z * oneMinus3) + (3.0D * p1.z * oneMinus2 * t) + (3.0D * p2.z * oneMinus * t2) + (p3.z * t3)
        );
    }

    private static Vec3 anchorToEdge(Vec3 center, Direction direction) {
        return center.add(direction.getStepX() * EDGE_OFFSET, 0.0D, direction.getStepZ() * EDGE_OFFSET);
    }

    private static Vec3 directionVector(Direction direction) {
        if (direction.getAxis().isHorizontal()) {
            return new Vec3(direction.getStepX(), 0.0D, direction.getStepZ());
        }
        return new Vec3(0.0D, 0.0D, -1.0D);
    }

    public record PlacementState(BlockPos startPos, String dimensionId, @Nullable Direction startFacing) {
        public Direction resolveStartFacing(Level level, BlockPos endPos, Direction fallback) {
            return startFacing != null ? startFacing : ConveyorItem.resolveStartFacing(level, startPos, endPos, fallback);
        }
    }
}
