package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.ConveyorBlock;
import art.arcane.satiscraftory.block.ConveyorEndBlock;
import art.arcane.satiscraftory.client.SatiscraftoryClient;
import art.arcane.satiscraftory.data.route.BeltRoute;
import art.arcane.satiscraftory.data.route.BeltRouteMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ConveyorBlockEntity extends BlockEntity {
    private static final int SLOTS_PER_BLOCK = 3;
    private static final int DEFAULT_TRAVEL_TICKS_PER_BLOCK = 60;
    private static final int MAX_CLIENT_PREDICTION_STEPS = 256;
    private static final double MIN_CLIENT_CORRECTION_SLOTS_PER_TICK = 0.125D;
    private static final double CLIENT_CORRECTION_SPEED_FRACTION = 0.25D;
    private static final double VISUAL_RANGE_BLOCKS = 16.0D;
    private static final double ITEM_Y_OFFSET = 0.08D;
    private static final int HANDOFF_GRACE_TICKS = 3;

    private static final String END_POS_TAG = "end_pos";
    private static final String END_FACING_TAG = "end_facing";
    private static final String ROUTE_MODE_TAG = "route_mode";
    private static final String BELT_LENGTH_BLOCKS_TAG = "belt_length_blocks";
    private static final String BUFFER_SLOTS_TAG = "buffer_slots";
    private static final String INVENTORY_TAG = "inventory";
    private static final String RENDER_POSITIONS_TAG = "render_positions";
    private static final String ITEM_IDS_TAG = "item_ids";
    private static final String STEP_ACCUMULATOR_TAG = "step_accumulator";
    private static final String TRAVEL_TICKS_PER_BLOCK_TAG = "travel_ticks_per_block";
    private static final String SYNC_REVISION_TAG = "sync_revision";
    private static final String DIRECT_INPUT_TAG = "direct_input";

    private static long NEXT_ITEM_ID = 1L;
    private static long nextClientSnapshotOrder;

    @Nullable
    private BlockPos endPos;
    private Direction endFacing = Direction.NORTH;
    private BeltRouteMode routeMode = BeltRouteMode.SPLINE;
    private BeltRoute route;
    private Direction routeStartFacing;
    private int beltLengthBlocks = 1;
    private int bufferSlots = SLOTS_PER_BLOCK;
    private int travelTicksPerBlock = DEFAULT_TRAVEL_TICKS_PER_BLOCK;
    @Nullable
    private BlockPos directInputPos;

    private ItemStackHandler inventory;
    private int[] renderPositions;
    private long[] itemIds;

    private boolean suppressDirtyCallbacks;

    private LazyOptional<IItemHandler> unsidedCapability = LazyOptional.empty();
    private final Map<Direction, LazyOptional<IItemHandler>> sidedCapabilities = new EnumMap<>(Direction.class);

    private final Map<Long, SplineVisualItemEntity> clientVisualItems = new HashMap<>();
    private long lastAccumulatorTick = Long.MIN_VALUE;
    private long lastSyncPacketGameTime = Long.MIN_VALUE;
    private double stepAccumulator;
    private long syncRevision;
    private boolean needsSync;
    private boolean handoffSyncPending;
    private transient long nextEndMarkerValidationTick = Long.MIN_VALUE;

    private transient long lastClientAppliedRevision = Long.MIN_VALUE;
    private transient long clientSnapshotGameTime = Long.MIN_VALUE;
    private transient long clientSnapshotOrder;

    public ConveyorBlockEntity(BlockPos pos, BlockState blockState) {
        super(Satiscraftory.CONVEYOR_BLOCK_ENTITY.get(), pos, blockState);
        travelTicksPerBlock = resolveTravelTicksPerBlock(blockState);
        inventory = createInventory(bufferSlots);
        renderPositions = new int[bufferSlots];
        Arrays.fill(renderPositions, -1);
        itemIds = new long[bufferSlots];
        rebuildCapabilities();
    }

    @Nullable
    public BlockPos getEndPos() {
        return endPos;
    }

    public Direction getEndFacing() {
        return endFacing;
    }

    public int getBeltLengthBlocks() {
        return beltLengthBlocks;
    }

    public int getTravelTicksPerBlock() {
        return travelTicksPerBlock;
    }

    public BeltRoute getRoute() {
        Direction startFacing = getFacing(getBlockState());
        if (route == null || routeStartFacing != startFacing) {
            BlockPos target = endPos == null ? worldPosition.relative(startFacing) : endPos;
            route = routeMode.create(worldPosition, startFacing, target, getOutputSide());
            routeStartFacing = startFacing;
        }
        return route;
    }

    public void setEndData(BlockPos endPos, Direction endFacing, BeltRouteMode mode) {
        BlockPos previousEndPos = this.endPos;
        this.endPos = endPos.immutable();
        this.endFacing = endFacing.getAxis().isHorizontal() ? endFacing : Direction.NORTH;
        routeMode = mode;
        route = null;
        recalculateLengthAndResize();
        nextEndMarkerValidationTick = Long.MIN_VALUE;
        if (level != null && !level.isClientSide) {
            updateLinkedEndMarker(previousEndPos);
        }
        setChanged();
        syncToClient();
    }

    public void removeLinkedEndMarker() {
        if (level == null || level.isClientSide || endPos == null) {
            return;
        }
        removeLinkedEndMarkerAt(endPos);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        unsidedCapability.invalidate();
        for (LazyOptional<IItemHandler> capability : sidedCapabilities.values()) {
            capability.invalidate();
        }
        sidedCapabilities.clear();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        rebuildCapabilities();
    }

    @Override
    public void setRemoved() {
        clearClientVisuals();
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (endPos != null) {
            tag.put(END_POS_TAG, NbtUtils.writeBlockPos(endPos));
        }
        tag.putString(END_FACING_TAG, endFacing.getName());
        tag.putString(ROUTE_MODE_TAG, routeMode.id());
        tag.putInt(BELT_LENGTH_BLOCKS_TAG, beltLengthBlocks);
        tag.putInt(BUFFER_SLOTS_TAG, bufferSlots);
        tag.put(INVENTORY_TAG, inventory.serializeNBT());
        tag.putIntArray(RENDER_POSITIONS_TAG, renderPositions);
        tag.putLongArray(ITEM_IDS_TAG, itemIds);
        tag.putDouble(STEP_ACCUMULATOR_TAG, stepAccumulator);
        tag.putInt(TRAVEL_TICKS_PER_BLOCK_TAG, travelTicksPerBlock);
        tag.putLong(SYNC_REVISION_TAG, syncRevision);
        if (directInputPos != null) {
            tag.put(DIRECT_INPUT_TAG, NbtUtils.writeBlockPos(directInputPos));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        long incomingRevision = tag.contains(SYNC_REVISION_TAG) ? tag.getLong(SYNC_REVISION_TAG) : 0L;
        boolean isClient = level != null && level.isClientSide;
        if (isClient && incomingRevision < lastClientAppliedRevision) {
            return;
        }

        super.load(tag);

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
                endFacing = fallbackFacing();
            }
        } else {
            endFacing = fallbackFacing();
        }

        BeltRouteMode loadedMode = BeltRouteMode.byId(tag.getString(ROUTE_MODE_TAG));
        routeMode = loadedMode == null ? BeltRouteMode.SPLINE : loadedMode;
        if (!Objects.equals(previousEndPos, endPos) || previousEndFacing != endFacing || previousMode != routeMode) {
            route = null;
        }

        beltLengthBlocks = Math.max(1, tag.getInt(BELT_LENGTH_BLOCKS_TAG));
        int loadedSlots = Math.max(1, tag.contains(BUFFER_SLOTS_TAG, Tag.TAG_INT)
                ? tag.getInt(BUFFER_SLOTS_TAG)
                : beltLengthBlocks * SLOTS_PER_BLOCK);

        suppressDirtyCallbacks = true;
        inventory = createInventory(loadedSlots);
        if (tag.contains(INVENTORY_TAG, Tag.TAG_COMPOUND)) {
            inventory.deserializeNBT(tag.getCompound(INVENTORY_TAG));
        }
        bufferSlots = Math.max(1, inventory.getSlots());

        renderPositions = new int[bufferSlots];
        Arrays.fill(renderPositions, -1);
        if (tag.contains(RENDER_POSITIONS_TAG, Tag.TAG_INT_ARRAY)) {
            int[] loadedPositions = tag.getIntArray(RENDER_POSITIONS_TAG);
            for (int slot = 0; slot < Math.min(bufferSlots, loadedPositions.length); slot++) {
                renderPositions[slot] = clampRenderPosition(loadedPositions[slot]);
            }
        }

        itemIds = new long[bufferSlots];
        if (tag.contains(ITEM_IDS_TAG, Tag.TAG_LONG_ARRAY)) {
            long[] loadedIds = tag.getLongArray(ITEM_IDS_TAG);
            System.arraycopy(loadedIds, 0, itemIds, 0, Math.min(bufferSlots, loadedIds.length));
        }

        stepAccumulator = tag.contains(STEP_ACCUMULATOR_TAG, Tag.TAG_DOUBLE)
                ? clamp(tag.getDouble(STEP_ACCUMULATOR_TAG), 0.0D, 512.0D)
                : 0.0D;
        travelTicksPerBlock = tag.contains(TRAVEL_TICKS_PER_BLOCK_TAG, Tag.TAG_INT)
                ? Math.max(1, tag.getInt(TRAVEL_TICKS_PER_BLOCK_TAG))
                : resolveTravelTicksPerBlock(getBlockState());
        syncRevision = Math.max(0L, incomingRevision);
        lastAccumulatorTick = Long.MIN_VALUE;
        lastSyncPacketGameTime = Long.MIN_VALUE;
        needsSync = false;
        handoffSyncPending = false;
        directInputPos = tag.contains(DIRECT_INPUT_TAG, Tag.TAG_COMPOUND)
                ? NbtUtils.readBlockPos(tag.getCompound(DIRECT_INPUT_TAG)) : null;
        nextEndMarkerValidationTick = Long.MIN_VALUE;

        normalizeInventory(false);
        recalculateLengthAndResize();
        normalizeInventory(false);

        suppressDirtyCallbacks = false;

        advanceNextItemIdFromLoadedItems();

        if (isClient) {
            sanitizeClientLoadedState();
            lastClientAppliedRevision = syncRevision;
            clientSnapshotGameTime = level != null ? level.getGameTime() : Long.MIN_VALUE;
            clientSnapshotOrder = ++nextClientSnapshotOrder;
        }

        rebuildCapabilities();
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

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            if (side == null) {
                return unsidedCapability.cast();
            }
            LazyOptional<IItemHandler> sided = sidedCapabilities.get(side);
            if (sided != null) {
                return sided.cast();
            }
            return unsidedCapability.cast();
        }
        return super.getCapability(cap, side);
    }

    public void dropContents(Level level) {
        if (level.isClientSide) {
            return;
        }
        List<PredictedQueueItem> queue = snapshotRenderQueue();
        if (queue.isEmpty()) {
            return;
        }

        // Sample the live queue without projecting transfers beyond the current partial step.
        double partialProgress = stepAccumulator - Math.floor(stepAccumulator);
        List<PredictedRenderItem> drops = interpolateRenderItems(queue, getOutputContainerHandler(),
                getDirectOutputConveyor() != null, partialProgress);

        for (int slot = 0; slot < bufferSlots; slot++) {
            inventory.setStackInSlot(slot, ItemStack.EMPTY);
            renderPositions[slot] = -1;
            itemIds[slot] = 0L;
        }
        for (PredictedRenderItem drop : drops) {
            Vec3 position = computeVisualPositionForSlotUnits(drop.slotUnits);
            ItemEntity entity = new ItemEntity(level, position.x, position.y, position.z, drop.stack,
                    0.0D, 0.0D, 0.0D);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ConveyorBlockEntity conveyor) {
        conveyor.tickServer(level, pos, state);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, ConveyorBlockEntity conveyor) {
        conveyor.tickClient(level, pos, state);
    }

    private void tickServer(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ConveyorBlock)) {
            return;
        }

        validateLinkedEndMarker(level.getGameTime());

        if (recalculateLengthAndResize()) {
            normalizeInventory(true);
        }

        updateStepAccumulator(level.getGameTime());
        int stepBudget = availableStepBudget();
        for (int i = 0; i < stepBudget; i++) {
            runSubStep(level);
            consumeOneStepBudget();
        }

        if (needsSync && syncDirtyItems()) {
            needsSync = false;
        }
        handoffSyncPending = false;
    }

    private void tickClient(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ConveyorBlock)) {
            clearClientVisuals();
            return;
        }

        if (!isPlayerWithinVisualRange(level)) {
            clearClientVisuals();
            clientSnapshotGameTime = level.getGameTime();
            return;
        }

        long now = level.getGameTime();
        if (clientSnapshotGameTime == Long.MIN_VALUE) {
            clientSnapshotGameTime = now;
        }
        long elapsedTicks = Math.max(0L, now - clientSnapshotGameTime);
        ConveyorBlockEntity nextBelt = getDirectOutputConveyor();
        ConveyorBlockEntity previousBelt = getDirectInputConveyor();
        List<PredictedRenderItem> prediction = predictClientRenderItems(elapsedTicks, nextBelt != null);
        double slotsPerTick = getStepsPerTick();
        double maxCorrection = Math.max(MIN_CLIENT_CORRECTION_SLOTS_PER_TICK,
                slotsPerTick * CLIENT_CORRECTION_SPEED_FRACTION);

        Set<Long> activeVisualKeys = new HashSet<>();
        for (PredictedRenderItem item : prediction) {
            if (item.stack.isEmpty()) {
                continue;
            }

            activeVisualKeys.add(item.visualKey);
            updateClientVisual(item, previousBelt, nextBelt, now, slotsPerTick, maxCorrection);
        }

        finishDepartingVisuals(activeVisualKeys, nextBelt != null, now, slotsPerTick, maxCorrection);
    }

    private List<PredictedQueueItem> snapshotRenderQueue() {
        List<PredictedQueueItem> queue = new ArrayList<>();
        for (int slot = 0; slot < bufferSlots; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }

            long visualKey = itemIds[slot] > 0L ? itemIds[slot] : -(slot + 1L);
            int basePosition = clampRenderPosition(renderPositions[slot]);
            if (basePosition < 0) {
                basePosition = 0;
            }
            queue.add(new PredictedQueueItem(visualKey, stack.copy(), basePosition));
        }
        return queue;
    }

    private List<PredictedRenderItem> predictClientRenderItems(long elapsedTicks, boolean directOutput) {
        List<PredictedQueueItem> queue = snapshotRenderQueue();
        if (queue.isEmpty()) {
            return List.of();
        }

        double projectedAccumulator = Math.max(0.0D, stepAccumulator + (elapsedTicks * getStepsPerTick()));
        int projectedWholeSteps = (int) Math.floor(projectedAccumulator);
        int simulatedWholeSteps = Math.min(projectedWholeSteps, getMaxClientPredictionSteps());
        double partialProgress = projectedWholeSteps > simulatedWholeSteps
                ? 0.0D
                : clamp(projectedAccumulator - projectedWholeSteps, 0.0D, 1.0D);

        IItemHandler predictedOutput = getOutputContainerHandler();
        for (int step = 0; step < simulatedWholeSteps; step++) {
            simulateRenderSubStep(queue, predictedOutput, directOutput);
        }

        return interpolateRenderItems(queue, predictedOutput, directOutput, partialProgress);
    }

    private List<PredictedRenderItem> interpolateRenderItems(List<PredictedQueueItem> queue,
                                                            @Nullable IItemHandler predictedOutput,
                                                            boolean directOutput, double partialProgress) {
        List<PredictedQueueItem> previewQueue = copyRenderQueue(queue);
        simulateRenderSubStep(previewQueue, predictedOutput, directOutput);

        Map<Long, Integer> nextPositionsByKey = new HashMap<>();
        for (PredictedQueueItem previewItem : previewQueue) {
            nextPositionsByKey.put(previewItem.visualKey, previewItem.position);
        }

        List<PredictedRenderItem> renderedItems = new ArrayList<>(queue.size());
        for (PredictedQueueItem item : queue) {
            // The first slot starts on the input edge. A departing head advances to the output edge.
            int nextPosition = nextPositionsByKey.getOrDefault(item.visualKey, bufferSlots);
            double slotUnits = Mth.lerp(partialProgress, item.position, nextPosition);
            renderedItems.add(new PredictedRenderItem(item.visualKey, item.stack, slotUnits));
        }

        return renderedItems;
    }

    private void simulateRenderSubStep(List<PredictedQueueItem> queue, @Nullable IItemHandler predictedOutput,
                                       boolean directOutput) {
        predictHeadTransfer(queue, predictedOutput, directOutput);
        advanceRenderQueue(queue);
    }

    private void predictHeadTransfer(List<PredictedQueueItem> queue, @Nullable IItemHandler predictedOutput,
                                     boolean directOutput) {
        if (queue.isEmpty()) {
            return;
        }

        PredictedQueueItem head = queue.get(0);
        if (head.position != bufferSlots - 1) {
            return;
        }
        if (!canLikelyTransferHead(predictedOutput, head.stack)) {
            return;
        }

        if (directOutput) {
            // Keep the visual on the seam until the next belt actually receives this item ID.
            head.position = bufferSlots;
        } else {
            queue.remove(0);
        }
    }

    private void advanceRenderQueue(List<PredictedQueueItem> queue) {
        if (queue.isEmpty()) {
            return;
        }

        boolean[] taken = new boolean[bufferSlots];
        for (PredictedQueueItem item : queue) {
            if (item.position == bufferSlots) {
                continue;
            }
            int current = item.position;
            if (current < 0) {
                current = 0;
            }
            current = clamp(current, 0, bufferSlots - 1);

            int desired = Math.min(bufferSlots - 1, current + 1);
            int next = (desired >= 0 && desired < taken.length && !taken[desired]) ? desired : current;
            if (next >= 0 && next < taken.length) {
                taken[next] = true;
            }
            item.position = next;
        }
    }

    private boolean canLikelyTransferHead(@Nullable IItemHandler predictedOutput, ItemStack stack) {
        if (predictedOutput == null || stack.isEmpty()) {
            return false;
        }

        ItemStack single = stack.copy();
        single.setCount(1);
        return insertIntoHandler(predictedOutput, single, true).isEmpty();
    }

    private static List<PredictedQueueItem> copyRenderQueue(List<PredictedQueueItem> source) {
        List<PredictedQueueItem> copy = new ArrayList<>(source.size());
        for (PredictedQueueItem item : source) {
            copy.add(new PredictedQueueItem(item.visualKey, item.stack, item.position));
        }
        return copy;
    }

    private int getMaxClientPredictionSteps() {
        return Math.max(bufferSlots * 2, MAX_CLIENT_PREDICTION_STEPS);
    }

    private void runSubStep(Level level) {
        tryTransferToOutput(level);
        advanceItemsOneStep();
        pullFromInputContainer();
    }

    private boolean tryTransferToOutput(Level level) {
        if (!hasHeadAtOutput()) {
            return false;
        }

        ConveyorBlockEntity nextBelt = getDirectOutputConveyor();
        if (nextBelt != null) {
            return transferHeadTo(nextBelt);
        }

        IItemHandler outputHandler = getOutputContainerHandler();
        if (outputHandler == null) {
            return false;
        }

        ItemStack moving = peekHeadSingle();
        if (moving.isEmpty()) {
            return false;
        }

        if (!insertIntoHandler(outputHandler, moving, true).isEmpty()) {
            return false;
        }

        HeadTransfer transfer = popHeadForTransfer();
        if (transfer == null || transfer.stack.isEmpty()) {
            return false;
        }

        ItemStack remaining = insertIntoHandler(outputHandler, transfer.stack, false);
        if (!remaining.isEmpty()) {
            BlockPos outputPos = resolveOutputTargetPos();
            Containers.dropItemStack(level,
                    outputPos.getX() + 0.5D,
                    outputPos.getY() + 0.5D,
                    outputPos.getZ() + 0.5D,
                    remaining);
        }

        return true;
    }

    private boolean pullFromInputContainer() {
        if (level == null || !canAcceptNewItemAtStep()) {
            return false;
        }

        if (level.getBlockEntity(resolveInputTargetPos()) instanceof ConveyorBlockEntity previousBelt
                && previousBelt.getDirectOutputConveyor() == this) {
            return previousBelt.transferHeadTo(this);
        }

        IItemHandler handler = getInputContainerHandler();
        if (handler == null) {
            return false;
        }

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack simulated = handler.extractItem(slot, 1, true);
            if (simulated.isEmpty()) {
                continue;
            }

            ItemStack single = simulated.copy();
            single.setCount(1);
            if (!enqueueItem(single, true)) {
                return false;
            }

            ItemStack extracted = handler.extractItem(slot, 1, false);
            if (extracted.isEmpty()) {
                continue;
            }

            ItemStack extractedSingle = extracted.copy();
            extractedSingle.setCount(1);
            return enqueueItem(extractedSingle, false);
        }

        return false;
    }

    @Nullable
    private ConveyorBlockEntity getDirectOutputConveyor() {
        if (level != null && endPos != null
                && level.getBlockEntity(resolveOutputTargetPos()) instanceof ConveyorBlockEntity target
                && target != this && target.endPos != null && target.canInsertFromSide(getOutputSide().getOpposite())) {
            return target;
        }
        return null;
    }

    @Nullable
    private ConveyorBlockEntity getDirectInputConveyor() {
        if (level != null && directInputPos != null
                && level.getBlockEntity(directInputPos) instanceof ConveyorBlockEntity source
                && source.getDirectOutputConveyor() == this) {
            return source;
        }
        return null;
    }

    private boolean transferHeadTo(ConveyorBlockEntity target) {
        int slot = target.findFirstEmptySlot();
        if (slot < 0 || target.hasItemAtPosition(0)) {
            return false;
        }
        HeadTransfer transfer = popHeadForTransfer();
        if (transfer == null) {
            return false;
        }
        target.appendItem(slot, transfer.stack, transfer.itemId);
        target.directInputPos = worldPosition;
        // Let both belts publish their completed tick without waiting for the regular snapshot interval.
        handoffSyncPending = true;
        target.handoffSyncPending = true;
        return true;
    }

    @Nullable
    private IItemHandler getInputContainerHandler() {
        if (level == null) {
            return null;
        }

        BlockEntity blockEntity = level.getBlockEntity(resolveInputTargetPos());
        if (blockEntity == null || blockEntity == this) {
            return null;
        }

        Direction inputSide = getInputSide();
        return getItemHandler(blockEntity, inputSide.getOpposite());
    }

    @Nullable
    private IItemHandler getOutputContainerHandler() {
        if (level == null) {
            return null;
        }

        BlockEntity blockEntity = level.getBlockEntity(resolveOutputTargetPos());
        if (blockEntity == null || blockEntity == this) {
            return null;
        }

        Direction outputSide = getOutputSide();
        return getItemHandler(blockEntity, outputSide.getOpposite());
    }

    @Nullable
    private IItemHandler getItemHandler(BlockEntity blockEntity, Direction side) {
        LazyOptional<IItemHandler> sided = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side);
        if (sided.isPresent()) {
            return sided.orElse(null);
        }

        LazyOptional<IItemHandler> unsided = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null);
        return unsided.orElse(null);
    }

    private static ItemStack insertIntoHandler(IItemHandler handler, ItemStack stack, boolean simulate) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = handler.insertItem(slot, remaining, simulate);
        }
        return remaining;
    }

    private boolean enqueueItem(ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return true;
        }

        if (!canAcceptNewItemAtStep()) {
            return false;
        }

        int slot = findFirstEmptySlot();
        if (slot < 0) {
            return false;
        }

        if (!simulate) {
            appendItem(slot, stack, allocateItemId());
        }

        return true;
    }

    private void appendItem(int slot, ItemStack stack, long itemId) {
        ItemStack single = stack.copy();
        single.setCount(1);
        suppressDirtyCallbacks = true;
        inventory.setStackInSlot(slot, single);
        suppressDirtyCallbacks = false;
        renderPositions[slot] = 0;
        itemIds[slot] = itemId;
        markDirtyForSync();
    }

    private ItemStack insertFromCapability(@Nullable Direction side, int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        if (side != null && !canInsertFromSide(side)) {
            return stack;
        }

        int tailSlot = findFirstEmptySlot();
        if (tailSlot < 0 || slot != tailSlot || !canAcceptNewItemAtStep()) {
            return stack;
        }

        if (!simulate) {
            appendItem(tailSlot, stack, allocateItemId());
        }

        ItemStack leftover = stack.copy();
        leftover.shrink(1);
        return leftover;
    }

    private ItemStack extractFromCapability(@Nullable Direction side, int slot, int amount, boolean simulate) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }

        if (side != null && !canExtractFromSide(side)) {
            return ItemStack.EMPTY;
        }

        if (slot != 0 || !hasHeadAtOutput()) {
            return ItemStack.EMPTY;
        }

        ItemStack head = inventory.getStackInSlot(0);
        if (head.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack single = head.copy();
        single.setCount(1);

        if (!simulate) {
            popHeadForTransfer();
        }

        return single;
    }

    private int findFirstEmptySlot() {
        int capacity = Math.min(bufferSlots, desiredBufferSlots());
        for (int slot = 0; slot < capacity; slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private boolean compactQueue() {
        boolean changed = false;
        int write = 0;

        suppressDirtyCallbacks = true;
        for (int read = 0; read < bufferSlots; read++) {
            ItemStack stack = inventory.getStackInSlot(read);
            if (stack.isEmpty()) {
                continue;
            }

            if (write != read) {
                inventory.setStackInSlot(write, stack);
                inventory.setStackInSlot(read, ItemStack.EMPTY);
                renderPositions[write] = renderPositions[read];
                renderPositions[read] = -1;
                itemIds[write] = itemIds[read];
                itemIds[read] = 0L;
                changed = true;
            }

            write++;
        }

        for (int slot = write; slot < bufferSlots; slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
                changed = true;
            }
            if (renderPositions[slot] != -1) {
                renderPositions[slot] = -1;
                changed = true;
            }
            if (itemIds[slot] != 0L) {
                itemIds[slot] = 0L;
                changed = true;
            }
        }
        suppressDirtyCallbacks = false;

        return changed;
    }

    private boolean normalizeInventory(boolean markDirty) {
        boolean changed = compactQueue();

        suppressDirtyCallbacks = true;
        for (int slot = 0; slot < bufferSlots; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                if (renderPositions[slot] != -1) {
                    renderPositions[slot] = -1;
                    changed = true;
                }
                if (itemIds[slot] != 0L) {
                    itemIds[slot] = 0L;
                    changed = true;
                }
                continue;
            }

            if (itemIds[slot] == 0L) {
                itemIds[slot] = allocateItemId();
                changed = true;
            }

            if (stack.getCount() <= 1) {
                continue;
            }

            int overflow = stack.getCount() - 1;
            ItemStack single = stack.copy();
            single.setCount(1);
            inventory.setStackInSlot(slot, single);
            changed = true;

            while (overflow > 0) {
                int emptySlot = findFirstEmptySlot();
                if (emptySlot < 0) {
                    break;
                }

                inventory.setStackInSlot(emptySlot, single.copy());
                renderPositions[emptySlot] = 0;
                itemIds[emptySlot] = allocateItemId();
                overflow--;
                changed = true;
            }
        }
        suppressDirtyCallbacks = false;

        if (compactQueue()) {
            changed = true;
        }

        if (normalizeRenderPositions(countNonEmptySlots())) {
            changed = true;
        }

        if (changed && markDirty) {
            markDirtyForSync();
        }

        return changed;
    }

    private void sanitizeClientLoadedState() {
        for (int slot = 0; slot < bufferSlots; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                renderPositions[slot] = -1;
                itemIds[slot] = 0L;
                continue;
            }

            int position = clampRenderPosition(renderPositions[slot]);
            renderPositions[slot] = position < 0 ? 0 : position;
        }
    }

    private int countNonEmptySlots() {
        int count = 0;
        for (int slot = 0; slot < bufferSlots; slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private boolean hasHeadAtOutput() {
        if (inventory.getStackInSlot(0).isEmpty()) {
            return false;
        }

        return clampRenderPosition(renderPositions[0]) >= bufferSlots - 1;
    }

    private ItemStack peekHeadSingle() {
        if (!hasHeadAtOutput()) {
            return ItemStack.EMPTY;
        }

        ItemStack head = inventory.getStackInSlot(0);
        if (head.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack single = head.copy();
        single.setCount(1);
        return single;
    }

    @Nullable
    private HeadTransfer popHeadForTransfer() {
        if (!hasHeadAtOutput()) {
            return null;
        }

        ItemStack head = inventory.getStackInSlot(0);
        long itemId = itemIds[0];
        if (head.isEmpty() || itemId <= 0L) {
            return null;
        }

        ItemStack single = head.copy();
        single.setCount(1);

        suppressDirtyCallbacks = true;
        inventory.setStackInSlot(0, ItemStack.EMPTY);
        suppressDirtyCallbacks = false;
        renderPositions[0] = -1;
        itemIds[0] = 0L;
        compactQueue();
        markDirtyForSync();

        return new HeadTransfer(single, itemId);
    }

    private boolean canAcceptNewItemAtStep() {
        return findFirstEmptySlot() >= 0 && !hasItemAtPosition(0);
    }

    private boolean hasItemAtPosition(int position) {
        int clampedPosition = clampRenderPosition(position);
        if (clampedPosition < 0) {
            return false;
        }

        for (int slot = 0; slot < bufferSlots; slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty() && renderPositions[slot] == clampedPosition) {
                return true;
            }
        }

        return false;
    }

    private boolean advanceItemsOneStep() {
        boolean changed = false;
        int[] nextPositions = new int[bufferSlots];
        Arrays.fill(nextPositions, -1);

        for (int slot = 0; slot < bufferSlots; slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                continue;
            }

            int current = clampRenderPosition(renderPositions[slot]);
            if (current < 0) {
                current = 0;
            }

            int desired = Math.min(bufferSlots - 1, current + 1);
            int next = isPositionTaken(nextPositions, desired) ? current : desired;
            nextPositions[slot] = next;

            if (next != renderPositions[slot]) {
                changed = true;
            }
        }

        if (!changed) {
            return false;
        }

        for (int slot = 0; slot < bufferSlots; slot++) {
            renderPositions[slot] = inventory.getStackInSlot(slot).isEmpty() ? -1 : nextPositions[slot];
        }

        markDirtyForSync();
        return true;
    }

    private static boolean isPositionTaken(int[] positions, int target) {
        for (int position : positions) {
            if (position == target) {
                return true;
            }
        }
        return false;
    }

    private boolean normalizeRenderPositions(int nonEmptyCount) {
        boolean changed = false;
        int previousMaximum = bufferSlots - 1;
        int seen = 0;

        for (int slot = 0; slot < bufferSlots; slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                if (renderPositions[slot] != -1) {
                    renderPositions[slot] = -1;
                    changed = true;
                }
                continue;
            }

            int remaining = Math.max(0, nonEmptyCount - seen - 1);
            int lower = Math.min(bufferSlots - 1, remaining);
            int upper = Math.max(lower, previousMaximum);
            int clamped = clamp(renderPositions[slot], lower, upper);
            if (renderPositions[slot] != clamped) {
                renderPositions[slot] = clamped;
                changed = true;
            }
            previousMaximum = Math.max(0, clamped - 1);
            seen++;
        }

        return changed;
    }

    private int clampRenderPosition(int position) {
        if (position < 0) {
            return -1;
        }

        return Math.min(bufferSlots - 1, position);
    }

    private int desiredBufferSlots() {
        return beltLengthBlocks * SLOTS_PER_BLOCK;
    }

    private boolean recalculateLengthAndResize() {
        int computedLength = endPos == null ? 1 : getRoute().lengthInBlocks();

        boolean changed = false;
        if (beltLengthBlocks != computedLength) {
            beltLengthBlocks = computedLength;
            changed = true;
        }

        int desiredSlots = desiredBufferSlots();
        // Rounded routes may be shorter in existing saves. Drain the old slots before shrinking.
        if (desiredSlots > bufferSlots || (desiredSlots < bufferSlots && countNonEmptySlots() <= desiredSlots)) {
            resizeBuffer(desiredSlots);
            changed = true;
        }

        return changed;
    }

    private void resizeBuffer(int desiredSlots) {
        List<BufferedItem> items = new ArrayList<>();
        int oldSlots = Math.max(1, bufferSlots);
        for (int slot = 0; slot < bufferSlots; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack single = stack.copy();
            single.setCount(1);
            int oldPosition = clamp(renderPositions[slot], 0, oldSlots - 1);
            double progress = clamp((oldPosition + 0.5D) / oldSlots, 0.0D, 1.0D);
            long id = itemIds[slot] > 0L ? itemIds[slot] : allocateItemId();
            items.add(new BufferedItem(single, id, progress));
        }

        suppressDirtyCallbacks = true;
        inventory = createInventory(desiredSlots);
        bufferSlots = desiredSlots;
        renderPositions = new int[bufferSlots];
        Arrays.fill(renderPositions, -1);
        itemIds = new long[bufferSlots];

        int write = 0;
        for (BufferedItem item : items) {
            inventory.setStackInSlot(write, item.stack.copy());
            itemIds[write] = item.itemId;
            int mapped = clamp((int) Math.floor(item.progress * bufferSlots), 0, bufferSlots - 1);
            renderPositions[write] = mapped;
            write++;
        }
        suppressDirtyCallbacks = false;

        normalizeInventory(true);
    }

    private void updateStepAccumulator(long now) {
        if (lastAccumulatorTick == Long.MIN_VALUE) {
            lastAccumulatorTick = now;
            return;
        }

        long elapsed = Math.max(0L, now - lastAccumulatorTick);
        lastAccumulatorTick = now;
        if (elapsed <= 0L) {
            return;
        }

        stepAccumulator = Math.min(512.0D, stepAccumulator + (elapsed * getStepsPerTick()));
    }

    private int availableStepBudget() {
        return (int) Math.floor(stepAccumulator);
    }

    private void consumeOneStepBudget() {
        stepAccumulator = Math.max(0.0D, stepAccumulator - 1.0D);
    }

    private double getStepsPerTick() {
        return bufferSlots / (double) getConfiguredTravelTicks();
    }

    private long getConfiguredTravelTicks() {
        return Math.max(1L, beltLengthBlocks * (long) travelTicksPerBlock);
    }

    private long getClientSyncIntervalTicks() {
        return Math.max(1L, travelTicksPerBlock / 2L);
    }

    private Direction getInputSide() {
        return getFacing(getBlockState()).getOpposite();
    }

    private Direction getOutputSide() {
        return endFacing.getAxis().isHorizontal() ? endFacing : Direction.NORTH;
    }

    private boolean canInsertFromSide(Direction side) {
        return side == getInputSide();
    }

    private boolean canExtractFromSide(Direction side) {
        return side == getOutputSide();
    }

    private BlockPos resolveInputTargetPos() {
        return worldPosition.relative(getInputSide());
    }

    private BlockPos resolveOutputTargetPos() {
        if (endPos == null) {
            return worldPosition.relative(getOutputSide());
        }

        return endPos.relative(getOutputSide());
    }

    private void validateLinkedEndMarker(long gameTime) {
        if (level == null || level.isClientSide || endPos == null) {
            return;
        }
        if (nextEndMarkerValidationTick != Long.MIN_VALUE && gameTime < nextEndMarkerValidationTick) {
            return;
        }
        nextEndMarkerValidationTick = gameTime + 20L;
        ensureLinkedEndMarker();
    }

    private void updateLinkedEndMarker(@Nullable BlockPos previousEndPos) {
        if (level == null || level.isClientSide) {
            return;
        }
        if (previousEndPos != null && !previousEndPos.equals(endPos)) {
            removeLinkedEndMarkerAt(previousEndPos);
        }
        ensureLinkedEndMarker();
    }

    private void ensureLinkedEndMarker() {
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
            conveyorEndBlockEntity.setMasterPos(worldPosition);
        }
    }

    private void removeLinkedEndMarkerAt(BlockPos markerPos) {
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
            if (linkedMaster != null && !worldPosition.equals(linkedMaster)) {
                return;
            }
            conveyorEndBlockEntity.suppressLinkedBreakOnce();
            conveyorEndBlockEntity.setMasterPos(null);
        }

        level.removeBlock(markerPos, false);
    }

    private void markDirtyForSync() {
        if (suppressDirtyCallbacks) {
            return;
        }

        setChanged();
        needsSync = true;
    }

    private boolean syncDirtyItems() {
        if (level == null || level.isClientSide) {
            return true;
        }

        long now = level.getGameTime();
        if (!handoffSyncPending && lastSyncPacketGameTime != Long.MIN_VALUE
                && now - lastSyncPacketGameTime < getClientSyncIntervalTicks()) {
            return false;
        }

        syncRevision++;
        lastSyncPacketGameTime = now;
        return sendUpdateToNearbyPlayers();
    }

    private void syncToClient() {
        if (level == null || level.isClientSide) {
            return;
        }

        syncRevision++;
        lastSyncPacketGameTime = level.getGameTime();
        sendUpdateToNearbyPlayers();
    }

    private boolean isPlayerWithinVisualRange(Level level) {
        Vec3 startPoint = getVisualStartPoint();
        Vec3 endPoint = getVisualEndPoint();
        double maxDistanceSqr = getVisualRangeBlocks() * getVisualRangeBlocks();

        for (Player player : level.players()) {
            if (isEntityWithinVisualRange(player, startPoint, endPoint, maxDistanceSqr)) {
                return true;
            }
        }
        return false;
    }

    private double getVisualRangeBlocks() {
        return VISUAL_RANGE_BLOCKS;
    }

    private boolean sendUpdateToNearbyPlayers() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        ClientboundBlockEntityDataPacket packet = getUpdatePacket();
        if (packet == null) {
            return false;
        }

        Vec3 startPoint = getVisualStartPoint();
        Vec3 endPoint = getVisualEndPoint();
        double maxDistanceSqr = getVisualRangeBlocks() * getVisualRangeBlocks();
        boolean sent = false;

        for (ServerPlayer player : serverLevel.players()) {
            if (!isEntityWithinVisualRange(player, startPoint, endPoint, maxDistanceSqr)) {
                continue;
            }
            player.connection.send(packet);
            sent = true;
        }

        return sent;
    }

    private Vec3 getVisualStartPoint() {
        return new Vec3(
                worldPosition.getX() + 0.5D,
                worldPosition.getY() + 0.5D,
                worldPosition.getZ() + 0.5D
        );
    }

    private Vec3 getVisualEndPoint() {
        BlockPos endPoint = endPos != null ? endPos : worldPosition.relative(getOutputSide());
        return new Vec3(
                endPoint.getX() + 0.5D,
                endPoint.getY() + 0.5D,
                endPoint.getZ() + 0.5D
        );
    }

    private static boolean isEntityWithinVisualRange(Entity entity, Vec3 startPoint, Vec3 endPoint, double maxDistanceSqr) {
        if (entity == null || !entity.isAlive()) {
            return false;
        }
        return entity.distanceToSqr(startPoint.x, startPoint.y, startPoint.z) <= maxDistanceSqr
                || entity.distanceToSqr(endPoint.x, endPoint.y, endPoint.z) <= maxDistanceSqr;
    }

    private void updateClientVisual(PredictedRenderItem item, @Nullable ConveyorBlockEntity previousBelt,
                                    @Nullable ConveyorBlockEntity nextBelt, long now,
                                    double slotsPerTick, double maxCorrection) {
        SplineVisualItemEntity downstream = nextBelt == null ? null : nextBelt.clientVisualItems.get(item.visualKey);
        if (downstream != null && downstream.snapshotOrder > clientSnapshotOrder
                && ItemStack.isSameItemSameTags(downstream.getItem(), item.stack)) {
            // The next belt may receive its handoff snapshot before this belt's removal snapshot.
            SplineVisualItemEntity stale = clientVisualItems.remove(item.visualKey);
            if (stale != null) {
                stale.discard();
            }
            return;
        }

        SplineVisualItemEntity visual = clientVisualItems.get(item.visualKey);
        if ((visual == null || !visual.isAlive()) && previousBelt != null) {
            SplineVisualItemEntity incoming = previousBelt.clientVisualItems.get(item.visualKey);
            if (incoming != null && incoming.isAlive() && clientSnapshotOrder > incoming.snapshotOrder
                    && ItemStack.isSameItemSameTags(incoming.getItem(), item.stack)) {
                if (!incoming.canEnterNextBelt(now)) {
                    return;
                }
                previousBelt.clientVisualItems.remove(item.visualKey);
                incoming.enterBelt(this);
                visual = incoming;
                clientVisualItems.put(item.visualKey, visual);
            }
        }

        if (visual == null || !visual.isAlive()) {
            double startUnits = item.slotUnits <= Math.max(1.0D, slotsPerTick + maxCorrection) ? 0.0D : item.slotUnits;
            Vec3 position = computeVisualPositionForSlotUnits(startUnits);
            visual = new SplineVisualItemEntity(this, level, position, item.stack, startUnits);
            if (!SatiscraftoryClient.addConveyorItemVisual(visual)) {
                clientVisualItems.remove(item.visualKey);
                return;
            }
            clientVisualItems.put(item.visualKey, visual);
        } else if (!ItemStack.isSameItemSameTags(visual.getItem(), item.stack)) {
            visual.setItem(item.stack);
        }

        visual.snapshotOrder = clientSnapshotOrder;
        visual.departureTicks = 0;
        visual.advanceTowards(item.slotUnits, now, slotsPerTick, maxCorrection);
    }

    private void finishDepartingVisuals(Set<Long> activeVisualKeys, boolean directOutput, long now,
                                        double slotsPerTick, double maxCorrection) {
        var iterator = clientVisualItems.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, SplineVisualItemEntity> entry = iterator.next();
            SplineVisualItemEntity visual = entry.getValue();
            if (visual.isAlive()) {
                if (activeVisualKeys.contains(entry.getKey())) {
                    continue;
                }
                if (directOutput || visual.slotUnits >= bufferSlots - Math.max(1.0D, slotsPerTick * 2.0D)) {
                    if (visual.slotUnits < bufferSlots || visual.previousSlotUnits < bufferSlots) {
                        visual.advanceTowards(bufferSlots, now, slotsPerTick, maxCorrection);
                        continue;
                    }
                    if (directOutput && ++visual.departureTicks <= HANDOFF_GRACE_TICKS) {
                        continue;
                    }
                }
            }
            visual.discard();
            iterator.remove();
        }
    }

    private void clearClientVisuals() {
        for (SplineVisualItemEntity visual : clientVisualItems.values()) {
            visual.discard();
        }
        clientVisualItems.clear();
    }

    private Vec3 computeVisualPositionForSlotUnits(double slotUnits) {
        return getRoute().position(slotUnits / bufferSlots)
                .add(worldPosition.getX(), worldPosition.getY() + ITEM_Y_OFFSET, worldPosition.getZ());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static synchronized long allocateGlobalItemId() {
        return NEXT_ITEM_ID++;
    }

    private static synchronized void ensureGlobalItemIdAbove(long minimumExclusive) {
        if (NEXT_ITEM_ID <= minimumExclusive) {
            NEXT_ITEM_ID = minimumExclusive + 1L;
        }
    }

    private long allocateItemId() {
        long id = allocateGlobalItemId();
        return Math.max(1L, id);
    }

    private void advanceNextItemIdFromLoadedItems() {
        long maxId = 0L;
        for (int slot = 0; slot < bufferSlots; slot++) {
            maxId = Math.max(maxId, itemIds[slot]);
        }
        if (maxId > 0L) {
            ensureGlobalItemIdAbove(maxId);
        }
    }

    private Direction fallbackFacing() {
        BlockState state = getBlockState();
        if (state.hasProperty(ConveyorBlock.FACING)) {
            return state.getValue(ConveyorBlock.FACING);
        }
        return Direction.NORTH;
    }

    private static Direction getFacing(BlockState state) {
        if (state.hasProperty(ConveyorBlock.FACING)) {
            return state.getValue(ConveyorBlock.FACING);
        }
        return Direction.NORTH;
    }

    private static int resolveTravelTicksPerBlock(BlockState state) {
        if (state.getBlock() instanceof ConveyorBlock conveyorBlock) {
            return Math.max(1, conveyorBlock.getTravelTicksPerBlock());
        }
        return DEFAULT_TRAVEL_TICKS_PER_BLOCK;
    }

    private void rebuildCapabilities() {
        if (unsidedCapability.isPresent()) {
            unsidedCapability.invalidate();
        }
        for (LazyOptional<IItemHandler> capability : sidedCapabilities.values()) {
            capability.invalidate();
        }
        sidedCapabilities.clear();

        unsidedCapability = LazyOptional.of(() -> new SplineInventoryHandler(null));
        for (Direction direction : Direction.values()) {
            sidedCapabilities.put(direction, LazyOptional.of(() -> new SplineInventoryHandler(direction)));
        }
    }

    private ItemStackHandler createInventory(int slots) {
        return new ItemStackHandler(slots) {
            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }

            @Override
            protected void onContentsChanged(int slot) {
                if (!suppressDirtyCallbacks) {
                    markDirtyForSync();
                }
            }
        };
    }

    private final class SplineInventoryHandler implements IItemHandler {
        @Nullable
        private final Direction side;

        private SplineInventoryHandler(@Nullable Direction side) {
            this.side = side;
        }

        @Override
        public int getSlots() {
            return bufferSlots;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= bufferSlots) {
                return ItemStack.EMPTY;
            }
            return inventory.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < 0 || slot >= bufferSlots) {
                return stack;
            }
            return insertFromCapability(side, slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot < 0 || slot >= bufferSlots) {
                return ItemStack.EMPTY;
            }
            return extractFromCapability(side, slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return !stack.isEmpty();
        }
    }

    private static final class BufferedItem {
        private final ItemStack stack;
        private final long itemId;
        private final double progress;

        private BufferedItem(ItemStack stack, long itemId, double progress) {
            this.stack = stack;
            this.itemId = itemId;
            this.progress = progress;
        }
    }

    private static final class HeadTransfer {
        private final ItemStack stack;
        private final long itemId;

        private HeadTransfer(ItemStack stack, long itemId) {
            this.stack = stack;
            this.itemId = itemId;
        }
    }

    private static final class PredictedQueueItem {
        private final long visualKey;
        private final ItemStack stack;
        private int position;

        private PredictedQueueItem(long visualKey, ItemStack stack, int position) {
            this.visualKey = visualKey;
            this.stack = stack;
            this.position = position;
        }
    }

    private static final class PredictedRenderItem {
        private final long visualKey;
        private final ItemStack stack;
        private final double slotUnits;

        private PredictedRenderItem(long visualKey, ItemStack stack, double slotUnits) {
            this.visualKey = visualKey;
            this.stack = stack;
            this.slotUnits = slotUnits;
        }
    }

    public static final class SplineVisualItemEntity extends ItemEntity {
        // Leave 10% of the angle error after 100 ms, independent of frame rate.
        private static final double TURN_DECAY_PER_NANO = Math.log(10.0D) / 100_000_000.0D;

        private ConveyorBlockEntity conveyor;
        @Nullable
        private ConveyorBlockEntity incomingFrom;
        private double slotsPerIncomingSlot;
        private double previousSlotUnits;
        private double slotUnits;
        private long motionTick = Long.MIN_VALUE;
        private long snapshotOrder;
        private int departureTicks;
        private long lastFacingUpdateNanos = Long.MIN_VALUE;
        private float facingYaw;
        private float facingPitch;

        private SplineVisualItemEntity(ConveyorBlockEntity conveyor, Level level, Vec3 position,
                                       ItemStack stack, double slotUnits) {
            super(Satiscraftory.CONVEYOR_ITEM_VISUAL.get(), level);
            this.conveyor = conveyor;
            this.previousSlotUnits = slotUnits;
            this.slotUnits = slotUnits;
            noPhysics = true;
            setNoGravity(true);
            setNeverPickUp();
            setInvulnerable(true);
            setItem(stack.copy());
            setPos(position.x, position.y, position.z);
            setOldPosAndRot();
        }

        public Vec3 positionOnBelt(float partialTick) {
            double progress = Mth.lerp(partialTick, previousSlotUnits, slotUnits);
            if (progress < 0.0D && incomingFrom != null) {
                return incomingFrom.computeVisualPositionForSlotUnits(
                        incomingFrom.bufferSlots + progress / slotsPerIncomingSlot);
            }
            return conveyor.computeVisualPositionForSlotUnits(progress);
        }

        private boolean canEnterNextBelt(long now) {
            return slotUnits >= 0.0D && (motionTick != now || previousSlotUnits >= 0.0D);
        }

        private void enterBelt(ConveyorBlockEntity next) {
            incomingFrom = conveyor;
            // Negative progress on the new belt measures the distance remaining on the old one.
            double incomingSlotLength = conveyor.getRoute().length() / conveyor.bufferSlots;
            double nextSlotLength = next.getRoute().length() / next.bufferSlots;
            slotsPerIncomingSlot = incomingSlotLength / nextSlotLength;
            previousSlotUnits = (previousSlotUnits - conveyor.bufferSlots) * slotsPerIncomingSlot;
            slotUnits = (slotUnits - conveyor.bufferSlots) * slotsPerIncomingSlot;
            conveyor = next;
        }

        private void advanceTowards(double target, long now, double slotsPerTick, double maxCorrection) {
            if (motionTick != now) {
                setOldPosAndRot();
                previousSlotUnits = slotUnits;
                motionTick = now;
            }

            double forward = slotsPerTick;
            if (previousSlotUnits >= 0.0D) {
                incomingFrom = null;
            } else if (incomingFrom != null) {
                double approachSpeed = incomingFrom.getStepsPerTick() * slotsPerIncomingSlot;
                double timeToEdge = -previousSlotUnits / approachSpeed;
                forward = timeToEdge >= 1.0D ? approachSpeed
                        : -previousSlotUnits + slotsPerTick * (1.0D - timeToEdge);
            }

            slotUnits = previousSlotUnits + clamp(target - previousSlotUnits, -maxCorrection, forward + maxCorrection);
            Vec3 position = positionOnBelt(1.0F);
            setPos(position.x, position.y, position.z);
        }

        @Override
        public void tick() {
            // Client-only visual. Belt code owns movement.
        }

        @Override
        public boolean isPickable() {
            return false;
        }

        public void turnToward(Vec3 offsetToCamera, long nowNanos) {
            double horizontalDistance = Math.hypot(offsetToCamera.x, offsetToCamera.z);
            if (horizontalDistance < 1.0E-5D && Math.abs(offsetToCamera.y) < 1.0E-5D) {
                return;
            }

            // The model's front is +Z. Keep the last yaw when we are directly above or below it.
            float targetYaw = horizontalDistance < 1.0E-5D ? facingYaw
                    : (float) Math.toDegrees(Math.atan2(offsetToCamera.x, offsetToCamera.z));
            float targetPitch = (float) -Math.toDegrees(Math.atan2(offsetToCamera.y, horizontalDistance));
            float blend = lastFacingUpdateNanos == Long.MIN_VALUE ? 1.0F
                    : (float) -Math.expm1(-TURN_DECAY_PER_NANO * (nowNanos - lastFacingUpdateNanos));
            facingYaw = Mth.wrapDegrees(Mth.rotLerp(blend, facingYaw, targetYaw));
            facingPitch = Mth.lerp(blend, facingPitch, targetPitch);
            lastFacingUpdateNanos = nowNanos;
        }

        public float getFacingYaw() {
            return facingYaw;
        }

        public float getFacingPitch() {
            return facingPitch;
        }
    }
}
