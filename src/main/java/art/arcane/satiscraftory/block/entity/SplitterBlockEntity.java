package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.SplitterBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

public class SplitterBlockEntity extends BlockEntity {
    private static final String INVENTORY_TAG = "inventory";
    private static final String NEXT_OUTPUT_INDEX_TAG = "next_output_index";
    private static final int BUFFER_SLOT = 0;
    private static final int OUTPUT_FACE_COUNT = 3;

    private ItemStackHandler inventory;
    private int nextOutputIndex;
    private boolean suppressDirtyCallbacks;

    private LazyOptional<IItemHandler> unsidedCapability = LazyOptional.empty();
    private final Map<Direction, LazyOptional<IItemHandler>> sidedCapabilities = new EnumMap<>(Direction.class);

    public SplitterBlockEntity(BlockPos pos, BlockState blockState) {
        super(Satiscraftory.SPLITTER_BLOCK_ENTITY.get(), pos, blockState);
        inventory = createInventory();
        rebuildCapabilities();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SplitterBlockEntity splitter) {
        splitter.tickServer(state);
    }

    private void tickServer(BlockState state) {
        ItemStack bufferStack = inventory.getStackInSlot(BUFFER_SLOT);
        if (bufferStack.isEmpty()) {
            return;
        }

        Direction inputSide = getInputSide(state);
        Direction[] outputOrder = getOutputOrder(inputSide);

        ItemStack single = bufferStack.copy();
        single.setCount(1);

        for (int attempt = 0; attempt < OUTPUT_FACE_COUNT; attempt++) {
            int outputIndex = Math.floorMod(nextOutputIndex + attempt, OUTPUT_FACE_COUNT);
            Direction outputSide = outputOrder[outputIndex];
            IItemHandler outputHandler = getOutputHandler(outputSide);
            if (outputHandler == null) {
                continue;
            }

            if (!insertIntoHandler(outputHandler, single, true).isEmpty()) {
                continue;
            }

            if (!insertIntoHandler(outputHandler, single, false).isEmpty()) {
                continue;
            }

            suppressDirtyCallbacks = true;
            inventory.setStackInSlot(BUFFER_SLOT, ItemStack.EMPTY);
            suppressDirtyCallbacks = false;
            nextOutputIndex = (outputIndex + 1) % OUTPUT_FACE_COUNT;
            setChanged();
            return;
        }
    }

    @Nullable
    private IItemHandler getOutputHandler(Direction outputSide) {
        if (level == null) {
            return null;
        }

        BlockEntity target = level.getBlockEntity(worldPosition.relative(outputSide));
        if (target == null || target == this) {
            return null;
        }

        Direction sideOnTarget = outputSide.getOpposite();
        LazyOptional<IItemHandler> sidedHandler = target.getCapability(ForgeCapabilities.ITEM_HANDLER, sideOnTarget);
        if (sidedHandler.isPresent()) {
            return sidedHandler.orElse(null);
        }

        return target.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
    }

