package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.ConstructorBlock;
import art.arcane.satiscraftory.menu.ConstructorMenu;
import art.arcane.satiscraftory.recipe.ConstructorRecipes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.List;

/** One input stack, a recipe template, and a FIFO of completed crafting results and remainders. */
public final class ConstructorBlockEntity extends BlockEntity implements MenuProvider {
    public static final int ENERGY_CAPACITY = 5_000;
    public static final int ENERGY_PER_ITEM = 500;

    private boolean processing;
    private ItemStack template = ItemStack.EMPTY;
    @Nullable
    private ResourceLocation recipeId;
    private int workCredit;
    private final ArrayDeque<ItemStack> finished = new ArrayDeque<>();
    private final MachineEnergyStorage power = new MachineEnergyStorage(ENERGY_CAPACITY,
            () -> level != null && !level.isClientSide && !isRemoved(), previous -> {
                setChanged();
                process();
                if ((previous >= ENERGY_PER_ITEM) != (powerStored() >= ENERGY_PER_ITEM)) {
                    syncToClient();
                }
            });
    private final ItemStackHandler inventory = new ItemStackHandler(1) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && acceptsIngredient(stack);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return processing || (level != null && level.isClientSide && !simulate)
                    ? stack : super.insertItem(slot, stack, simulate);
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            super.setStackInSlot(slot, stack.copy());
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            process();
        }
    };
    private LazyOptional<IItemHandler> input = LazyOptional.empty();
    private LazyOptional<IItemHandler> output = LazyOptional.empty();
    private LazyOptional<IEnergyStorage> energy = LazyOptional.empty();

    public ConstructorBlockEntity(BlockPos pos, BlockState state) {
        super(Satiscraftory.CONSTRUCTOR_BLOCK_ENTITY.get(), pos, state);
        rebuildCapabilities();
    }

    public ItemStackHandler inputInventory() {
        return inventory;
    }

    public ItemStack template() {
        return template.copy();
    }

    @Nullable
    public ResourceLocation recipeId() {
        return recipeId;
    }

    public int powerStored() {
        return power.getEnergyStored();
    }

    public int workCredit() {
        return workCredit;
    }

    public List<ConstructorRecipes.Choice> choices() {
        return level == null ? List.of() : ConstructorRecipes.find(level, template);
    }

    public void setTemplate(ItemStack requested) {
        if (level == null || level.isClientSide) {
            return;
        }
        template = requested.copyWithCount(1);
        var choices = choices();
        recipeId = choices.isEmpty() ? null : choices.get(0).id();
        setChanged();
        process();
        syncToClient();
    }

    public boolean selectRecipe(ResourceLocation id) {
        if (level == null || level.isClientSide || choices().stream().noneMatch(choice -> choice.id().equals(id))) {
            return false;
        }
        recipeId = id;
        setChanged();
        process();
        syncToClient();
        return true;
    }

    private boolean acceptsIngredient(ItemStack stack) {
        if (level == null || recipeId == null || stack.isEmpty()) {
            return false;
        }
        return ConstructorRecipes.plan(level, recipeId, stack.copyWithCount(9))
                .filter(plan -> plan.result().is(template.getItem()))
                .filter(plan -> !template.hasTag() || ItemStack.isSameItemSameTags(plan.result(), template)).isPresent();
    }

    private Direction outputSide() {
        return getBlockState().getValue(ConstructorBlock.OUTPUT_FACING);
    }

    @Nullable
    private IItemHandler destination() {
        if (level == null || !level.hasChunkAt(worldPosition.relative(outputSide()))) {
            return null;
        }
        var block = level.getBlockEntity(worldPosition.relative(outputSide()));
        return block == null ? null : ItemTransfers.handler(block, outputSide().getOpposite());
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ConstructorBlockEntity constructor) {
        constructor.process();
    }

    private void process() {
        if (processing || level == null || level.isClientSide || isRemoved()) {
            return;
        }
        processing = true;
        try {
            while (flushFinished() && recipeId != null) {
                var plan = ConstructorRecipes.plan(level, recipeId, inventory.getStackInSlot(0));
                if (plan.isEmpty()) {
                    return;
                }
                var craft = plan.get();
                if (!craft.result().is(template.getItem())
                        || (template.hasTag() && !ItemStack.isSameItemSameTags(craft.result(), template))) {
                    return;
                }
                IItemHandler target = destination();
                if (target == null || ItemTransfers.insert(target, craft.result(), true).getCount() == craft.result().getCount()) {
                    return;
                }
                int cost = Math.multiplyExact(ENERGY_PER_ITEM, craft.result().getCount());
                int payment = Math.min(Math.max(0, cost - workCredit), powerStored());
                workCredit += payment;
                power.consume(payment);
                if (payment > 0) {
                    setChanged();
                }
                if (workCredit < cost) {
                    return;
                }
                // Keep inputs until the whole batch is funded, including recipes costing more than the RF buffer.
                inventory.extractItem(0, craft.count(), false);
                workCredit -= cost;
                finished.add(craft.result());
                finished.addAll(craft.remainders());
                setChanged();
            }
        } finally {
            processing = false;
        }
    }

    private boolean flushFinished() {
        while (!finished.isEmpty()) {
            var target = destination();
            if (target == null) {
                return false;
            }
            ItemStack stack = finished.peek();
            ItemStack remainder = ItemTransfers.insert(target, stack, false);
            if (remainder.getCount() == stack.getCount()) {
                return false;
            }
            finished.remove();
            if (!remainder.isEmpty()) {
                finished.addFirst(remainder);
            }
            setChanged();
        }
        return true;
    }

    public void dropContents() {
        if (level == null || level.isClientSide) {
            return;
        }
        processing = true;
        try {
            drop(inventory.extractItem(0, Integer.MAX_VALUE, false));
            while (!finished.isEmpty()) {
                drop(finished.remove());
            }
        } finally {
            processing = false;
        }
    }

    private void drop(ItemStack stack) {
        Containers.dropItemStack(level, worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
                worldPosition.getZ() + 0.5D, stack);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("inventory", inventory.serializeNBT());
        tag.put("template", template.save(new CompoundTag()));
        if (recipeId != null) {
            tag.putString("recipe", recipeId.toString());
        }
        tag.putInt("energy", powerStored());
        tag.putInt("work_credit", workCredit);
        ListTag results = new ListTag();
        for (ItemStack stack : finished) {
            results.add(stack.save(new CompoundTag()));
        }
        tag.put("finished", results);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        processing = true;
        try {
            template = ItemStack.of(tag.getCompound("template"));
            recipeId = ResourceLocation.tryParse(tag.getString("recipe"));
            inventory.deserializeNBT(tag.getCompound("inventory"));
            power.restore(tag.getInt("energy"));
            workCredit = Math.max(0, tag.getInt("work_credit"));
            finished.clear();
            for (Tag result : tag.getList("finished", Tag.TAG_COMPOUND)) {
                ItemStack stack = ItemStack.of((CompoundTag) result);
                if (!stack.isEmpty()) {
                    finished.add(stack);
                }
            }
        } finally {
            processing = false;
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.satiscraftory.constructor");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
        return new ConstructorMenu(id, playerInventory, this);
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
        if (packet.getTag() != null) {
            load(packet.getTag());
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
        Direction previousOutput = outputSide();
        super.setBlockState(state);
        if (previousOutput != outputSide()) {
            rebuildCapabilities();
        }
    }

    private void rebuildCapabilities() {
        input.invalidate();
        output.invalidate();
        energy.invalidate();
        input = LazyOptional.of(() -> inventory);
        output = LazyOptional.of(() -> new IItemHandler() {
            @Override
            public int getSlots() {
                return 1;
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return slot == 0 && !finished.isEmpty() ? finished.peek().copy() : ItemStack.EMPTY;
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return stack;
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                if (slot != 0 || amount <= 0 || finished.isEmpty() || processing || isRemoved()
                        || (level != null && level.isClientSide && !simulate)) {
                    return ItemStack.EMPTY;
                }
                ItemStack extracted = finished.peek().copyWithCount(Math.min(amount, finished.peek().getCount()));
                if (!simulate) {
                    finished.peek().shrink(extracted.getCount());
                    if (finished.peek().isEmpty()) {
                        finished.remove();
                    }
                    setChanged();
                    process();
                }
                return extracted;
            }

            @Override
            public int getSlotLimit(int slot) {
                return slot == 0 ? 64 : 0;
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return false;
            }
        });
        energy = LazyOptional.of(() -> power);
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (!isRemoved() && capability == ForgeCapabilities.ITEM_HANDLER) {
            return side == outputSide() ? output.cast() : input.cast();
        }
        if (!isRemoved() && capability == ForgeCapabilities.ENERGY) {
            return side == outputSide() ? LazyOptional.empty() : energy.cast();
        }
        return super.getCapability(capability, side);
    }
}
