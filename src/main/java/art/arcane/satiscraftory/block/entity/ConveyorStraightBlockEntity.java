package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.ConveyorStraightBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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
import java.util.Arrays;

public class ConveyorStraightBlockEntity extends BlockEntity {
    private static final int BUFFER_SLOTS = 5;
    private static final long TRAVEL_TICKS = 10L;
    private static final long STEP_TICKS = Math.max(1L, TRAVEL_TICKS / BUFFER_SLOTS);
    private static final double VISUAL_RANGE_BLOCKS = 10.0D;
    private static final String INVENTORY_TAG = "inventory";
    private static final String RENDER_POSITIONS_TAG = "render_positions";

    private final int[] renderPositions = new int[BUFFER_SLOTS];
    private final ConveyorVisualItemEntity[] clientVisualItems = new ConveyorVisualItemEntity[BUFFER_SLOTS];
    private long lastTickGameTime = Long.MIN_VALUE;
    private boolean needsSync;
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
        super.saveAdditional(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
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
        lastTickGameTime = Long.MIN_VALUE;
        needsSync = false;
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
                renderPositions[slot] = -1;
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
        if (lastTickGameTime == Long.MIN_VALUE) {
            lastTickGameTime = now;
        } else if (now - lastTickGameTime >= STEP_TICKS) {
            lastTickGameTime = now;
            if (tickMovementStep(level, pos, state)) {
                changed = true;
            }
        }

        if (changed || needsSync) {
            setChangedAndSync(true);
            needsSync = false;
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

        for (int slot = 0; slot < BUFFER_SLOTS; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                removeClientVisual(slot);
                continue;
            }

            double progress = getRenderProgressForSlot(slot);
            Vec3 visualPosition = computeVisualPosition(state, progress);
            updateClientVisual(slot, stack, visualPosition);
        }
    }

    private boolean tickMovementStep(Level level, BlockPos pos, BlockState state) {
        boolean changed = false;

        if (hasHeadAtOutput() && canPushHead(level, pos, state) && pushHead(level, pos, state)) {
            changed = true;
        }

        if (advanceItemsOneStep()) {
            changed = true;
        }

        if (canAcceptNewItemAtStep() && pullFromInput(level, pos, state)) {
            changed = true;
        }

        return changed;
    }

    private boolean canPushHead(Level level, BlockPos pos, BlockState state) {
        ItemStack head = inventory.getStackInSlot(0);
        if (head.isEmpty()) {
            return false;
        }
        ItemStack single = head.copy();
        single.setCount(1);
        return tryInsertIntoOutput(level, pos, state, single, true);
    }

    private boolean pushHead(Level level, BlockPos pos, BlockState state) {
        ItemStack head = inventory.getStackInSlot(0);
        if (head.isEmpty()) {
            return false;
        }
        ItemStack single = head.copy();
        single.setCount(1);
        if (!tryInsertIntoOutput(level, pos, state, single, false)) {
            return false;
        }
        inventory.setStackInSlot(0, ItemStack.EMPTY);
        renderPositions[0] = -1;
        compactQueue();
        markDirtyForSync();
        return true;
    }

    private boolean pullFromInput(Level level, BlockPos pos, BlockState state) {
        if (!canAcceptNewItemAtStep()) {
            return false;
        }

        Direction inputDirection = getInputDirection(state);
        BlockPos inputPos = resolveInputTargetPos(state);
        BlockEntity inputBlockEntity = level.getBlockEntity(inputPos);
        if (inputBlockEntity == null) {
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

    private boolean tryInsertIntoOutput(Level level, BlockPos pos, BlockState state, ItemStack stack, boolean simulate) {
        BlockPos outputPos = resolveOutputTargetPos(state);
        BlockEntity outputBlockEntity = level.getBlockEntity(outputPos);
        if (outputBlockEntity instanceof ConveyorStraightBlockEntity conveyor) {
            return conveyor.acceptsInputFrom(pos) && conveyor.enqueueItem(stack, simulate);
        }

        if (outputBlockEntity == null) {
            return false;
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
            inventory.setStackInSlot(slot, single);
            renderPositions[slot] = 0;
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
                renderPositions[emptySlot] = 0;
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

    private void setChangedAndSync(boolean syncToClient) {
        setChanged();
        if (syncToClient && level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    private boolean isPlayerWithinVisualRange(Level level, BlockPos pos) {
        double centerX = pos.getX() + 0.5D;
        double centerY = pos.getY() + 0.5D;
        double centerZ = pos.getZ() + 0.5D;
        double maxDistanceSqr = VISUAL_RANGE_BLOCKS * VISUAL_RANGE_BLOCKS;

        for (Player player : level.players()) {
            if (player.isAlive() && player.distanceToSqr(centerX, centerY, centerZ) <= maxDistanceSqr) {
                return true;
            }
        }
        return false;
    }

    private void updateClientVisual(int slot, ItemStack sourceStack, Vec3 position) {
        if (level == null || !level.isClientSide) {
            return;
        }

        ItemStack single = sourceStack.copy();
        single.setCount(1);
        ConveyorVisualItemEntity visual = clientVisualItems[slot];
        if (visual == null || !visual.isAlive()) {
            ConveyorVisualItemEntity created = new ConveyorVisualItemEntity(level, position.x, position.y, position.z, single);
            if (!spawnClientVisual(slot, created)) {
                clientVisualItems[slot] = null;
                return;
            }
            setVisualBobPhase(created, 0.0F);
            visual = created;
            clientVisualItems[slot] = created;
        }
        setVisualBobPhase(visual, 0.0F);

        if (!ItemStack.isSameItemSameTags(visual.getItem(), single)) {
            visual.setItem(single);
        }

        visual.setOldPosAndRot();
        visual.moveTo(position.x, position.y, position.z, 0.0F, 0.0F);
        visual.setDeltaMovement(Vec3.ZERO);
    }

    private void removeClientVisual(int slot) {
        ConveyorVisualItemEntity visual = clientVisualItems[slot];
        if (visual != null) {
            if (level != null && level.isClientSide) {
                removeClientEntityById(visual.getId());
            }
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean spawnClientVisual(int slot, ConveyorVisualItemEntity visual) {
        if (level == null || !level.isClientSide) {
            return false;
        }

        int id = visualEntityId(slot);
        visual.setId(id);

        if (addClientEntityById(id, visual)) {
            return true;
        }

        return level.addFreshEntity(visual);
    }

    private int visualEntityId(int slot) {
        long seed = worldPosition.asLong() ^ (0x9E3779B97F4A7C15L * (slot + 1L));
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
