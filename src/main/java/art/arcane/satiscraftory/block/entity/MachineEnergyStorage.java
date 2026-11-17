package art.arcane.satiscraftory.block.entity;

import net.minecraft.util.Mth;
import net.minecraftforge.energy.EnergyStorage;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Receive-only RF storage with no per-tick intake budget. */
final class MachineEnergyStorage extends EnergyStorage {
    private final BooleanSupplier available;
    private final IntConsumer changed;

    MachineEnergyStorage(int capacity, BooleanSupplier available, IntConsumer changed) {
        super(capacity, Integer.MAX_VALUE, 0);
        this.available = available;
        this.changed = changed;
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
        if (amount <= 0 || !available.getAsBoolean()) {
            return 0;
        }
        int previous = energy;
        int received = super.receiveEnergy(amount, simulate);
        if (!simulate && received > 0) {
            changed.accept(previous);
        }
        return received;
    }

    void consume(int amount) {
        int previous = energy;
        energy -= amount;
        if (amount > 0) {
            changed.accept(previous);
        }
    }

    void restore(int amount) {
        energy = Mth.clamp(amount, 0, capacity);
    }
}
