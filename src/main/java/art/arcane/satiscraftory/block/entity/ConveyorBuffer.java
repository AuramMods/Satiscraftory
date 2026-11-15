package art.arcane.satiscraftory.block.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Owns the buffered stacks, their order along the belt, and their persistent item IDs. */
final class ConveyorBuffer {
    private static final String BUFFER_SLOTS_TAG = "buffer_slots";
    private static final String INVENTORY_TAG = "inventory";
    private static final String RENDER_POSITIONS_TAG = "render_positions";
    private static final String ITEM_IDS_TAG = "item_ids";

    private static long nextItemId = 1L;

    private final Runnable dirty;
    private ItemStackHandler inventory;
    private int[] renderPositions;
    private long[] itemIds;
    private int bufferSlots;
    private int requestedSlots;
    private boolean suppressDirtyCallbacks;

    ConveyorBuffer(int slots, Runnable dirty) {
        this.dirty = dirty;
        requestedSlots = slots;
        bufferSlots = slots;
        inventory = createInventory(slots);
        renderPositions = new int[slots];
        Arrays.fill(renderPositions, -1);
        itemIds = new long[slots];
    }

    int size() {
        return bufferSlots;
    }

    ItemStack stack(int slot) {
        return inventory.getStackInSlot(slot);
    }

    long itemId(int slot) {
        return itemIds[slot];
    }

    int position(int slot) {
        return clampRenderPosition(renderPositions[slot]);
    }

    void save(CompoundTag tag) {
        tag.putInt(BUFFER_SLOTS_TAG, bufferSlots);
        tag.put(INVENTORY_TAG, inventory.serializeNBT());
        tag.putIntArray(RENDER_POSITIONS_TAG, renderPositions);
        tag.putLongArray(ITEM_IDS_TAG, itemIds);
    }

    void load(CompoundTag tag, int requestedSlots) {
        this.requestedSlots = requestedSlots;
        int loadedSlots = Math.max(1, tag.contains(BUFFER_SLOTS_TAG, Tag.TAG_INT)
                ? tag.getInt(BUFFER_SLOTS_TAG)
                : requestedSlots);

        suppressDirtyCallbacks = true;
        inventory = createInventory(loadedSlots);
        if (tag.contains(INVENTORY_TAG, Tag.TAG_COMPOUND)) {
            inventory.deserializeNBT(tag.getCompound(INVENTORY_TAG));
        }
        bufferSlots = Math.max(1, inventory.getSlots());

        renderPositions = new int[bufferSlots];
        Arrays.fill(renderPositions, -1);
        if (tag.contains(RENDER_POSITIONS_TAG, Tag.TAG_INT_ARRAY)) {
            int[] loadedPositions = tag.getIntArray(RENDER_POSITIONS_TAG);
            for (int slot = 0; slot < Math.min(bufferSlots, loadedPositions.length); slot++) {
                renderPositions[slot] = clampRenderPosition(loadedPositions[slot]);
            }
        }

        itemIds = new long[bufferSlots];
        if (tag.contains(ITEM_IDS_TAG, Tag.TAG_LONG_ARRAY)) {
            long[] loadedIds = tag.getLongArray(ITEM_IDS_TAG);
            System.arraycopy(loadedIds, 0, itemIds, 0, Math.min(bufferSlots, loadedIds.length));
        }
    }

    void finishLoad() {
        suppressDirtyCallbacks = false;
        advanceNextItemIdFromLoadedItems();
    }

    void clear() {
        for (int slot = 0; slot < bufferSlots; slot++) {
            inventory.setStackInSlot(slot, ItemStack.EMPTY);
            renderPositions[slot] = -1;
            itemIds[slot] = 0L;
        }
    }

    void remove(int slot) {
        suppressDirtyCallbacks = true;
        inventory.setStackInSlot(slot, ItemStack.EMPTY);
        suppressDirtyCallbacks = false;
        renderPositions[slot] = -1;
        itemIds[slot] = 0L;
        compactQueue();
    }

    boolean resizeTo(int desiredSlots) {
        requestedSlots = desiredSlots;
        // Keep any excess cargo in existing slots until the belt drains enough to shrink.
        if (desiredSlots > bufferSlots || (desiredSlots < bufferSlots && countNonEmptySlots() <= desiredSlots)) {
            resizeBuffer(desiredSlots);
            return true;
        }
        return false;
    }

