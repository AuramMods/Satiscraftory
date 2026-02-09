package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.ConveyorBlock;
import art.arcane.satiscraftory.block.ConveyorEndBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConveyorBlockEntity extends BlockEntity {
    private static final int SLOTS_PER_BLOCK = 3;
    private static final int DEFAULT_TRAVEL_TICKS_PER_BLOCK = 60;
    private static final int LENGTH_SAMPLE_SEGMENTS = 80;
    private static final int MAX_CLIENT_PREDICTION_STEPS = 256;
    private static final double VISUAL_RANGE_BLOCKS = 16.0D;
    private static final double EDGE_OFFSET = 0.5D;
    private static final double ITEM_Y_OFFSET = 0.08D;

    private static final String END_POS_TAG = "end_pos";
    private static final String END_FACING_TAG = "end_facing";
    private static final String BELT_LENGTH_BLOCKS_TAG = "belt_length_blocks";
    private static final String BUFFER_SLOTS_TAG = "buffer_slots";
    private static final String INVENTORY_TAG = "inventory";
    private static final String RENDER_POSITIONS_TAG = "render_positions";
    private static final String ITEM_IDS_TAG = "item_ids";
    private static final String STEP_ACCUMULATOR_TAG = "step_accumulator";
    private static final String TRAVEL_TICKS_PER_BLOCK_TAG = "travel_ticks_per_block";
    private static final String SYNC_REVISION_TAG = "sync_revision";

    private static long NEXT_ITEM_ID = 1L;

    @Nullable
    private BlockPos endPos;
    private Direction endFacing = Direction.NORTH;
    private int beltLengthBlocks = 1;
    private int bufferSlots = SLOTS_PER_BLOCK;
    private int travelTicksPerBlock = DEFAULT_TRAVEL_TICKS_PER_BLOCK;

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
    private transient long nextEndMarkerValidationTick = Long.MIN_VALUE;

    private transient long lastClientAppliedRevision = Long.MIN_VALUE;
    private transient long clientSnapshotGameTime = Long.MIN_VALUE;

    @Nullable
    private transient Method clientPutNonPlayerEntityMethod;
    @Nullable
    private transient Method clientRemoveEntityMethod;
    private transient boolean resolvedClientEntityMethods;
    @Nullable
    private transient Field itemBobOffsField;
    private transient boolean resolvedItemBobField;

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

    public void setEndData(BlockPos endPos, Direction endFacing) {
        BlockPos previousEndPos = this.endPos;
        this.endPos = endPos.immutable();
        this.endFacing = endFacing.getAxis().isHorizontal() ? endFacing : Direction.NORTH;
        recalculateLengthAndResize(false);
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
        tag.putInt(BELT_LENGTH_BLOCKS_TAG, beltLengthBlocks);
        tag.putInt(BUFFER_SLOTS_TAG, bufferSlots);
        tag.put(INVENTORY_TAG, inventory.serializeNBT());
        tag.putIntArray(RENDER_POSITIONS_TAG, renderPositions);
        tag.putLongArray(ITEM_IDS_TAG, itemIds);
        tag.putDouble(STEP_ACCUMULATOR_TAG, stepAccumulator);
        tag.putInt(TRAVEL_TICKS_PER_BLOCK_TAG, travelTicksPerBlock);
        tag.putLong(SYNC_REVISION_TAG, syncRevision);
    }

    @Override
    public void load(CompoundTag tag) {
        long incomingRevision = tag.contains(SYNC_REVISION_TAG) ? tag.getLong(SYNC_REVISION_TAG) : 0L;
        boolean isClient = level != null && level.isClientSide;
        if (isClient && incomingRevision < lastClientAppliedRevision) {
            return;
        }

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
        nextEndMarkerValidationTick = Long.MIN_VALUE;

        normalizeInventory(false);
        recalculateLengthAndResize(false);
        normalizeInventory(false);

        suppressDirtyCallbacks = false;

        advanceNextItemIdFromLoadedItems();

        if (isClient) {
            sanitizeClientLoadedState();
            lastClientAppliedRevision = syncRevision;
            clientSnapshotGameTime = level != null ? level.getGameTime() : Long.MIN_VALUE;
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

    public void dropContents(Level level, BlockPos pos) {
        for (int slot = 0; slot < bufferSlots; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
                renderPositions[slot] = -1;
                itemIds[slot] = 0L;
            }
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

        if (recalculateLengthAndResize(true)) {
            normalizeInventory(true);
        }

        updateStepAccumulator(level.getGameTime());
        int stepBudget = availableStepBudget();
        for (int i = 0; i < stepBudget; i++) {
            runSubStep(level);
            consumeOneStepBudget();
        }

        if (needsSync && setChangedAndSync(true)) {
            needsSync = false;
        }
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
        ClientRenderPrediction prediction = predictClientRenderState(elapsedTicks);

        Set<Long> activeVisualKeys = new HashSet<>();
        for (ClientPredictedRenderItem item : prediction.items()) {
            if (item.stack.isEmpty()) {
                continue;
            }

            activeVisualKeys.add(item.visualKey);
            Vec3 visualPosition = computeVisualPositionForSlotUnits(item.slotUnits);
            updateClientVisual(item.visualKey, item.stack, visualPosition);
        }

        removeStaleClientVisuals(activeVisualKeys);
    }

    private ClientRenderPrediction predictClientRenderState(long elapsedTicks) {
        List<ClientPredictedQueueItem> queue = new ArrayList<>();
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
            queue.add(new ClientPredictedQueueItem(visualKey, stack.copy(), basePosition));
        }

        if (queue.isEmpty()) {
            return new ClientRenderPrediction(List.of());
        }

        double projectedAccumulator = Math.max(0.0D, stepAccumulator + (elapsedTicks * getStepsPerTick()));
        int projectedWholeSteps = (int) Math.floor(projectedAccumulator);
        int simulatedWholeSteps = Math.min(projectedWholeSteps, getMaxClientPredictionSteps());
        double partialProgress = projectedWholeSteps > simulatedWholeSteps
                ? 0.0D
                : clamp(projectedAccumulator - projectedWholeSteps, 0.0D, 1.0D);

        IItemHandler predictedOutput = getOutputContainerHandler();
        for (int step = 0; step < simulatedWholeSteps; step++) {
            simulateClientSubStep(queue, predictedOutput);
        }

        List<ClientPredictedQueueItem> previewQueue = copyClientQueue(queue);
        simulateClientSubStep(previewQueue, predictedOutput);

        Map<Long, Integer> nextPositionsByKey = new HashMap<>();
        for (ClientPredictedQueueItem previewItem : previewQueue) {
            nextPositionsByKey.put(previewItem.visualKey, previewItem.position);
        }

        List<ClientPredictedRenderItem> renderedItems = new ArrayList<>(queue.size());
        for (ClientPredictedQueueItem item : queue) {
            int nextPosition = nextPositionsByKey.getOrDefault(item.visualKey, item.position);
            double interpolated = item.position + ((nextPosition - item.position) * partialProgress) + 0.5D;
            double slotUnits = clamp(interpolated, 0.0D, bufferSlots - 1.0E-6D);
            renderedItems.add(new ClientPredictedRenderItem(item.visualKey, item.stack, slotUnits));
        }

        return new ClientRenderPrediction(renderedItems);
    }

    private void simulateClientSubStep(List<ClientPredictedQueueItem> queue, @Nullable IItemHandler predictedOutput) {
        tryClientTransferHead(queue, predictedOutput);
        advanceClientQueueOneStep(queue);
    }

    private void tryClientTransferHead(List<ClientPredictedQueueItem> queue, @Nullable IItemHandler predictedOutput) {
        if (queue.isEmpty()) {
            return;
        }

        ClientPredictedQueueItem head = queue.get(0);
        if (head.position < bufferSlots - 1) {
            return;
        }
        if (!canLikelyTransferHead(predictedOutput, head.stack)) {
            return;
        }

        queue.remove(0);
    }

    private void advanceClientQueueOneStep(List<ClientPredictedQueueItem> queue) {
        if (queue.isEmpty()) {
            return;
        }

        boolean[] taken = new boolean[bufferSlots];
        for (ClientPredictedQueueItem item : queue) {
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

    private static List<ClientPredictedQueueItem> copyClientQueue(List<ClientPredictedQueueItem> source) {
        List<ClientPredictedQueueItem> copy = new ArrayList<>(source.size());
        for (ClientPredictedQueueItem item : source) {
            copy.add(new ClientPredictedQueueItem(item.visualKey, item.stack, item.position));
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
            ItemStack single = stack.copy();
            single.setCount(1);
            suppressDirtyCallbacks = true;
            inventory.setStackInSlot(slot, single);
            suppressDirtyCallbacks = false;
            renderPositions[slot] = 0;
            itemIds[slot] = allocateItemId();
            markDirtyForSync();
        }

        return true;
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

        ItemStack single = stack.copy();
        single.setCount(1);
        if (!simulate) {
            suppressDirtyCallbacks = true;
            inventory.setStackInSlot(slot, single);
            suppressDirtyCallbacks = false;
            renderPositions[slot] = 0;
            itemIds[slot] = allocateItemId();
            markDirtyForSync();
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
        for (int slot = 0; slot < bufferSlots; slot++) {
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

    private boolean recalculateLengthAndResize(boolean dropOverflow) {
        int computedLength = computeBeltLengthBlocks();
        computedLength = Math.max(1, computedLength);

        boolean changed = false;
        if (beltLengthBlocks != computedLength) {
            beltLengthBlocks = computedLength;
            changed = true;
        }

        int desiredSlots = Math.max(1, beltLengthBlocks * SLOTS_PER_BLOCK);
        if (desiredSlots != bufferSlots) {
            resizeBuffer(desiredSlots, dropOverflow);
            changed = true;
        }

        return changed;
    }

    private int computeBeltLengthBlocks() {
        if (endPos == null) {
            return 1;
        }

        Direction startFacing = getFacing(getBlockState());
        Vec3 start = anchorToEdge(new Vec3(
                worldPosition.getX() + 0.5D,
                worldPosition.getY(),
                worldPosition.getZ() + 0.5D
        ), startFacing.getOpposite());
        Vec3 end = anchorToEdge(new Vec3(
                endPos.getX() + 0.5D,
                endPos.getY(),
                endPos.getZ() + 0.5D
        ), endFacing);

        Vec3 startForward = directionVector(startFacing);
        Vec3 endForward = directionVector(endFacing);

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

    private void resizeBuffer(int desiredSlots, boolean dropOverflow) {
        desiredSlots = Math.max(1, desiredSlots);
        if (desiredSlots == bufferSlots) {
            return;
        }

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
            if (write >= bufferSlots) {
                if (dropOverflow && level != null && !level.isClientSide) {
                    Containers.dropItemStack(level,
                            worldPosition.getX() + 0.5D,
                            worldPosition.getY() + 0.5D,
                            worldPosition.getZ() + 0.5D,
                            item.stack.copy());
                }
                continue;
            }

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

    private boolean setChangedAndSync(boolean syncToClient) {
        setChanged();
        if (!syncToClient || level == null || level.isClientSide) {
            return true;
        }

        long now = level.getGameTime();
        if (lastSyncPacketGameTime != Long.MIN_VALUE && now - lastSyncPacketGameTime < getClientSyncIntervalTicks()) {
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

    private void updateClientVisual(long visualKey, ItemStack sourceStack, Vec3 position) {
        if (level == null || !level.isClientSide) {
            return;
        }

        ItemStack single = sourceStack.copy();
        single.setCount(1);
        SplineVisualItemEntity visual = clientVisualItems.get(visualKey);
        if (visual == null || !visual.isAlive()) {
            SplineVisualItemEntity created = new SplineVisualItemEntity(level, position.x, position.y, position.z, single);
            if (!spawnClientVisual(visualKey, created)) {
                clientVisualItems.remove(visualKey);
                return;
            }
            setVisualBobPhase(created, 0.0F);
            visual = created;
            clientVisualItems.put(visualKey, created);
        }
        setVisualBobPhase(visual, 0.0F);

        if (!ItemStack.isSameItemSameTags(visual.getItem(), single)) {
            visual.setItem(single);
        }

        visual.setOldPosAndRot();
        visual.moveTo(position.x, position.y, position.z, 0.0F, 0.0F);
        visual.setDeltaMovement(Vec3.ZERO);
    }

    private void removeStaleClientVisuals(Set<Long> activeVisualKeys) {
        if (clientVisualItems.isEmpty()) {
            return;
        }

        List<Long> staleKeys = new ArrayList<>();
        for (Map.Entry<Long, SplineVisualItemEntity> entry : clientVisualItems.entrySet()) {
            SplineVisualItemEntity visual = entry.getValue();
            if (visual == null || !visual.isAlive() || !activeVisualKeys.contains(entry.getKey())) {
                if (visual != null) {
                    if (level != null && level.isClientSide) {
                        removeClientEntityById(visual.getId());
                    }
                    visual.discard();
                }
                staleKeys.add(entry.getKey());
            }
        }

        for (Long key : staleKeys) {
            clientVisualItems.remove(key);
        }
    }

    private void clearClientVisuals() {
        if (clientVisualItems.isEmpty()) {
            return;
        }

        for (SplineVisualItemEntity visual : clientVisualItems.values()) {
            if (visual == null) {
                continue;
            }
            if (level != null && level.isClientSide) {
                removeClientEntityById(visual.getId());
            }
            visual.discard();
        }
        clientVisualItems.clear();
    }

    private Vec3 computeVisualPosition(double progress) {
        Direction startFacing = getFacing(getBlockState());
        Direction outputSide = getOutputSide();

        Vec3 start = anchorToEdge(new Vec3(
                worldPosition.getX() + 0.5D,
                worldPosition.getY() + ITEM_Y_OFFSET,
                worldPosition.getZ() + 0.5D
        ), startFacing.getOpposite());

        Vec3 endCenter;
        if (endPos != null) {
            endCenter = new Vec3(
                    endPos.getX() + 0.5D,
                    endPos.getY() + ITEM_Y_OFFSET,
                    endPos.getZ() + 0.5D
            );
        } else {
            endCenter = new Vec3(
                    worldPosition.getX() + 0.5D + startFacing.getStepX(),
                    worldPosition.getY() + ITEM_Y_OFFSET,
                    worldPosition.getZ() + 0.5D + startFacing.getStepZ()
            );
        }
        Vec3 end = anchorToEdge(endCenter, outputSide);

        Vec3 startForward = directionVector(startFacing);
        Vec3 endForward = directionVector(outputSide);

        Vec3 flatDelta = new Vec3(end.x - start.x, 0.0D, end.z - start.z);
        double horizontalDistance = Math.sqrt(flatDelta.lengthSqr());
        double tangentLength = Math.max(0.85D, Math.min(4.0D, horizontalDistance * 0.45D));

        Vec3 c1 = start.add(startForward.scale(tangentLength));
        Vec3 c2 = end.subtract(endForward.scale(tangentLength));

        return sampleBezier(start, c1, c2, end, clamp(progress, 0.0D, 1.0D));
    }

    private Vec3 computeVisualPositionForSlotUnits(double slotUnits) {
        double position = Math.max(0.0D, slotUnits);
        position = Math.min(bufferSlots - 1.0E-6D, position);
        double progress = clamp(position / bufferSlots, 0.0D, 0.999999D);
        return computeVisualPosition(progress);
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

    private boolean spawnClientVisual(long visualKey, SplineVisualItemEntity visual) {
        if (level == null || !level.isClientSide) {
            return false;
        }

        int id = visualEntityId(visualKey);
        visual.setId(id);

        if (addClientEntityById(id, visual)) {
            return true;
        }

        return level.addFreshEntity(visual);
    }

    private int visualEntityId(long visualKey) {
        long seed = worldPosition.asLong() ^ (0x9E3779B97F4A7C15L * visualKey);
        int hash = (int) (seed ^ (seed >>> 32));
        return hash | Integer.MIN_VALUE;
    }

    private boolean addClientEntityById(int id, Entity entity) {
        if (level == null || !level.isClientSide) {
            return false;
        }

        resolveClientEntityMethods();
        if (clientPutNonPlayerEntityMethod == null) {
            return false;
        }

        try {
            clientPutNonPlayerEntityMethod.invoke(level, id, entity);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void removeClientEntityById(int id) {
        if (level == null || !level.isClientSide) {
            return;
        }

        resolveClientEntityMethods();
        if (clientRemoveEntityMethod == null) {
            return;
        }

        try {
            clientRemoveEntityMethod.invoke(level, id, Entity.RemovalReason.DISCARDED);
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    private void resolveClientEntityMethods() {
        if (resolvedClientEntityMethods || level == null) {
            return;
        }

        resolvedClientEntityMethods = true;
        Class<?> levelClass = level.getClass();

        try {
            clientPutNonPlayerEntityMethod = levelClass.getMethod("putNonPlayerEntity", int.class, Entity.class);
        } catch (NoSuchMethodException ignored) {
            clientPutNonPlayerEntityMethod = null;
        }

        try {
            clientRemoveEntityMethod = levelClass.getMethod("removeEntity", int.class, Entity.RemovalReason.class);
        } catch (NoSuchMethodException ignored) {
            clientRemoveEntityMethod = null;
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

    private static final class ClientRenderPrediction {
        private final List<ClientPredictedRenderItem> items;

        private ClientRenderPrediction(List<ClientPredictedRenderItem> items) {
            this.items = items;
        }

        private List<ClientPredictedRenderItem> items() {
            return items;
        }
    }

    private static final class ClientPredictedQueueItem {
        private final long visualKey;
        private final ItemStack stack;
        private int position;

        private ClientPredictedQueueItem(long visualKey, ItemStack stack, int position) {
            this.visualKey = visualKey;
            this.stack = stack;
            this.position = position;
        }
    }

    private static final class ClientPredictedRenderItem {
        private final long visualKey;
        private final ItemStack stack;
        private final double slotUnits;

        private ClientPredictedRenderItem(long visualKey, ItemStack stack, double slotUnits) {
            this.visualKey = visualKey;
            this.stack = stack;
            this.slotUnits = slotUnits;
        }
    }

    private static class SplineVisualItemEntity extends ItemEntity {
        private SplineVisualItemEntity(Level level, double x, double y, double z, ItemStack stack) {
            super(level, x, y, z, stack.copy());
            this.noPhysics = true;
            this.setNoGravity(true);
            this.setNeverPickUp();
            this.setPickUpDelay(32767);
            this.setInvulnerable(true);
            this.setDeltaMovement(Vec3.ZERO);
            this.setOldPosAndRot();
        }

        @Override
        public void tick() {
            // Client-only visual. Belt code owns movement.
        }

        @Override
        public boolean isPickable() {
            return false;
        }

        @Override
        public float getSpin(float partialTicks) {
            return 0.0F;
        }
    }

    private void setVisualBobPhase(SplineVisualItemEntity visual, float bobOffs) {
        if (visual == null) {
            return;
        }

        if (!resolvedItemBobField) {
            resolvedItemBobField = true;
            try {
                itemBobOffsField = ItemEntity.class.getDeclaredField("bobOffs");
            } catch (NoSuchFieldException first) {
                try {
                    itemBobOffsField = ItemEntity.class.getDeclaredField("f_31983_");
                } catch (NoSuchFieldException ignored) {
                    itemBobOffsField = null;
                }
            }
            if (itemBobOffsField != null) {
                itemBobOffsField.setAccessible(true);
            }
        }

        if (itemBobOffsField == null) {
            return;
        }

        try {
            itemBobOffsField.setFloat(visual, bobOffs);
        } catch (IllegalAccessException ignored) {
            // Fallback is vanilla bob behavior.
        }
    }
}
