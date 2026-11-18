package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.SplitterBlock;
import art.arcane.satiscraftory.data.filter.SmartSplitterFilters;
import art.arcane.satiscraftory.data.filter.SplitterFilterMode;
import art.arcane.satiscraftory.data.filter.SplitterOutput;
import art.arcane.satiscraftory.menu.SmartSplitterMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public final class SmartSplitterBlockEntity extends SplitterBlockEntity implements MenuProvider {
    private final SmartSplitterFilters filters = new SmartSplitterFilters();

    public SmartSplitterBlockEntity(BlockPos pos, BlockState state) {
        super(Satiscraftory.SMART_SPLITTER_BLOCK_ENTITY.get(), pos, state);
    }

    public SplitterFilterMode mode(SplitterOutput output) {
        return filters.mode(output);
    }

    public ItemStack template(SplitterOutput output, int slot) {
        return filters.template(output, slot);
    }

    public void cycleMode(SplitterOutput output) {
        if (level != null && !level.isClientSide && !isRemoved()) {
            filters.setMode(output, filters.mode(output).next());
            changed();
        }
    }

    public void setTemplate(SplitterOutput output, int slot, ItemStack stack) {
        if (level != null && !level.isClientSide && !isRemoved() && slot >= 0 && slot < SmartSplitterFilters.FILTER_SLOTS) {
            filters.setTemplate(output, slot, stack);
            changed();
        }
    }

    private void changed() {
        setChanged();
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
    }

    @Nullable
    private SplitterOutput output(Direction side) {
        return SplitterOutput.fromDirection(getBlockState().getValue(SplitterBlock.INPUT_FACING), side);
    }

    @Override
    protected boolean matchesOutput(Direction side, ItemStack stack, boolean overflow) {
        SplitterOutput output = output(side);
        if (output == null) {
            return false;
        }
        return overflow ? mode(output) == SplitterFilterMode.OVERFLOW : filters.matches(output, stack);
    }

    @Override
    protected boolean allowsPull(@Nullable Direction side, ItemStack stack) {
        if (side == null || stack.isEmpty()) {
            return false;
        }
        SplitterOutput output = output(side);
        if (output == null) {
            return false;
        }
        return mode(output) == SplitterFilterMode.OVERFLOW
                ? !hasAcceptingRegularOutput(stack) : filters.matches(output, stack);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("filters", filters.save());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        filters.load(tag.getCompound("filters"));
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.satiscraftory.smart_splitter");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new SmartSplitterMenu(id, inventory, this);
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
}
