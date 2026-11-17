package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.block.CraftingMachineBlock;
import art.arcane.satiscraftory.recipe.CraftingMachineRecipes;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
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

/** Shared crafting inventory, power accounting, and lossless output delivery. */
public abstract class CraftingMachineBlockEntity extends BlockEntity implements MenuProvider {
    private final CraftingMachineSpec spec;
    private boolean processing;
    private ItemStack template = ItemStack.EMPTY;
    @Nullable
    private ResourceLocation recipeId;
    private int workCredit;
    private final ArrayDeque<ItemStack> finished = new ArrayDeque<>();
    private final MachineEnergyStorage power;
    private final ItemStackHandler inventory;
    private LazyOptional<IItemHandler> input = LazyOptional.empty();
    private LazyOptional<IItemHandler> output = LazyOptional.empty();
    private LazyOptional<IEnergyStorage> energy = LazyOptional.empty();

    protected CraftingMachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, CraftingMachineSpec spec) {
        super(type, pos, state);
        this.spec = spec;
        power = new MachineEnergyStorage(spec.energyCapacity(),
                () -> level != null && !level.isClientSide && !isRemoved(), previous -> {
                    setChanged();
                    process();
                });
        inventory = new ItemStackHandler(spec.inputSlots()) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return slot >= 0 && slot < getSlots() && acceptsIngredient(slot, stack);
            }

            @Override
            protected int getStackLimit(int slot, ItemStack stack) {
                return inputLimit(stack);
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return processing || isRemoved() || (level != null && level.isClientSide && !simulate)
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
        rebuildCapabilities();
    }

    public CraftingMachineSpec spec() {
        return spec;
    }

    public ItemStackHandler inputInventory() {
        return inventory;
    }

    public int inputLimit(ItemStack stack) {
        if (spec.inputSlots() > 1) {
            if (level == null || recipeId == null || stack.isEmpty()) {
                return 0;
            }
            return 2 * CraftingMachineRecipes.maxInputCount(level, recipeId, stack, template, spec.inputSlots());
        }
        // Preserve the Constructor's original buffer size, including repeated unstackable inputs.
        return Math.min(64, Math.max(9, stack.getMaxStackSize()));
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

    public List<CraftingMachineRecipes.Choice> choices() {
        return level == null ? List.of() : CraftingMachineRecipes.find(level, template, spec.inputSlots());
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

    private List<ItemStack> inputStacks() {
        var stacks = new java.util.ArrayList<ItemStack>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            stacks.add(inventory.getStackInSlot(slot));
        }
        return stacks;
    }

    private boolean acceptsIngredient(int slot, ItemStack stack) {
        if (level == null || recipeId == null || stack.isEmpty()) {
            return false;
        }
        if (spec.inputSlots() > 1) {
            for (int other = 0; other < inventory.getSlots(); other++) {
                if (other != slot && inventory.getStackInSlot(other).is(stack.getItem())) {
                    return false;
                }
            }
        }
        var proposed = inputStacks();
        proposed.set(slot, stack);
        // A partially supplied recipe must still fit in the remaining empty input slots.
        return CraftingMachineRecipes.canComplete(level, recipeId, proposed, template);
    }

    private Direction outputSide() {
        return getBlockState().getValue(CraftingMachineBlock.OUTPUT_FACING);
    }

    @Nullable
    private IItemHandler destination() {
        if (level == null || !level.hasChunkAt(worldPosition.relative(outputSide()))) {
            return null;
        }
        var block = level.getBlockEntity(worldPosition.relative(outputSide()));
        return block == null ? null : ItemTransfers.handler(block, outputSide().getOpposite());
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CraftingMachineBlockEntity machine) {
        machine.process();
    }

    private void process() {
        if (processing || level == null || level.isClientSide || isRemoved()) {
            return;
        }
        processing = true;
        try {
            while (flushFinished() && recipeId != null) {
                var plan = CraftingMachineRecipes.plan(level, recipeId, inputStacks());
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
                int cost = spec.energyCost(craft.result());
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
                for (int slot = 0; slot < inventory.getSlots(); slot++) {
                    int consumed = craft.consumed()[slot];
                    if (consumed > 0) {
                        ItemStack stack = inventory.getStackInSlot(slot);
                        inventory.setStackInSlot(slot, stack.copyWithCount(stack.getCount() - consumed));
                    }
                }
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
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                ItemStack stack = inventory.getStackInSlot(slot).copy();
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
                drop(stack);
            }
            while (!finished.isEmpty()) {
                drop(finished.remove());
            }
        } finally {
            processing = false;
        }
    }

    private void drop(ItemStack stack) {
        while (!stack.isEmpty()) {
            Containers.dropItemStack(level, worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
                    worldPosition.getZ() + 0.5D, stack.split(stack.getMaxStackSize()));
        }
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
        return getBlockState().getBlock().getName();
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
