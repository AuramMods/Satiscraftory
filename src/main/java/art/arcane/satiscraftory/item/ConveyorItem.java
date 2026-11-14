package art.arcane.satiscraftory.item;

import art.arcane.satiscraftory.block.ConveyorBlock;
import art.arcane.satiscraftory.block.ConveyorConnectable;
import art.arcane.satiscraftory.block.ConveyorEndBlock;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity;
import art.arcane.satiscraftory.block.entity.MergerBlockEntity;
import art.arcane.satiscraftory.block.entity.SplitterBlockEntity;
import art.arcane.satiscraftory.data.route.BeltRoute;
import art.arcane.satiscraftory.data.route.BeltRouteMode;
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
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import javax.annotation.Nullable;

public class ConveyorItem extends BlockItem {
    private static final String PLACEMENT_STATE_TAG = "satiscraftory_spline_experiment_state";
    private static final String START_POS_TAG = "start_pos";
    private static final String START_FACING_TAG = "start_facing";
    private static final String START_DIMENSION_TAG = "start_dimension";
    private static final String ROUTE_MODE_TAG = "route_mode";
    public static final int MAX_BELT_LENGTH_BLOCKS = 16;

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
            player.displayClientMessage(Component.literal("Belt placement canceled."), true);
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
                        player.displayClientMessage(Component.literal("Belt start is blocked: " + formatPos(startPos)), true);
                    }
                    return InteractionResult.FAIL;
                }
                startFacing = face;
            } else {
                startPos = resolvePlacementPos(context);
            }

            BeltRouteMode mode = BeltRouteMode.DEFAULT;
            storePlacementState(player, new PlacementState(startPos, level.dimension().location().toString(), startFacing, mode));
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("Belt start set: " + formatPos(startPos)
                        + " | " + mode.label() + " (Tab to cycle)"), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (!state.dimensionId.equals(level.dimension().location().toString())) {
            clearPlacementState(player);
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("Belt start cleared (dimension changed)."), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        BlockPos startPos = state.startPos;
        BlockPos selectedPos = resolvePlacementPos(context);
        BlockState targetState = level.getBlockState(startPos);
        boolean blockedStart = !targetState.canBeReplaced();

        Direction startFacing = state.resolveStartFacing(level, selectedPos, player.getDirection());
        Direction endFacing = resolveEndFacing(level, startPos, selectedPos, player.getDirection());
        BeltRoute route = state.mode.create(startPos, startFacing, selectedPos, endFacing);
        int estimatedLength = route.lengthInBlocks();
        boolean overMaxLength = estimatedLength > MAX_BELT_LENGTH_BLOCKS;

        if (level.isClientSide) {
            // Keep the hologram active on rejected placements so the player can adjust the endpoint.
            if (!blockedStart && !overMaxLength) {
                clearPlacementState(player);
            }
            return InteractionResult.SUCCESS;
        }

        if (blockedStart) {
            player.displayClientMessage(Component.literal("Belt start is blocked: " + formatPos(startPos)), true);
            return InteractionResult.FAIL;
        }

        if (overMaxLength) {
            player.displayClientMessage(
                    Component.literal("Belt too long (" + estimatedLength + " > " + MAX_BELT_LENGTH_BLOCKS + " blocks)."),
                    true
            );
            return InteractionResult.FAIL;
        }

        BlockState placeState = getBlock().defaultBlockState();
        if (placeState.hasProperty(ConveyorBlock.FACING)) {
            placeState = placeState.setValue(ConveyorBlock.FACING, startFacing);
        }

        if (!level.setBlock(startPos, placeState, Block.UPDATE_ALL)) {
            player.displayClientMessage(Component.literal("Failed to place belt start block."), true);
            return InteractionResult.FAIL;
        }

        BlockEntity blockEntity = level.getBlockEntity(startPos);
        if (blockEntity instanceof ConveyorBlockEntity conveyor) {
            conveyor.setEndData(selectedPos, endFacing, state.mode);
        }

        playPlaceSound(level, startPos, placeState);
        level.gameEvent(player, GameEvent.BLOCK_PLACE, startPos);

        ItemStack stack = context.getItemInHand();
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }

        clearPlacementState(player);
        player.displayClientMessage(Component.literal("Belt endpoint set: " + formatPos(selectedPos)), true);
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
        BeltRouteMode mode = BeltRouteMode.byId(placementData.getString(ROUTE_MODE_TAG));
        return new PlacementState(startPos, dimensionId, startFacing, mode == null ? BeltRouteMode.DEFAULT : mode);
    }

    private static void storePlacementState(Player player, PlacementState state) {
        CompoundTag placementData = new CompoundTag();
        placementData.put(START_POS_TAG, NbtUtils.writeBlockPos(state.startPos));
        placementData.putString(START_DIMENSION_TAG, state.dimensionId);
        placementData.putString(ROUTE_MODE_TAG, state.mode.id());
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

    public static boolean isHoldingBelt(Player player) {
        return player.getMainHandItem().getItem() instanceof ConveyorItem
                || player.getOffhandItem().getItem() instanceof ConveyorItem;
    }

    public static boolean selectRouteMode(Player player, BeltRouteMode mode) {
        PlacementState state = getActivePlacement(player, player.level());
        if (state == null) {
            return false;
        }
        storePlacementState(player, new PlacementState(state.startPos, state.dimensionId, state.startFacing, mode));
        return true;
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

    public record PlacementState(BlockPos startPos, String dimensionId, @Nullable Direction startFacing, BeltRouteMode mode) {
        public Direction resolveStartFacing(Level level, BlockPos endPos, Direction fallback) {
            return startFacing != null ? startFacing : ConveyorItem.resolveStartFacing(level, startPos, endPos, fallback);
        }
    }
}
