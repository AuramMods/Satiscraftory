package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.ConveyorStraightBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConveyorStraightBlockEntity extends BlockEntity {
    private static final int BUFFER_SLOTS = 5;
    private static final double VISUAL_RANGE_BLOCKS = 10.0D;
    private static final String INVENTORY_TAG = "inventory";
    private static final String RENDER_POSITIONS_TAG = "render_positions";
    private static final String ITEM_IDS_TAG = "item_ids";
    private static final String STEP_ACCUMULATOR_TAG = "step_accumulator";
    private static final String SYNC_REVISION_TAG = "sync_revision";

    private static long NEXT_ITEM_ID = 1L;

    private final long[] itemIds = new long[BUFFER_SLOTS];
    private final int[] renderPositions = new int[BUFFER_SLOTS];
    private final Map<Long, ConveyorVisualItemEntity> clientVisualItems = new HashMap<>();
    private long lastNetworkProcessTick = Long.MIN_VALUE;
    private long lastAccumulatorTick = Long.MIN_VALUE;
    private long lastSyncPacketGameTime = Long.MIN_VALUE;
    private double stepAccumulator;
    private long syncRevision;
    private boolean needsSync;
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

    private final ItemStackHandler inventory = new ItemStackHandler(BUFFER_SLOTS) {
        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }

            int tailSlot = findFirstEmptySlot();
            if (tailSlot < 0 || slot != tailSlot || !canAcceptNewItemAtStep()) {
                return stack;
            }

            ItemStack single = stack.copy();
            single.setCount(1);
            ItemStack remaining = super.insertItem(slot, single, simulate);
            if (!remaining.isEmpty()) {
                return stack;
            }

            if (!simulate) {
                renderPositions[slot] = 0;
                itemIds[slot] = allocateItemId();
                markDirtyForSync();
            }

            ItemStack leftover = stack.copy();
            leftover.shrink(1);
            return leftover;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != 0 || amount <= 0 || !hasHeadAtOutput()) {
                return ItemStack.EMPTY;
            }

            ItemStack extracted = super.extractItem(0, 1, simulate);
            if (!simulate && !extracted.isEmpty()) {
                renderPositions[0] = -1;
                itemIds[0] = 0L;
                compactQueue();
                markDirtyForSync();
            }
            return extracted;
        }

        @Override
        protected void onContentsChanged(int slot) {
            markDirtyForSync();
        }
    };

    private LazyOptional<IItemHandler> inventoryCapability = LazyOptional.of(() -> inventory);

    public ConveyorStraightBlockEntity(BlockPos pos, BlockState blockState) {
        super(Satiscraftory.CONVEYOR_BLOCK_ENTITY.get(), pos, blockState);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        inventoryCapability.invalidate();
    }

    @Override
    public void setRemoved() {
        clearClientVisuals();
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        tag.put(INVENTORY_TAG, inventory.serializeNBT());
        tag.putIntArray(RENDER_POSITIONS_TAG, renderPositions);
        tag.putLongArray(ITEM_IDS_TAG, itemIds);
        tag.putDouble(STEP_ACCUMULATOR_TAG, stepAccumulator);
        tag.putLong(SYNC_REVISION_TAG, syncRevision);
        super.saveAdditional(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        long incomingRevision = tag.contains(SYNC_REVISION_TAG) ? tag.getLong(SYNC_REVISION_TAG) : 0L;
        boolean isClient = level != null && level.isClientSide;
        if (isClient && incomingRevision < lastClientAppliedRevision) {
            return;
        }

        super.load(tag);
        Arrays.fill(itemIds, 0L);
        Arrays.fill(renderPositions, -1);
        if (tag.contains(INVENTORY_TAG)) {
            inventory.deserializeNBT(tag.getCompound(INVENTORY_TAG));
        }
        if (tag.contains(RENDER_POSITIONS_TAG)) {
            int[] loadedRenderPositions = tag.getIntArray(RENDER_POSITIONS_TAG);
            for (int slot = 0; slot < Math.min(loadedRenderPositions.length, renderPositions.length); slot++) {
                renderPositions[slot] = clampRenderPosition(loadedRenderPositions[slot]);
            }
        }
        if (tag.contains(ITEM_IDS_TAG)) {
            long[] loadedItemIds = tag.getLongArray(ITEM_IDS_TAG);
            System.arraycopy(loadedItemIds, 0, itemIds, 0, Math.min(loadedItemIds.length, itemIds.length));
        }
        if (tag.contains(STEP_ACCUMULATOR_TAG)) {
            stepAccumulator = clamp(tag.getDouble(STEP_ACCUMULATOR_TAG), 0.0D, 64.0D);
        } else {
            stepAccumulator = 0.0D;
        }
        syncRevision = Math.max(0L, incomingRevision);
        lastNetworkProcessTick = Long.MIN_VALUE;
        lastAccumulatorTick = Long.MIN_VALUE;
        lastSyncPacketGameTime = Long.MIN_VALUE;
        needsSync = false;
        advanceNextItemIdFromLoadedItems();
        if (!isClient) {
            normalizeInventory();
        } else {
            sanitizeClientLoadedState();
            lastClientAppliedRevision = syncRevision;
            clientSnapshotGameTime = level != null ? level.getGameTime() : Long.MIN_VALUE;
        }
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        this.load(tag);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            load(tag);
        }
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return inventoryCapability.cast();
        }
        return super.getCapability(cap, side);
    }

    public void dropContents(Level level, BlockPos pos) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
                renderPositions[slot] = -1;
                itemIds[slot] = 0L;
            }
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ConveyorStraightBlockEntity conveyor) {
        conveyor.tickServer(level, pos, state);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, ConveyorStraightBlockEntity conveyor) {
        conveyor.tickClient(level, pos, state);
    }

    private void tickServer(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ConveyorStraightBlock)) {
            return;
        }

        long now = level.getGameTime();
        if (lastNetworkProcessTick == now) {
            if (needsSync && setChangedAndSync(true)) {
                needsSync = false;
            }
            return;
        }

        processConnectedNetwork(level, now);
    }

    private void tickClient(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ConveyorStraightBlock)) {
            clearClientVisuals();
            return;
        }

        if (!isPlayerWithinVisualRange(level, pos)) {
            clearClientVisuals();
            return;
        }

        long now = level.getGameTime();
        if (clientSnapshotGameTime == Long.MIN_VALUE) {
            clientSnapshotGameTime = now;
        }
        long elapsedTicks = Math.max(0L, now - clientSnapshotGameTime);

        Set<Long> activeVisualKeys = new HashSet<>();
        for (int slot = 0; slot < BUFFER_SLOTS; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }

            int basePosition = clampRenderPosition(renderPositions[slot]);
            if (basePosition < 0) {
                continue;
            }

            long visualKey = itemIds[slot] > 0L ? itemIds[slot] : -(slot + 1L);
            activeVisualKeys.add(visualKey);

            Vec3 visualPosition = computeDeadReckonedVisualPosition(basePosition + 0.5D, elapsedTicks);
            updateClientVisual(visualKey, stack, visualPosition);
        }

        removeStaleClientVisuals(activeVisualKeys);
    }

    private void processConnectedNetwork(Level level, long now) {
        List<ConveyorStraightBlockEntity> network = collectConnectedNetwork(level);
        if (network.isEmpty()) {
            return;
        }

        Map<ConveyorStraightBlockEntity, Integer> stepBudgets = new HashMap<>();
        int maxBudget = 0;

        for (ConveyorStraightBlockEntity belt : network) {
            belt.lastNetworkProcessTick = now;
            if (belt.normalizeInventory()) {
                belt.markDirtyForSync();
            }
            belt.updateStepAccumulator(now);
            int budget = belt.availableStepBudget();
            if (budget > 0) {
                stepBudgets.put(belt, budget);
                maxBudget = Math.max(maxBudget, budget);
            }
        }

        for (int round = 0; round < maxBudget; round++) {
            List<ConveyorStraightBlockEntity> dueBelts = new ArrayList<>();
            for (ConveyorStraightBlockEntity belt : network) {
                if (stepBudgets.getOrDefault(belt, 0) > 0) {
                    dueBelts.add(belt);
                }
            }

            if (dueBelts.isEmpty()) {
                break;
            }

            runNetworkSubStep(level, dueBelts);
            for (ConveyorStraightBlockEntity belt : dueBelts) {
                int remaining = stepBudgets.getOrDefault(belt, 0) - 1;
                if (remaining <= 0) {
                    stepBudgets.remove(belt);
                } else {
                    stepBudgets.put(belt, remaining);
                }
                belt.consumeOneStepBudget();
            }
        }

        for (ConveyorStraightBlockEntity belt : network) {
            if (belt.needsSync && belt.setChangedAndSync(true)) {
                belt.needsSync = false;
            }
        }
    }

    private List<ConveyorStraightBlockEntity> collectConnectedNetwork(Level level) {
        Set<ConveyorStraightBlockEntity> visited = new HashSet<>();
        ArrayDeque<ConveyorStraightBlockEntity> queue = new ArrayDeque<>();
        queue.add(this);

        while (!queue.isEmpty()) {
            ConveyorStraightBlockEntity current = queue.removeFirst();
            if (current == null || current.level != level || current.isRemoved() || !visited.add(current)) {
                continue;
            }

            ConveyorStraightBlockEntity output = current.getStrictOutputConveyor();
            if (output != null && output.level == level && !output.isRemoved()) {
                queue.add(output);
            }

            ConveyorStraightBlockEntity input = current.getStrictInputConveyor();
            if (input != null && input.level == level && !input.isRemoved()) {
                queue.add(input);
            }
        }

        List<ConveyorStraightBlockEntity> network = new ArrayList<>(visited);
        network.sort(Comparator.comparingLong(belt -> belt.worldPosition.asLong()));
        return network;
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

        stepAccumulator = Math.min(64.0D, stepAccumulator + (elapsed * getStepsPerTick()));
    }

    private int availableStepBudget() {
        return (int) Math.floor(stepAccumulator);
    }

    private void consumeOneStepBudget() {
        stepAccumulator = Math.max(0.0D, stepAccumulator - 1.0D);
    }

    private double getStepsPerTick() {
        return getStepsPerTickForState(getBlockState());
    }

    private long getConfiguredTravelTicks() {
        return getConfiguredTravelTicks(getBlockState());
    }

    private double getStepsPerTickForState(BlockState state) {
        return BUFFER_SLOTS / (double) getConfiguredTravelTicks(state);
    }

    private long getConfiguredTravelTicks(BlockState state) {
        if (state.getBlock() instanceof ConveyorStraightBlock conveyor) {
            return Math.max(1L, conveyor.getTravelTicks(state));
        }
        return 1L;
    }

    private long getClientSyncIntervalTicks() {
        return Math.max(1L, getConfiguredTravelTicks());
    }

    private void runNetworkSubStep(Level level, List<ConveyorStraightBlockEntity> dueBelts) {
        Set<ConveyorStraightBlockEntity> dueSet = new HashSet<>(dueBelts);
        Map<ConveyorStraightBlockEntity, ConveyorStraightBlockEntity> conveyorOutputs = new HashMap<>();
        Map<ConveyorStraightBlockEntity, IItemHandler> containerOutputs = new HashMap<>();
        Set<ConveyorStraightBlockEntity> tentativePops = new HashSet<>();

        for (ConveyorStraightBlockEntity source : dueBelts) {
            ItemStack head = source.peekHeadSingle();
            if (head.isEmpty()) {
                continue;
            }

            ConveyorStraightBlockEntity outputConveyor = source.getStrictOutputConveyor();
            if (outputConveyor != null) {
                conveyorOutputs.put(source, outputConveyor);
                tentativePops.add(source);
                continue;
            }

            IItemHandler outputHandler = source.getOutputContainerHandler();
            if (outputHandler != null && source.canInsertIntoHandler(outputHandler, head)) {
                containerOutputs.put(source, outputHandler);
                tentativePops.add(source);
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            Map<ConveyorStraightBlockEntity, ShiftPreview> previews = new HashMap<>();
            for (ConveyorStraightBlockEntity belt : dueBelts) {
                previews.put(belt, belt.previewAfterPopAndShift(tentativePops.contains(belt)));
            }

            for (ConveyorStraightBlockEntity source : dueBelts) {
                if (!tentativePops.contains(source)) {
                    continue;
                }

                ConveyorStraightBlockEntity target = conveyorOutputs.get(source);
                if (target == null) {
                    continue;
                }

                if (!canAcceptConveyorTransfer(target, dueSet, previews)) {
                    tentativePops.remove(source);
                    changed = true;
                }
            }
        }

        Set<ConveyorStraightBlockEntity> reservedTargets = new HashSet<>();
        Map<ConveyorStraightBlockEntity, ConveyorTransfer> incomingConveyorTransfers = new HashMap<>();
        for (ConveyorStraightBlockEntity source : dueBelts) {
            if (!tentativePops.contains(source)) {
                continue;
            }

            ConveyorStraightBlockEntity target = conveyorOutputs.get(source);
            if (target == null) {
                continue;
            }

            if (!reservedTargets.add(target)) {
                continue;
            }

            HeadTransfer moving = source.popHeadForTransfer();
            if (moving == null || moving.stack.isEmpty()) {
                continue;
            }

            incomingConveyorTransfers.put(target, new ConveyorTransfer(target, moving.stack, moving.itemId));
        }

        for (ConveyorStraightBlockEntity source : dueBelts) {
            if (!tentativePops.contains(source) || conveyorOutputs.containsKey(source)) {
                continue;
            }

            IItemHandler outputHandler = containerOutputs.get(source);
            ItemStack moving = source.peekHeadSingle();
            if (outputHandler == null || moving.isEmpty()) {
                continue;
            }

            if (insertIntoHandler(outputHandler, moving, false).isEmpty()) {
                source.popHeadForTransfer();
            }
        }

        for (ConveyorStraightBlockEntity belt : dueBelts) {
            belt.advanceItemsOneStep();
        }

        for (ConveyorTransfer transfer : incomingConveyorTransfers.values()) {
            if (!transfer.target.enqueueItem(transfer.stack, transfer.itemId, false)) {
                Containers.dropItemStack(
                        level,
                        transfer.target.worldPosition.getX() + 0.5D,
                        transfer.target.worldPosition.getY() + 0.5D,
                        transfer.target.worldPosition.getZ() + 0.5D,
                        transfer.stack
                );
            }
        }

        for (ConveyorStraightBlockEntity belt : dueBelts) {
            belt.pullFromInputContainer();
        }
    }

    private static boolean canAcceptConveyorTransfer(ConveyorStraightBlockEntity target,
                                                     Set<ConveyorStraightBlockEntity> dueSet,
                                                     Map<ConveyorStraightBlockEntity, ShiftPreview> previews) {
        if (!dueSet.contains(target)) {
            return target.canAcceptNewItemAtStep();
        }

        ShiftPreview preview = previews.get(target);
        return preview != null && preview.pos0Empty && preview.itemCount < BUFFER_SLOTS;
    }

    private boolean pullFromInputContainer() {
        if (level == null || !canAcceptNewItemAtStep()) {
            return false;
        }

        BlockState state = getBlockState();
        Direction inputDirection = getInputDirection(state);
        BlockPos inputPos = resolveInputTargetPos(state);
        BlockEntity inputBlockEntity = level.getBlockEntity(inputPos);
        if (inputBlockEntity == null || inputBlockEntity instanceof ConveyorStraightBlockEntity) {
            return false;
        }

        IItemHandler handler = getItemHandler(inputBlockEntity, inputDirection.getOpposite());
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

    private boolean canInsertIntoHandler(IItemHandler handler, ItemStack stack) {
        return insertIntoHandler(handler, stack, true).isEmpty();
    }

    private static ItemStack insertIntoHandler(IItemHandler handler, ItemStack stack, boolean simulate) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = handler.insertItem(slot, remaining, simulate);
        }
        return remaining;
    }

    private IItemHandler getItemHandler(BlockEntity blockEntity, Direction side) {
        LazyOptional<IItemHandler> sided = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side);
        if (sided.isPresent()) {
            return sided.orElse(null);
        }
        LazyOptional<IItemHandler> unsided = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null);
        return unsided.orElse(null);
    }

    @Nullable
    private IItemHandler getOutputContainerHandler() {
        if (level == null) {
            return null;
        }

        BlockState state = getBlockState();
        BlockPos outputPos = resolveOutputTargetPos(state);
        BlockEntity outputBlockEntity = level.getBlockEntity(outputPos);
        if (outputBlockEntity == null || outputBlockEntity instanceof ConveyorStraightBlockEntity) {
            return null;
        }

        Direction outputDirection = getOutputDirection(state);
        return getItemHandler(outputBlockEntity, outputDirection.getOpposite());
    }

    @Nullable
    private ConveyorStraightBlockEntity getStrictOutputConveyor() {
        if (level == null) {
            return null;
        }

        BlockState state = getBlockState();
        BlockPos outputPos = resolveOutputTargetPos(state);
        BlockEntity outputBlockEntity = level.getBlockEntity(outputPos);
        if (!(outputBlockEntity instanceof ConveyorStraightBlockEntity outputConveyor)) {
            return null;
        }

        return outputConveyor.acceptsInputFromStrict(worldPosition) ? outputConveyor : null;
    }

    @Nullable
    private ConveyorStraightBlockEntity getStrictInputConveyor() {
        if (level == null) {
            return null;
        }

        BlockState state = getBlockState();
        BlockPos inputPos = resolveInputTargetPos(state);
        BlockEntity inputBlockEntity = level.getBlockEntity(inputPos);
        if (!(inputBlockEntity instanceof ConveyorStraightBlockEntity inputConveyor)) {
            return null;
        }

        if (!acceptsInputFromStrict(inputConveyor.worldPosition)) {
            return null;
        }

        return inputConveyor.outputsToStrict(worldPosition) ? inputConveyor : null;
    }

    private boolean enqueueItem(ItemStack stack, boolean simulate) {
        return enqueueItem(stack, 0L, simulate);
    }

    private boolean enqueueItem(ItemStack stack, long itemId, boolean simulate) {
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
            inventory.setStackInSlot(slot, single);
            renderPositions[slot] = 0;
            itemIds[slot] = itemId > 0L ? itemId : allocateItemId();
            markDirtyForSync();
        }
        return true;
    }

    private int findFirstEmptySlot() {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private boolean compactQueue() {
        boolean changed = false;
        int write = 0;
        for (int read = 0; read < inventory.getSlots(); read++) {
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

        for (int slot = write; slot < inventory.getSlots(); slot++) {
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
        return changed;
    }

    private boolean normalizeInventory() {
        boolean changed = compactQueue();

        for (int slot = 0; slot < inventory.getSlots(); slot++) {
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

        if (compactQueue()) {
            changed = true;
        }
        if (normalizeRenderPositions(countNonEmptySlots())) {
            changed = true;
        }

        return changed;
    }

    private void sanitizeClientLoadedState() {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
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
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
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
        return clampRenderPosition(renderPositions[0]) >= BUFFER_SLOTS - 1;
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
        inventory.setStackInSlot(0, ItemStack.EMPTY);
        renderPositions[0] = -1;
        itemIds[0] = 0L;
        compactQueue();
        markDirtyForSync();
        return new HeadTransfer(single, itemId);
    }

    private ShiftPreview previewAfterPopAndShift(boolean popHead) {
        boolean[] occupied = new boolean[BUFFER_SLOTS];
        int count = 0;

        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }

            int position = clampRenderPosition(renderPositions[slot]);
            if (position < 0) {
                position = 0;
            }
            if (!occupied[position]) {
                occupied[position] = true;
                count++;
            }
        }

        if (popHead && hasHeadAtOutput()) {
            int headPosition = clampRenderPosition(renderPositions[0]);
            if (headPosition < 0) {
                headPosition = BUFFER_SLOTS - 1;
            }
            headPosition = Math.min(BUFFER_SLOTS - 1, headPosition);
            if (occupied[headPosition]) {
                occupied[headPosition] = false;
                count = Math.max(0, count - 1);
            }
        }

        for (int position = BUFFER_SLOTS - 2; position >= 0; position--) {
            if (occupied[position] && !occupied[position + 1]) {
                occupied[position] = false;
                occupied[position + 1] = true;
            }
        }

        return new ShiftPreview(!occupied[0], count);
    }

    private boolean canAcceptNewItemAtStep() {
        return findFirstEmptySlot() >= 0 && !hasItemAtPosition(0);
    }

    private boolean hasItemAtPosition(int position) {
        int clampedPosition = clampRenderPosition(position);
        if (clampedPosition < 0) {
            return false;
        }

        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty() && renderPositions[slot] == clampedPosition) {
                return true;
            }
        }
        return false;
    }

    private boolean advanceItemsOneStep() {
        boolean changed = false;
        int[] nextPositions = new int[BUFFER_SLOTS];
        Arrays.fill(nextPositions, -1);

        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                continue;
            }

            int current = clampRenderPosition(renderPositions[slot]);
            if (current < 0) {
                current = 0;
            }
            int desired = Math.min(BUFFER_SLOTS - 1, current + 1);
            int next = isPositionTaken(nextPositions, desired) ? current : desired;
            nextPositions[slot] = next;
            if (next != renderPositions[slot]) {
                changed = true;
            }
        }

        if (!changed) {
            return false;
        }

        for (int slot = 0; slot < BUFFER_SLOTS; slot++) {
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
        int previousMaximum = BUFFER_SLOTS - 1;
        int seen = 0;

        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                if (renderPositions[slot] != -1) {
                    renderPositions[slot] = -1;
                    changed = true;
                }
                continue;
            }

            int remaining = Math.max(0, nonEmptyCount - seen - 1);
            int lower = Math.min(BUFFER_SLOTS - 1, remaining);
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

    private double getRenderProgressForSlot(int slot) {
        if (inventory.getStackInSlot(slot).isEmpty()) {
            return 0.0D;
        }

        int position = clampRenderPosition(renderPositions[slot]);
        if (position < 0) {
            return 0.0D;
        }

        // Half-padding on both ends: position i maps to (i + 0.5) / N.
        return clamp((position + 0.5D) / BUFFER_SLOTS, 0.0D, 1.0D);
    }

    private int clampRenderPosition(int position) {
        if (position < 0) {
            return -1;
        }
        return Math.min(BUFFER_SLOTS - 1, position);
    }

    private void markDirtyForSync() {
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

    private boolean isPlayerWithinVisualRange(Level level, BlockPos pos) {
        double centerX = pos.getX() + 0.5D;
        double centerY = pos.getY() + 0.5D;
        double centerZ = pos.getZ() + 0.5D;
        double maxDistanceSqr = getVisualRangeBlocks() * getVisualRangeBlocks();

        for (Player player : level.players()) {
            if (player.isAlive() && player.distanceToSqr(centerX, centerY, centerZ) <= maxDistanceSqr) {
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

        double centerX = worldPosition.getX() + 0.5D;
        double centerY = worldPosition.getY() + 0.5D;
        double centerZ = worldPosition.getZ() + 0.5D;
        double maxDistanceSqr = getVisualRangeBlocks() * getVisualRangeBlocks();
        boolean sent = false;

        for (ServerPlayer player : serverLevel.players()) {
            if (!player.isAlive()) {
                continue;
            }
            if (player.distanceToSqr(centerX, centerY, centerZ) > maxDistanceSqr) {
                continue;
            }
            player.connection.send(packet);
            sent = true;
        }

        return sent;
    }

    private void updateClientVisual(long visualKey, ItemStack sourceStack, Vec3 position) {
        if (level == null || !level.isClientSide) {
            return;
        }

        ItemStack single = sourceStack.copy();
        single.setCount(1);
        ConveyorVisualItemEntity visual = clientVisualItems.get(visualKey);
        if (visual == null || !visual.isAlive()) {
            ConveyorVisualItemEntity created = new ConveyorVisualItemEntity(level, position.x, position.y, position.z, single);
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
        for (Map.Entry<Long, ConveyorVisualItemEntity> entry : clientVisualItems.entrySet()) {
            ConveyorVisualItemEntity visual = entry.getValue();
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

        for (ConveyorVisualItemEntity visual : clientVisualItems.values()) {
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

    private Vec3 computeVisualPosition(BlockState state, double progress) {
        BlockPos inputOffset = getInputOffset(state);
        BlockPos outputOffset = getOutputOffset(state);

        double startX = worldPosition.getX() + 0.5D + (inputOffset.getX() * 0.5D);
        double startY = worldPosition.getY() + 0.05D;
        double startZ = worldPosition.getZ() + 0.5D + (inputOffset.getZ() * 0.5D);

        double endX = worldPosition.getX() + 0.5D + (outputOffset.getX() * 0.5D);
        double endY = worldPosition.getY() + 0.05D;
        double endZ = worldPosition.getZ() + 0.5D + (outputOffset.getZ() * 0.5D);

        return new Vec3(
                startX + ((endX - startX) * progress),
                startY + ((endY - startY) * progress),
                startZ + ((endZ - startZ) * progress)
        );
    }

    private Vec3 computeDeadReckonedVisualPosition(double startSlotUnits, long elapsedTicks) {
        ConveyorStraightBlockEntity current = this;
        BlockState currentState = current.getBlockState();
        double position = Math.max(0.0D, startSlotUnits);
        double remainingTicks = Math.max(0L, elapsedTicks);
        int guard = 256;

        while (remainingTicks > 0.0D && guard-- > 0) {
            double stepsPerTick = current.getStepsPerTickForState(currentState);
            if (stepsPerTick <= 0.0D) {
                break;
            }

            double distanceToEnd = BUFFER_SLOTS - position;
            if (distanceToEnd <= 1.0E-6D) {
                ConveyorStraightBlockEntity next = current.getStrictOutputConveyor();
                if (next == null || next.isRemoved()) {
                    position = BUFFER_SLOTS - 1.0E-6D;
                    break;
                }

                current = next;
                currentState = current.getBlockState();
                position = 0.0D;
                continue;
            }

            double ticksToEnd = distanceToEnd / stepsPerTick;
            if (remainingTicks < ticksToEnd) {
                position += remainingTicks * stepsPerTick;
                remainingTicks = 0.0D;
                break;
            }

            remainingTicks -= ticksToEnd;
            ConveyorStraightBlockEntity next = current.getStrictOutputConveyor();
            if (next == null || next.isRemoved()) {
                position = BUFFER_SLOTS - 1.0E-6D;
                break;
            }

            current = next;
            currentState = current.getBlockState();
            position = 0.0D;
        }

        double progress = clamp(position / BUFFER_SLOTS, 0.0D, 0.999999D);
        return current.computeVisualPosition(currentState, progress);
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
        for (int slot = 0; slot < BUFFER_SLOTS; slot++) {
            maxId = Math.max(maxId, itemIds[slot]);
        }
        if (maxId > 0L) {
            ensureGlobalItemIdAbove(maxId);
        }
    }

    private boolean spawnClientVisual(long visualKey, ConveyorVisualItemEntity visual) {
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

    private boolean acceptsInputFrom(BlockPos sourcePos) {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof ConveyorStraightBlock)) {
            return false;
        }
        BlockPos expectedInputPos = worldPosition.offset(getInputOffset(state));
        if (expectedInputPos.equals(sourcePos)) {
            return true;
        }

        return expectedInputPos.getX() == sourcePos.getX()
                && expectedInputPos.getZ() == sourcePos.getZ()
                && Math.abs(expectedInputPos.getY() - sourcePos.getY()) <= 1;
    }

    private boolean acceptsInputFromStrict(BlockPos sourcePos) {
        if (level == null) {
            return false;
        }

        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof ConveyorStraightBlock)) {
            return false;
        }

        return resolveInputTargetPos(state).equals(sourcePos);
    }

    private boolean outputsToStrict(BlockPos targetPos) {
        if (level == null) {
            return false;
        }

        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof ConveyorStraightBlock)) {
            return false;
        }

        return resolveOutputTargetPos(state).equals(targetPos);
    }

    private Direction getInputDirection(BlockState state) {
        if (state.getBlock() instanceof ConveyorStraightBlock conveyor) {
            return conveyor.getInputDirection(state);
        }
        return Direction.NORTH;
    }

    private Direction getOutputDirection(BlockState state) {
        if (state.getBlock() instanceof ConveyorStraightBlock conveyor) {
            return conveyor.getOutputDirection(state);
        }
        return Direction.NORTH;
    }

    private BlockPos getInputOffset(BlockState state) {
        Direction output = getOutputDirection(state);
        ConveyorStraightBlock.ConveyorShape shape = state.getValue(ConveyorStraightBlock.SHAPE);
        if (shape == ConveyorStraightBlock.ConveyorShape.UP) {
            return new BlockPos(output.getOpposite().getStepX(), 0, output.getOpposite().getStepZ());
        }
        if (shape == ConveyorStraightBlock.ConveyorShape.DOWN) {
            return new BlockPos(output.getOpposite().getStepX(), 1, output.getOpposite().getStepZ());
        }
        Direction input = getInputDirection(state);
        return new BlockPos(input.getStepX(), 0, input.getStepZ());
    }

    private BlockPos getOutputOffset(BlockState state) {
        Direction output = getOutputDirection(state);
        return switch (state.getValue(ConveyorStraightBlock.SHAPE)) {
            case UP -> new BlockPos(output.getStepX(), 1, output.getStepZ());
            case DOWN -> new BlockPos(output.getStepX(), 0, output.getStepZ());
            default -> new BlockPos(output.getStepX(), 0, output.getStepZ());
        };
    }

    private BlockPos resolveInputTargetPos(BlockState state) {
        Direction input = getInputDirection(state);
        BlockPos sameLevelBehind = worldPosition.relative(input);
        BlockPos defaultInputPos = worldPosition.offset(getInputOffset(state));
        ConveyorStraightBlock.ConveyorShape shape = state.getValue(ConveyorStraightBlock.SHAPE);

        return switch (shape) {
            case STRAIGHT, LEFT, RIGHT, UP -> {
                BlockPos belowBehind = sameLevelBehind.below();
                if (isSpecificConveyorPointedAtThis(belowBehind, ConveyorStraightBlock.ConveyorShape.UP)) {
                    yield belowBehind;
                }
                yield defaultInputPos;
            }
            case DOWN -> {
                yield defaultInputPos;
            }
        };
    }

    private BlockPos resolveOutputTargetPos(BlockState state) {
        Direction output = getOutputDirection(state);
        BlockPos sameLevelFront = worldPosition.relative(output);
        BlockPos defaultOutputPos = worldPosition.offset(getOutputOffset(state));
        ConveyorStraightBlock.ConveyorShape shape = state.getValue(ConveyorStraightBlock.SHAPE);

        return switch (shape) {
            case STRAIGHT, LEFT, RIGHT -> {
                BlockPos belowFront = sameLevelFront.below();
                if (isSpecificConveyorAcceptingFromThis(belowFront, ConveyorStraightBlock.ConveyorShape.DOWN)) {
                    yield belowFront;
                }
                yield defaultOutputPos;
            }
            case UP, DOWN -> defaultOutputPos;
        };
    }

    private boolean isSpecificConveyorPointedAtThis(BlockPos conveyorPos, ConveyorStraightBlock.ConveyorShape shape) {
        if (level == null) {
            return false;
        }

        BlockState state = level.getBlockState(conveyorPos);
        if (!(state.getBlock() instanceof ConveyorStraightBlock conveyorBlock) || state.getValue(ConveyorStraightBlock.SHAPE) != shape) {
            return false;
        }

        Direction toThis = Direction.fromDelta(
                Integer.signum(worldPosition.getX() - conveyorPos.getX()),
                0,
                Integer.signum(worldPosition.getZ() - conveyorPos.getZ())
        );
        return toThis != null && toThis.getAxis().isHorizontal() && conveyorBlock.getOutputDirection(state) == toThis;
    }

    private boolean isSpecificConveyorAcceptingFromThis(BlockPos conveyorPos, ConveyorStraightBlock.ConveyorShape shape) {
        if (level == null) {
            return false;
        }

        BlockState state = level.getBlockState(conveyorPos);
        if (!(state.getBlock() instanceof ConveyorStraightBlock) || state.getValue(ConveyorStraightBlock.SHAPE) != shape) {
            return false;
        }

        BlockEntity blockEntity = level.getBlockEntity(conveyorPos);
        return blockEntity instanceof ConveyorStraightBlockEntity conveyor && conveyor.acceptsInputFrom(worldPosition);
    }

    private static final class ConveyorTransfer {
        private final ConveyorStraightBlockEntity target;
        private final ItemStack stack;
        private final long itemId;

        private ConveyorTransfer(ConveyorStraightBlockEntity target, ItemStack stack, long itemId) {
            this.target = target;
            this.stack = stack;
            this.itemId = itemId;
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

    private static final class ShiftPreview {
        private final boolean pos0Empty;
        private final int itemCount;

        private ShiftPreview(boolean pos0Empty, int itemCount) {
            this.pos0Empty = pos0Empty;
            this.itemCount = itemCount;
        }
    }

    private static class ConveyorVisualItemEntity extends ItemEntity {
        private ConveyorVisualItemEntity(Level level, double x, double y, double z, ItemStack stack) {
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
            // Client-only visual. Conveyor code owns movement.
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

    private void setVisualBobPhase(ConveyorVisualItemEntity visual, float bobOffs) {
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
            // If this fails in runtime, fallback is vanilla item bob behavior.
        }
    }
}
