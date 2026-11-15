package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.block.entity.ConveyorPrediction.RenderItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.List;

/** Runs the belt clock, transfers cargo between ports, and exposes the inventory capability. */
final class ConveyorTransport {
    private static final String STEP_ACCUMULATOR_TAG = "step_accumulator";
    private static final String DIRECT_INPUT_TAG = "direct_input";
    private static final String BLOCKED_OUTPUT_ITEM_TAG = "blocked_output_item";

    private final ConveyorBlockEntity belt;
    private final ConveyorBuffer buffer;
    @Nullable
    private BlockPos directInputPos;
    private long lastAccumulatorTick = Long.MIN_VALUE;
    private double stepAccumulator;
    private long blockedOutputItemId;

    ConveyorTransport(ConveyorBlockEntity belt, ConveyorBuffer buffer) {
        this.belt = belt;
        this.buffer = buffer;
    }

    void save(CompoundTag tag) {
        tag.putDouble(STEP_ACCUMULATOR_TAG, stepAccumulator);
        if (blockedOutputItemId != 0L) {
            tag.putLong(BLOCKED_OUTPUT_ITEM_TAG, blockedOutputItemId);
        }
        if (directInputPos != null) {
            tag.put(DIRECT_INPUT_TAG, NbtUtils.writeBlockPos(directInputPos));
        }
    }

    void load(CompoundTag tag) {
        stepAccumulator = tag.contains(STEP_ACCUMULATOR_TAG, Tag.TAG_DOUBLE)
                ? Mth.clamp(tag.getDouble(STEP_ACCUMULATOR_TAG), 0.0D, 512.0D)
                : 0.0D;
        blockedOutputItemId = tag.getLong(BLOCKED_OUTPUT_ITEM_TAG);
        lastAccumulatorTick = Long.MIN_VALUE;
        directInputPos = tag.contains(DIRECT_INPUT_TAG, Tag.TAG_COMPOUND)
                ? NbtUtils.readBlockPos(tag.getCompound(DIRECT_INPUT_TAG)) : null;
    }

    void tick(Level level) {
        boolean firstTick = lastAccumulatorTick == Long.MIN_VALUE;
        updateStepAccumulator(level.getGameTime());
        int stepBudget = availableStepBudget();
        for (int i = 0; i < stepBudget; i++) {
            runSubStep(level);
            consumeOneStepBudget();
        }
        if (firstTick || stepBudget > 0) {
            refreshOutputBackpressure();
        }
    }

    List<RenderItem> sampleCargo() {
        return ConveyorPrediction.sample(buffer, stepAccumulator, blockedOutputItemId, this::canSendToOutput);
    }

    List<RenderItem> predictCargo(long elapsedTicks) {
        return ConveyorPrediction.predict(buffer, stepAccumulator, getStepsPerTick(), elapsedTicks,
                blockedOutputItemId, this::canSendToOutput);
    }

