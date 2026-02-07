package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.ConveyorStraightBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.item.ItemEntity;
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
import java.util.Arrays;

public class ConveyorStraightBlockEntity extends BlockEntity {
    private static final int BUFFER_SLOTS = 5;
    private static final long TRAVEL_TICKS = 10L;
    private static final double VISUAL_RANGE_BLOCKS = 10.0D;
    private static final long BLOCKED_SYNC_INTERVAL = 5L;
    private static final String INVENTORY_TAG = "inventory";
    private static final String ENTRY_TICKS_TAG = "entry_ticks";
    private static final String LAST_TICK_TAG = "last_tick";

    private final long[] entryTicks = new long[BUFFER_SLOTS];
    private final ConveyorVisualItemEntity[] clientVisualItems = new ConveyorVisualItemEntity[BUFFER_SLOTS];
    private long lastTickGameTime = Long.MIN_VALUE;

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
            if (tailSlot < 0 || slot != tailSlot) {
                return stack;
            }

            ItemStack single = stack.copy();
            single.setCount(1);
            ItemStack remaining = super.insertItem(slot, single, simulate);
            if (!remaining.isEmpty()) {
                return stack;
            }

            if (!simulate) {
                entryTicks[slot] = getCurrentGameTick();
            }

            ItemStack leftover = stack.copy();
            leftover.shrink(1);
            return leftover;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != 0 || amount <= 0 || !isHeadReady(getCurrentGameTick())) {
                return ItemStack.EMPTY;
            }

            ItemStack extracted = super.extractItem(0, 1, simulate);
            if (!simulate && !extracted.isEmpty()) {
                entryTicks[0] = 0L;
                compactQueue();
            }
            return extracted;
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
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
        tag.putLongArray(ENTRY_TICKS_TAG, entryTicks);
        if (lastTickGameTime != Long.MIN_VALUE) {
            tag.putLong(LAST_TICK_TAG, lastTickGameTime);
        }
        super.saveAdditional(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        Arrays.fill(entryTicks, 0L);
        if (tag.contains(INVENTORY_TAG)) {
            inventory.deserializeNBT(tag.getCompound(INVENTORY_TAG));
        }
        long[] loadedEntryTicks = tag.getLongArray(ENTRY_TICKS_TAG);
        System.arraycopy(loadedEntryTicks, 0, entryTicks, 0, Math.min(loadedEntryTicks.length, entryTicks.length));
        lastTickGameTime = tag.contains(LAST_TICK_TAG) ? tag.getLong(LAST_TICK_TAG) : Long.MIN_VALUE;
        normalizeInventory();
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
                entryTicks[slot] = 0L;
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

        boolean changed = normalizeInventory();
        long now = level.getGameTime();
        long elapsed = lastTickGameTime == Long.MIN_VALUE ? 1L : Math.max(1L, now - lastTickGameTime);
        lastTickGameTime = now;

        if (isHeadReady(now) && !canPushHead(level, pos, state, now)) {
            freezeTimers(elapsed);
            setChangedAndSync(now % BLOCKED_SYNC_INTERVAL == 0L);
            return;
        }

        if (isHeadReady(now) && pushHead(level, pos, state, now)) {
            changed = true;
        }

        if (pullFromInput(level, pos, state, now)) {
            changed = true;
        }

        if (changed) {
            setChangedAndSync(true);
        }
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
        for (int slot = 0; slot < BUFFER_SLOTS; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                removeClientVisual(slot);
                continue;
            }

            double progress = clamp((now - entryTicks[slot]) / (double) TRAVEL_TICKS, 0.0D, 1.0D);
            Vec3 visualPosition = computeVisualPosition(state, progress);
            updateClientVisual(slot, stack, visualPosition);
        }
    }

    private boolean canPushHead(Level level, BlockPos pos, BlockState state, long now) {
        ItemStack head = inventory.getStackInSlot(0);
        if (head.isEmpty()) {
            return false;
        }
        ItemStack single = head.copy();
        single.setCount(1);
        return tryInsertIntoOutput(level, pos, state, single, now, true);
    }

    private boolean pushHead(Level level, BlockPos pos, BlockState state, long now) {
        ItemStack head = inventory.getStackInSlot(0);
        if (head.isEmpty()) {
            return false;
        }
        ItemStack single = head.copy();
        single.setCount(1);
        if (!tryInsertIntoOutput(level, pos, state, single, now, false)) {
            return false;
        }
        inventory.setStackInSlot(0, ItemStack.EMPTY);
        entryTicks[0] = 0L;
        compactQueue();
        return true;
    }

    private boolean pullFromInput(Level level, BlockPos pos, BlockState state, long now) {
        if (findFirstEmptySlot() < 0) {
            return false;
        }

        BlockPos inputPos = pos.offset(getInputOffset(state));
        BlockEntity inputBlockEntity = level.getBlockEntity(inputPos);
        if (inputBlockEntity == null || inputBlockEntity instanceof ConveyorStraightBlockEntity) {
            return false;
        }

        Direction inputDirection = getInputDirection(state);
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
            if (!enqueueItem(single, now, true)) {
                return false;
            }

            ItemStack extracted = handler.extractItem(slot, 1, false);
            if (extracted.isEmpty()) {
                continue;
            }

            ItemStack extractedSingle = extracted.copy();
            extractedSingle.setCount(1);
            return enqueueItem(extractedSingle, now, false);
        }

        return false;
    }

    private boolean tryInsertIntoOutput(Level level, BlockPos pos, BlockState state, ItemStack stack, long now, boolean simulate) {
        BlockPos outputPos = pos.offset(getOutputOffset(state));
        BlockEntity outputBlockEntity = level.getBlockEntity(outputPos);
        if (outputBlockEntity == null) {
            return false;
        }

        if (outputBlockEntity instanceof ConveyorStraightBlockEntity conveyor) {
            if (!conveyor.acceptsInputFrom(pos)) {
                return false;
            }
            return conveyor.enqueueItem(stack, now, simulate);
        }

        Direction outputDirection = getOutputDirection(state);
        IItemHandler handler = getItemHandler(outputBlockEntity, outputDirection.getOpposite());
        if (handler == null) {
            return false;
        }

        return insertIntoHandler(handler, stack, simulate).isEmpty();
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

    private boolean enqueueItem(ItemStack stack, long entryTick, boolean simulate) {
        if (stack.isEmpty()) {
            return true;
        }

        int slot = findFirstEmptySlot();
        if (slot < 0) {
            return false;
        }

        if (!simulate) {
            ItemStack single = stack.copy();
            single.setCount(1);
            inventory.setStackInSlot(slot, single);
            entryTicks[slot] = entryTick;
            compactQueue();
            setChangedAndSync(true);
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
                entryTicks[write] = entryTicks[read];
                entryTicks[read] = 0L;
                changed = true;
            }
            write++;
        }

        for (int slot = write; slot < inventory.getSlots(); slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
                changed = true;
            }
            if (entryTicks[slot] != 0L) {
                entryTicks[slot] = 0L;
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
                entryTicks[slot] = 0L;
                continue;
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
                entryTicks[emptySlot] = entryTicks[slot];
                overflow--;
                changed = true;
            }
        }

        return compactQueue() || changed;
    }

    private boolean isHeadReady(long now) {
        if (inventory.getStackInSlot(0).isEmpty()) {
            return false;
        }
        return now - entryTicks[0] >= TRAVEL_TICKS;
    }

    private void freezeTimers(long ticks) {
        if (ticks <= 0) {
            return;
        }
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                entryTicks[slot] += ticks;
            }
        }
    }

    private long getCurrentGameTick() {
        return level == null ? 0L : level.getGameTime();
    }

    private void setChangedAndSync(boolean syncToClient) {
        setChanged();
        if (syncToClient && level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    private boolean isPlayerWithinVisualRange(Level level, BlockPos pos) {
        return level.getNearestPlayer(
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D,
                VISUAL_RANGE_BLOCKS,
                false
        ) != null;
    }

    private void updateClientVisual(int slot, ItemStack sourceStack, Vec3 position) {
        if (level == null || !level.isClientSide) {
            return;
        }

        ItemStack single = sourceStack.copy();
        single.setCount(1);
        ConveyorVisualItemEntity visual = clientVisualItems[slot];
        if (visual == null || !visual.isAlive()) {
            visual = new ConveyorVisualItemEntity(level, position.x, position.y, position.z, single);
            clientVisualItems[slot] = visual;
            level.addFreshEntity(visual);
        }

        if (!ItemStack.isSameItemSameTags(visual.getItem(), single)) {
            visual.setItem(single);
        }

        visual.moveTo(position.x, position.y, position.z, 0.0F, 0.0F);
        visual.setDeltaMovement(Vec3.ZERO);
    }

    private void removeClientVisual(int slot) {
        ConveyorVisualItemEntity visual = clientVisualItems[slot];
        if (visual != null) {
            visual.discard();
            clientVisualItems[slot] = null;
        }
    }

    private void clearClientVisuals() {
        for (int slot = 0; slot < BUFFER_SLOTS; slot++) {
            removeClientVisual(slot);
        }
    }

    private Vec3 computeVisualPosition(BlockState state, double progress) {
        BlockPos inputOffset = getInputOffset(state);
        BlockPos outputOffset = getOutputOffset(state);

        double startX = worldPosition.getX() + 0.5D + (inputOffset.getX() * 0.45D);
        double startY = worldPosition.getY() + 0.2D + (inputOffset.getY() * 0.5D);
        double startZ = worldPosition.getZ() + 0.5D + (inputOffset.getZ() * 0.45D);

        double endX = worldPosition.getX() + 0.5D + (outputOffset.getX() * 0.45D);
        double endY = worldPosition.getY() + 0.2D + (outputOffset.getY() * 0.5D);
        double endZ = worldPosition.getZ() + 0.5D + (outputOffset.getZ() * 0.45D);

        return new Vec3(
                startX + ((endX - startX) * progress),
                startY + ((endY - startY) * progress),
                startZ + ((endZ - startZ) * progress)
        );
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean acceptsInputFrom(BlockPos sourcePos) {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof ConveyorStraightBlock)) {
            return false;
        }
        BlockPos expectedInputPos = worldPosition.offset(getInputOffset(state));
        return expectedInputPos.equals(sourcePos);
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

    private static class ConveyorVisualItemEntity extends ItemEntity {
        private ConveyorVisualItemEntity(Level level, double x, double y, double z, ItemStack stack) {
            super(level, x, y, z, stack.copy());
            this.noPhysics = true;
            this.setNoGravity(true);
            this.setNeverPickUp();
            this.setPickUpDelay(32767);
            this.setInvulnerable(true);
            this.setDeltaMovement(Vec3.ZERO);
        }

        @Override
        public void tick() {
            // This entity is client-only visual state and is moved by conveyor dead reckoning.
        }

        @Override
        public boolean isPickable() {
            return false;
        }
    }
}
