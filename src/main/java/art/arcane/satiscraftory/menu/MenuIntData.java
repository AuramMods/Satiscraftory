package art.arcane.satiscraftory.menu;

import net.minecraft.world.inventory.ContainerData;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Container data packets carry signed shorts; send each integer as two independent words. */
final class MenuIntData implements ContainerData {
    private final BooleanSupplier client;
    private final IntSupplier[] values;
    private final int[] clientWords;

    MenuIntData(BooleanSupplier client, IntSupplier... values) {
        this.client = client;
        this.values = values;
        clientWords = new int[values.length * 2];
    }

    @Override
    public int get(int index) {
        if (client.getAsBoolean()) {
            return clientWords[index];
        }
        return (values[index / 2].getAsInt() >>> ((index % 2) * 16)) & 0xffff;
    }

    @Override
    public void set(int index, int value) {
        clientWords[index] = value;
    }

    @Override
    public int getCount() {
        return clientWords.length;
    }

    int value(int index) {
        return (get(index * 2) & 0xffff) | ((get(index * 2 + 1) & 0xffff) << 16);
    }
}