    IItemHandler itemHandler(@Nullable Direction side) {
        return new PortItemHandler(side);
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

    double getStepsPerTick() {
        return buffer.size() / (double) getConfiguredTravelTicks();
    }

    private long getConfiguredTravelTicks() {
        return Math.max(1L, belt.getBeltLengthBlocks() * (long) belt.getTravelTicksPerBlock());
    }

    private boolean canSendToOutput(ItemStack stack) {
        return canInsertHead(getOutputContainerHandler(), stack);
    }

    private boolean canInsertHead(@Nullable IItemHandler output, ItemStack stack) {
        if (output == null || stack.isEmpty()) {
            return false;
        }

        ItemStack single = stack.copy();
        single.setCount(1);
        return ItemTransfers.insert(output, single, true).isEmpty();
    }

    private void runSubStep(Level level) {
        tryTransferToOutput(level);
        buffer.advanceItemsOneStep();
        pullFromInputContainer();
    }

    private void refreshOutputBackpressure() {
        long blocked = 0L;
        if (buffer.hasHeadAtOutput() && !canInsertHead(getOutputContainerHandler(), buffer.stack(0))) {
            blocked = buffer.itemId(0);
        }
        if (blocked != blockedOutputItemId) {
            blockedOutputItemId = blocked;
            belt.requestUrgentSync();
            belt.markDirtyForSync();
        }
    }

    private boolean tryTransferToOutput(Level level) {
        if (!buffer.hasHeadAtOutput()) {
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

        ItemStack moving = buffer.peekHeadSingle();
        if (moving.isEmpty()) {
            return false;
        }

        if (!ItemTransfers.insert(outputHandler, moving, true).isEmpty()) {
            return false;
        }

        HeadTransfer transfer = popHeadForTransfer();
        if (transfer == null || transfer.stack.isEmpty()) {
            return false;
        }

        ItemStack remaining = ItemTransfers.insert(outputHandler, transfer.stack, false);
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
        Level level = belt.getLevel();
        if (level == null || !canAcceptNewItemAtStep()) {
            return false;
        }

        if (level.getBlockEntity(resolveInputTargetPos()) instanceof ConveyorBlockEntity previousBelt
                && previousBelt.transport().getDirectOutputConveyor() == belt) {
            return previousBelt.transport().transferHeadTo(belt);
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
    ConveyorBlockEntity getDirectOutputConveyor() {
        Level level = belt.getLevel();
        if (level != null && belt.getEndPos() != null
                && level.getBlockEntity(resolveOutputTargetPos()) instanceof ConveyorBlockEntity target
                && target != belt && target.getEndPos() != null && target.transport().canInsertFromSide(belt.getOutputSide().getOpposite())) {
            return target;
        }
        return null;
    }

    @Nullable
    ConveyorBlockEntity getDirectInputConveyor() {
        Level level = belt.getLevel();
        if (level != null && directInputPos != null
                && level.getBlockEntity(directInputPos) instanceof ConveyorBlockEntity source
                && source.transport().getDirectOutputConveyor() == belt) {
            return source;
        }
        return null;
    }

    private boolean transferHeadTo(ConveyorBlockEntity target) {
        int slot = target.transport().buffer.findFirstEmptySlot();
        if (slot < 0 || target.transport().buffer.hasItemAtPosition(0)) {
            return false;
        }
        HeadTransfer transfer = popHeadForTransfer();
        if (transfer == null) {
            return false;
        }
        target.transport().buffer.appendItem(slot, transfer.stack, transfer.itemId);
        target.transport().directInputPos = belt.getBlockPos();
        // Let both belts publish their completed tick without waiting for the regular snapshot interval.
        target.requestUrgentSync();
        return true;
    }

    @Nullable
    private IItemHandler getInputContainerHandler() {
        Level level = belt.getLevel();
        if (level == null) {
            return null;
        }

        BlockEntity blockEntity = level.getBlockEntity(resolveInputTargetPos());
        // Belt-to-belt pulls are handled by the direct handoff, which checks the upstream outlet position.
        if (blockEntity == null || blockEntity instanceof ConveyorBlockEntity) {
            return null;
        }

        Direction inputSide = belt.getInputSide();
        return ItemTransfers.handler(blockEntity, inputSide.getOpposite());
    }

    @Nullable
    private IItemHandler getOutputContainerHandler() {
        Level level = belt.getLevel();
        if (level == null) {
            return null;
        }

        BlockEntity blockEntity = level.getBlockEntity(resolveOutputTargetPos());
        if (blockEntity == null || blockEntity == belt) {
            return null;
        }

        Direction outputSide = belt.getOutputSide();
        if (blockEntity instanceof ConveyorBlockEntity target
                && (target.getEndPos() == null || !target.transport().canInsertFromSide(outputSide.getOpposite()))) {
            return null;
        }
        return ItemTransfers.handler(blockEntity, outputSide.getOpposite());
    }

    private boolean enqueueItem(ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return true;
        }

        if (!canAcceptNewItemAtStep()) {
            return false;
        }

        int slot = buffer.findFirstEmptySlot();
        if (slot < 0) {
            return false;
        }

        if (!simulate) {
            buffer.appendItem(slot, stack, buffer.allocateItemId());
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

        int tailSlot = buffer.findFirstEmptySlot();
        if (tailSlot < 0 || slot != tailSlot || !canAcceptNewItemAtStep()) {
            return stack;
        }

        if (!simulate) {
            buffer.appendItem(tailSlot, stack, buffer.allocateItemId());
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

        if (slot != 0 || !buffer.hasHeadAtOutput()) {
            return ItemStack.EMPTY;
        }

        ItemStack head = buffer.stack(0);
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

    @Nullable
    private HeadTransfer popHeadForTransfer() {
        if (!buffer.hasHeadAtOutput()) {
            return null;
        }

        ItemStack head = buffer.stack(0);
        long itemId = buffer.itemId(0);
        if (head.isEmpty() || itemId <= 0L) {
            return null;
        }

        ItemStack single = head.copy();
        single.setCount(1);
        removeItemAt(0);
        return new HeadTransfer(single, itemId);
    }

    void removeItemAt(int slot) {
        buffer.remove(slot);
        if (slot == 0) {
            blockedOutputItemId = 0L;
        }
        belt.requestUrgentSync();
        belt.markDirtyForSync();
    }

    private boolean canAcceptNewItemAtStep() {
        return buffer.findFirstEmptySlot() >= 0 && !buffer.hasItemAtPosition(0);
    }

    private boolean canInsertFromSide(Direction side) {
        return side == belt.getInputSide();
    }

    private boolean canExtractFromSide(Direction side) {
        return side == belt.getOutputSide();
    }

    private BlockPos resolveInputTargetPos() {
        return belt.getBlockPos().relative(belt.getInputSide());
    }

    private BlockPos resolveOutputTargetPos() {
        BlockPos endPos = belt.getEndPos();
        if (endPos == null) {
            return belt.getBlockPos().relative(belt.getOutputSide());
        }

        return endPos.relative(belt.getOutputSide());
    }

    private final class PortItemHandler implements IItemHandler {
        @Nullable
        private final Direction side;

        private PortItemHandler(@Nullable Direction side) {
            this.side = side;
        }

        @Override
        public int getSlots() {
            return buffer.size();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= buffer.size()) {
                return ItemStack.EMPTY;
            }
            return buffer.stack(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < 0 || slot >= buffer.size()) {
                return stack;
            }
            return insertFromCapability(side, slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot < 0 || slot >= buffer.size()) {
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

    private static final class HeadTransfer {
        private final ItemStack stack;
        private final long itemId;

        private HeadTransfer(ItemStack stack, long itemId) {
            this.stack = stack;
            this.itemId = itemId;
        }
    }
}
