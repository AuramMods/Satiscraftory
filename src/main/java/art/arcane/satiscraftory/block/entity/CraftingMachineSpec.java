package art.arcane.satiscraftory.block.entity;

import net.minecraft.world.item.ItemStack;

/** Constructor charges per output item; higher tiers charge once for the complete recipe batch. */
public record CraftingMachineSpec(int inputSlots, int energyCapacity, int energyPerCraft, int energyPerItem) {
    public static final CraftingMachineSpec CONSTRUCTOR = new CraftingMachineSpec(1, 5_000, 0, 500);
    public static final CraftingMachineSpec ASSEMBLER = new CraftingMachineSpec(2, 10_000, 1_000, 0);

    public static final CraftingMachineSpec MANUFACTURER = new CraftingMachineSpec(4, 40_000, 4_000, 0);

    public int energyCost(ItemStack result) {
        return Math.addExact(energyPerCraft, Math.multiplyExact(energyPerItem, result.getCount()));
    }
}