    int findFirstEmptySlot() {
        int capacity = Math.min(bufferSlots, requestedSlots);
        for (int slot = 0; slot < capacity; slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    void appendItem(int slot, ItemStack stack, long itemId) {
        ItemStack single = stack.copy();
        single.setCount(1);
        suppressDirtyCallbacks = true;
        inventory.setStackInSlot(slot, single);
        suppressDirtyCallbacks = false;
        renderPositions[slot] = 0;
        itemIds[slot] = itemId;
        dirty.run();
    }

    private boolean compactQueue() {
        boolean changed = false;
        int write = 0;

        suppressDirtyCallbacks = true;
        for (int read = 0; read < bufferSlots; read++) {
            ItemStack stack = inventory.getStackInSlot(read);
            if (stack.isEmpty()) {
                continue;
            }

            if (write != read) {
                inventory.setStackInSlot(write, stack);
                inventory.setStackInSlot(read, ItemStack.EMPTY);
                renderPositions[write] = renderPositions[read];
                renderPositions[read] = -1;
                itemIds[write] = itemIds[read];
                itemIds[read] = 0L;
                changed = true;
            }

            write++;
        }

        for (int slot = write; slot < bufferSlots; slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
                changed = true;
            }
            if (renderPositions[slot] != -1) {
                renderPositions[slot] = -1;
                changed = true;
            }
            if (itemIds[slot] != 0L) {
                itemIds[slot] = 0L;
                changed = true;
            }
        }
        suppressDirtyCallbacks = false;

        return changed;
    }

    boolean normalizeInventory(boolean markDirty) {
        boolean changed = compactQueue();

        suppressDirtyCallbacks = true;
        for (int slot = 0; slot < bufferSlots; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                if (renderPositions[slot] != -1) {
                    renderPositions[slot] = -1;
                    changed = true;
                }
                if (itemIds[slot] != 0L) {
                    itemIds[slot] = 0L;
                    changed = true;
                }
                continue;
            }

            if (itemIds[slot] == 0L) {
                itemIds[slot] = allocateItemId();
                changed = true;
            }

            if (stack.getCount() <= 1) {
                continue;
            }

            int overflow = stack.getCount() - 1;
            ItemStack single = stack.copy();
            single.setCount(1);
            inventory.setStackInSlot(slot, single);
            changed = true;

            while (overflow > 0) {
                int emptySlot = findFirstEmptySlot();
                if (emptySlot < 0) {
                    break;
                }

                inventory.setStackInSlot(emptySlot, single.copy());
                renderPositions[emptySlot] = 0;
                itemIds[emptySlot] = allocateItemId();
                overflow--;
                changed = true;
            }
        }
        suppressDirtyCallbacks = false;

        if (compactQueue()) {
            changed = true;
        }

        if (normalizeRenderPositions(countNonEmptySlots())) {
            changed = true;
        }

        if (changed && markDirty) {
            dirty.run();
        }

        return changed;
    }

