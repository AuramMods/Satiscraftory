package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.SmelterBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;

/** Converts an accepted input and pushes its result immediately, retaining any unaccepted output. */
public final class SmelterBlockEntity extends BlockEntity {
    public static final int ENERGY_CAPACITY = 10_000;
    public static final int ENERGY_PER_ITEM = 1_000;

    private final RecipeManager.CachedCheck<Container, SmeltingRecipe> recipes = RecipeManager.createCheck(RecipeType.SMELTING);
    private final PowerBuffer power = new PowerBuffer();
    private ItemStack pendingOutput = ItemStack.EMPTY;
    private boolean transferring;
    private LazyOptional<IItemHandler> input = LazyOptional.empty();
    private LazyOptional<IItemHandler> output = LazyOptional.empty();
    private LazyOptional<IEnergyStorage> energy = LazyOptional.empty();

    public SmelterBlockEntity(BlockPos pos, BlockState state) {
        super(Satiscraftory.SMELTER_BLOCK_ENTITY.get(), pos, state);
        rebuildCapabilities();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SmelterBlockEntity smelter) {
        if (smelter.flushOutput()) {
            smelter.syncToClient();
        }
    }

    private Direction inputSide() {
        return getBlockState().getValue(SmelterBlock.INPUT_FACING);
    }

    private Direction outputSide() {
        return inputSide().getOpposite();
    }

    @Nullable
    private IItemHandler outputHandler() {
        if (level == null || !level.hasChunkAt(worldPosition.relative(outputSide()))) {
            return null;
        }
        BlockEntity target = level.getBlockEntity(worldPosition.relative(outputSide()));
        return target == null ? null : ItemTransfers.handler(target, inputSide());
    }

    private ItemStack insert(ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !pendingOutput.isEmpty() || transferring || level == null
                || (level.isClientSide && !simulate) || isRemoved()) {
            return stack;
        }
        ItemStack single = stack.copyWithCount(1);
        SimpleContainer recipeInput = new SimpleContainer(single);
        ItemStack smelted = recipes.getRecipeFor(recipeInput, level)
                .map(recipe -> recipe.assemble(recipeInput, level.registryAccess())).orElse(ItemStack.EMPTY);
        int cost = smelted.isEmpty() ? 0 : ENERGY_PER_ITEM;
        ItemStack result = smelted.isEmpty() ? single : smelted;
        IItemHandler destination = outputHandler();
        if (power.getEnergyStored() < cost || destination == null) {
            return stack;
        }

        transferring = true;
        ItemStack remainder;
        try {
            remainder = ItemTransfers.insert(destination, result, true);
        } finally {
            transferring = false;
        }
        if (remainder.getCount() == result.getCount()) {
            return stack;
        }
        if (!simulate) {
            // Reserve the complete result before calling a neighbor, including results with multiple items.
            pendingOutput = result.copy();
            power.consume(cost);
            setChanged();
            flushOutput();
            if (!pendingOutput.isEmpty()) {
                syncToClient();
            }
        }
        return stack.copyWithCount(stack.getCount() - 1);
    }

    private boolean flushOutput() {
        if (pendingOutput.isEmpty() || transferring) {
            return false;
        }
        IItemHandler destination = outputHandler();
        if (destination == null) {
            return false;
        }
        transferring = true;
        try {
            ItemStack remainder = ItemTransfers.insert(destination, pendingOutput, false);
            if (remainder.getCount() != pendingOutput.getCount()) {
                pendingOutput = remainder;
                setChanged();
            }
        } finally {
            transferring = false;
        }
        return pendingOutput.isEmpty();
    }

    public void dropContents() {
        if (level != null && !level.isClientSide && !pendingOutput.isEmpty()) {
            ItemStack drop = pendingOutput;
            pendingOutput = ItemStack.EMPTY;
            Containers.dropItemStack(level, worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
                    worldPosition.getZ() + 0.5D, drop);
            setChanged();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("energy", power.getEnergyStored());
        tag.put("output", pendingOutput.save(new CompoundTag()));
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        power.restore(tag.getInt("energy"));
        pendingOutput = ItemStack.of(tag.getCompound("output"));
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            load(tag);
        }
    }

    private void syncPowerAvailability(int previousEnergy) {
        if (level != null && !level.isClientSide
                && (previousEnergy >= ENERGY_PER_ITEM) != (power.getEnergyStored() >= ENERGY_PER_ITEM)) {
            syncToClient();
        }
    }

    private void syncToClient() {
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        input.invalidate();
        output.invalidate();
        energy.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        rebuildCapabilities();
    }

    @Override
    public void setBlockState(BlockState state) {
        Direction previous = inputSide();
        super.setBlockState(state);
        if (previous != inputSide()) {
            rebuildCapabilities();
        }
    }

    private void rebuildCapabilities() {
        input.invalidate();
        output.invalidate();
        energy.invalidate();
        input = LazyOptional.of(() -> new ItemPort(true));
        output = LazyOptional.of(() -> new ItemPort(false));
        energy = LazyOptional.of(() -> power);
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (!isRemoved() && capability == ForgeCapabilities.ITEM_HANDLER) {
            if (side == inputSide()) {
                return input.cast();
            }
            return side == outputSide() ? output.cast() : LazyOptional.empty();
        }
        if (!isRemoved() && capability == ForgeCapabilities.ENERGY) {
            return side == inputSide() || side == outputSide() ? LazyOptional.empty() : energy.cast();
        }
        return super.getCapability(capability, side);
    }

    private final class PowerBuffer extends EnergyStorage {
        private PowerBuffer() {
            super(ENERGY_CAPACITY, Integer.MAX_VALUE, 0);
        }

        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            if (amount <= 0 || level == null || level.isClientSide || isRemoved()) {
                return 0;
            }
            int previous = getEnergyStored();
            int received = super.receiveEnergy(amount, simulate);
            if (!simulate && received > 0) {
                setChanged();
                syncPowerAvailability(previous);
            }
            return received;
        }

        private void consume(int amount) {
            int previous = energy;
            energy -= amount;
            syncPowerAvailability(previous);
        }

        private void restore(int amount) {
            energy = Mth.clamp(amount, 0, ENERGY_CAPACITY);
        }
    }

    private final class ItemPort implements IItemHandler {
        private final boolean acceptsInput;

        private ItemPort(boolean acceptsInput) {
            this.acceptsInput = acceptsInput;
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot == 0 && !acceptsInput ? pendingOutput.copy() : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return slot == 0 && acceptsInput ? insert(stack, simulate) : stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != 0 || amount <= 0 || acceptsInput || transferring || isRemoved() || level == null
                    || (level.isClientSide && !simulate)) {
                return ItemStack.EMPTY;
            }
            ItemStack extracted = pendingOutput.copyWithCount(Math.min(amount, pendingOutput.getCount()));
            if (!simulate && !extracted.isEmpty()) {
                pendingOutput.shrink(extracted.getCount());
                setChanged();
                if (pendingOutput.isEmpty()) {
                    syncToClient();
                }
            }
            return extracted;
        }

        @Override
        public int getSlotLimit(int slot) {
            return slot == 0 ? 64 : 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && acceptsInput && !stack.isEmpty();
        }
    }
}
