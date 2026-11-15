package art.arcane.satiscraftory.block.entity;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;

/** Common sided inventory access and ordered insertion for conveyor machinery. */
final class ItemTransfers {
    private ItemTransfers() {}

    @Nullable
    static IItemHandler handler(BlockEntity blockEntity, Direction side) {
        LazyOptional<IItemHandler> sided = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side);
        if (sided.isPresent()) {
            return sided.orElse(null);
        }

        LazyOptional<IItemHandler> unsided = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null);
        return unsided.orElse(null);
    }

    static ItemStack insert(IItemHandler handler, ItemStack stack, boolean simulate) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = handler.insertItem(slot, remaining, simulate);
        }
        return remaining;
    }
}
