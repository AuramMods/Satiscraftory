package art.arcane.satiscraftory.interaction;

import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.function.IntConsumer;

/** Vanilla pick-block inventory behavior for a belt hit anywhere along its route. */
public final class BeltPickBlock {
    private BeltPickBlock() {
    }

    public static void pick(Player player, ConveyorBlockEntity belt, IntConsumer requestInventoryPick, Runnable syncCreativeSlot) {
        ItemStack item = new ItemStack(belt.getBlockState().getBlock());
        Inventory inventory = player.getInventory();
        if (player.getAbilities().instabuild) {
            inventory.setPickedItem(item);
            syncCreativeSlot.run();
        } else {
            int slot = inventory.findSlotMatchingItem(item);
            if (Inventory.isHotbarSlot(slot)) {
                inventory.selected = slot;
            } else if (slot != -1) {
                requestInventoryPick.accept(slot);
            }
        }
    }
}