    void sanitizeClientLoadedState() {
        for (int slot = 0; slot < bufferSlots; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                renderPositions[slot] = -1;
                itemIds[slot] = 0L;
                continue;
            }

            int position = clampRenderPosition(renderPositions[slot]);
            renderPositions[slot] = position < 0 ? 0 : position;
        }
    }

    private int countNonEmptySlots() {
        int count = 0;
        for (int slot = 0; slot < bufferSlots; slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    boolean hasHeadAtOutput() {
        if (inventory.getStackInSlot(0).isEmpty()) {
            return false;
        }

        return clampRenderPosition(renderPositions[0]) >= bufferSlots - 1;
    }

    ItemStack peekHeadSingle() {
        if (!hasHeadAtOutput()) {
            return ItemStack.EMPTY;
        }

        ItemStack head = inventory.getStackInSlot(0);
        if (head.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack single = head.copy();
        single.setCount(1);
        return single;
    }

    boolean hasItemAtPosition(int position) {
        int clampedPosition = clampRenderPosition(position);
        if (clampedPosition < 0) {
            return false;
        }

        for (int slot = 0; slot < bufferSlots; slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty() && renderPositions[slot] == clampedPosition) {
                return true;
            }
        }

        return false;
    }

    boolean advanceItemsOneStep() {
        boolean changed = false;
        int[] nextPositions = new int[bufferSlots];
        Arrays.fill(nextPositions, -1);

        for (int slot = 0; slot < bufferSlots; slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                continue;
            }

            int current = clampRenderPosition(renderPositions[slot]);
            if (current < 0) {
                current = 0;
            }

            int desired = Math.min(bufferSlots - 1, current + 1);
            int next = isPositionTaken(nextPositions, desired) ? current : desired;
            nextPositions[slot] = next;

            if (next != renderPositions[slot]) {
                changed = true;
            }
        }

        if (!changed) {
            return false;
        }

        for (int slot = 0; slot < bufferSlots; slot++) {
            renderPositions[slot] = inventory.getStackInSlot(slot).isEmpty() ? -1 : nextPositions[slot];
        }

        dirty.run();
        return true;
    }

    private static boolean isPositionTaken(int[] positions, int target) {
        for (int position : positions) {
            if (position == target) {
                return true;
            }
        }
        return false;
    }

    private boolean normalizeRenderPositions(int nonEmptyCount) {
        boolean changed = false;
        int previousMaximum = bufferSlots - 1;
        int seen = 0;

        for (int slot = 0; slot < bufferSlots; slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                if (renderPositions[slot] != -1) {
                    renderPositions[slot] = -1;
                    changed = true;
                }
                continue;
            }

            int remaining = Math.max(0, nonEmptyCount - seen - 1);
            int lower = Math.min(bufferSlots - 1, remaining);
            int upper = Math.max(lower, previousMaximum);
            int clamped = Mth.clamp(renderPositions[slot], lower, upper);
            if (renderPositions[slot] != clamped) {
                renderPositions[slot] = clamped;
                changed = true;
            }
            previousMaximum = Math.max(0, clamped - 1);
            seen++;
        }

        return changed;
    }

    private int clampRenderPosition(int position) {
        if (position < 0) {
            return -1;
        }

        return Math.min(bufferSlots - 1, position);
    }

    private void resizeBuffer(int desiredSlots) {
        List<BufferedItem> items = new ArrayList<>();
        int oldSlots = Math.max(1, bufferSlots);
        for (int slot = 0; slot < bufferSlots; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack single = stack.copy();
            single.setCount(1);
            int oldPosition = Mth.clamp(renderPositions[slot], 0, oldSlots - 1);
            double progress = Mth.clamp((oldPosition + 0.5D) / oldSlots, 0.0D, 1.0D);
            long id = itemIds[slot] > 0L ? itemIds[slot] : allocateItemId();
            items.add(new BufferedItem(single, id, progress));
        }

        suppressDirtyCallbacks = true;
        inventory = createInventory(desiredSlots);
        bufferSlots = desiredSlots;
        renderPositions = new int[bufferSlots];
        Arrays.fill(renderPositions, -1);
        itemIds = new long[bufferSlots];

        int write = 0;
        for (BufferedItem item : items) {
            inventory.setStackInSlot(write, item.stack.copy());
            itemIds[write] = item.itemId;
            int mapped = Mth.clamp((int) Math.floor(item.progress * bufferSlots), 0, bufferSlots - 1);
            renderPositions[write] = mapped;
            write++;
        }
        suppressDirtyCallbacks = false;

        normalizeInventory(true);
    }

    private static synchronized long allocateGlobalItemId() {
        return nextItemId++;
    }

    private static synchronized void ensureGlobalItemIdAbove(long minimumExclusive) {
        if (nextItemId <= minimumExclusive) {
            nextItemId = minimumExclusive + 1L;
        }
    }

    long allocateItemId() {
        long id = allocateGlobalItemId();
        return Math.max(1L, id);
    }

    private void advanceNextItemIdFromLoadedItems() {
        long maxId = 0L;
        for (int slot = 0; slot < bufferSlots; slot++) {
            maxId = Math.max(maxId, itemIds[slot]);
        }
        if (maxId > 0L) {
            ensureGlobalItemIdAbove(maxId);
        }
    }

    private ItemStackHandler createInventory(int slots) {
        return new ItemStackHandler(slots) {
            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }

            @Override
            protected void onContentsChanged(int slot) {
                if (!suppressDirtyCallbacks) {
                    dirty.run();
                }
            }
        };
    }

    private static final class BufferedItem {
        private final ItemStack stack;
        private final long itemId;
        private final double progress;

        private BufferedItem(ItemStack stack, long itemId, double progress) {
            this.stack = stack;
            this.itemId = itemId;
            this.progress = progress;
        }
    }
}