    private static ItemStack insertIntoHandler(IItemHandler handler, ItemStack stack, boolean simulate) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = handler.insertItem(slot, remaining, simulate);
        }
        return remaining;
    }

    public void dropContents(Level level, BlockPos pos) {
        ItemStack stack = inventory.getStackInSlot(BUFFER_SLOT);
        if (stack.isEmpty()) {
            return;
        }

        Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
        suppressDirtyCallbacks = true;
        inventory.setStackInSlot(BUFFER_SLOT, ItemStack.EMPTY);
        suppressDirtyCallbacks = false;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(INVENTORY_TAG, inventory.serializeNBT());
        tag.putInt(NEXT_OUTPUT_INDEX_TAG, Math.floorMod(nextOutputIndex, OUTPUT_FACE_COUNT));
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        suppressDirtyCallbacks = true;
        inventory = createInventory();
        if (tag.contains(INVENTORY_TAG, Tag.TAG_COMPOUND)) {
            inventory.deserializeNBT(tag.getCompound(INVENTORY_TAG));
        }
        clampInventoryToSingleItem();
        suppressDirtyCallbacks = false;

        int storedOutputIndex = tag.contains(NEXT_OUTPUT_INDEX_TAG, Tag.TAG_INT) ? tag.getInt(NEXT_OUTPUT_INDEX_TAG) : 0;
        nextOutputIndex = Math.floorMod(storedOutputIndex, OUTPUT_FACE_COUNT);
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

    private void rebuildCapabilities() {
        if (unsidedCapability.isPresent()) {
            unsidedCapability.invalidate();
        }
        for (LazyOptional<IItemHandler> capability : sidedCapabilities.values()) {
            capability.invalidate();
        }
        sidedCapabilities.clear();

        unsidedCapability = LazyOptional.of(() -> new SplitterInventoryHandler(null));
        for (Direction direction : Direction.values()) {
            sidedCapabilities.put(direction, LazyOptional.of(() -> new SplitterInventoryHandler(direction)));
        }
    }

    private ItemStackHandler createInventory() {
        return new ItemStackHandler(1) {
            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }

            @Override
            protected void onContentsChanged(int slot) {
                if (!suppressDirtyCallbacks) {
                    setChanged();
                }
            }
        };
    }

    private void clampInventoryToSingleItem() {
        ItemStack stack = inventory.getStackInSlot(BUFFER_SLOT);
        if (stack.isEmpty()) {
            return;
        }

        ItemStack single = stack.copy();
        single.setCount(1);
        inventory.setStackInSlot(BUFFER_SLOT, single);
    }

    private static Direction getInputSide(BlockState state) {
        if (state.hasProperty(SplitterBlock.INPUT_FACING)) {
            return state.getValue(SplitterBlock.INPUT_FACING);
        }
        return Direction.NORTH;
    }

    private static Direction[] getOutputOrder(Direction inputSide) {
        Direction input = inputSide.getAxis().isHorizontal() ? inputSide : Direction.NORTH;
        return new Direction[]{
                input.getCounterClockWise(),
                input.getOpposite(),
                input.getClockWise()
        };
    }

    private boolean canInsertFromSide(Direction side) {
        return side == getInputSide(getBlockState());
    }

    private boolean canExtractFromSide(Direction side) {
        if (!side.getAxis().isHorizontal()) {
            return false;
        }
        return side != getInputSide(getBlockState());
    }

    private ItemStack insertFromCapability(@Nullable Direction side, int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || slot != BUFFER_SLOT) {
            return stack;
        }

        if (side != null && !canInsertFromSide(side)) {
            return stack;
        }

        if (!inventory.getStackInSlot(BUFFER_SLOT).isEmpty()) {
            return stack;
        }

        ItemStack single = stack.copy();
        single.setCount(1);
        ItemStack remainder = inventory.insertItem(BUFFER_SLOT, single, simulate);
        if (!remainder.isEmpty()) {
            return stack;
        }

        ItemStack leftover = stack.copy();
        leftover.shrink(1);
        return leftover;
    }

    private ItemStack extractFromCapability(@Nullable Direction side, int slot, int amount, boolean simulate) {
        if (amount <= 0 || slot != BUFFER_SLOT) {
            return ItemStack.EMPTY;
        }

        if (side != null && !canExtractFromSide(side)) {
            return ItemStack.EMPTY;
        }

        return inventory.extractItem(BUFFER_SLOT, 1, simulate);
    }

    private final class SplitterInventoryHandler implements IItemHandler {
        @Nullable
        private final Direction side;

        private SplitterInventoryHandler(@Nullable Direction side) {
            this.side = side;
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot != BUFFER_SLOT) {
                return ItemStack.EMPTY;
            }
            return inventory.getStackInSlot(BUFFER_SLOT);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return insertFromCapability(side, slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
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
}
