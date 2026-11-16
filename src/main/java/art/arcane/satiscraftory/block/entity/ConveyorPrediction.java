package art.arcane.satiscraftory.block.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Projects a copy of the cargo queue for animation and in-place drops. */
final class ConveyorPrediction {
    private static final int MAX_PREDICTION_STEPS = 256;
    private static final double COAST_TICKS = 3.0D;
    private static final double MAX_COAST_SLOTS = 3.0D;

    private ConveyorPrediction() {}

    static List<RenderItem> sample(ConveyorBuffer buffer, double accumulator, long blockedItemId,
                                   Predicate<ItemStack> canExit) {
        List<PredictedQueueItem> queue = snapshot(buffer);
        if (queue.isEmpty()) {
            return List.of();
        }

        // Sample the live queue without projecting transfers beyond the current partial step.
        double partialProgress = accumulator - Math.floor(accumulator);
        boolean outputOpen = canPredictOutputDeparture(queue.get(0), buffer.size(), 0.0D, blockedItemId, canExit);
        return interpolate(queue, buffer.size(), outputOpen, partialProgress, 0.0D);
    }

    private static List<PredictedQueueItem> snapshot(ConveyorBuffer buffer) {
        List<PredictedQueueItem> queue = new ArrayList<>();
        for (int slot = 0; slot < buffer.size(); slot++) {
            ItemStack stack = buffer.stack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            long visualKey = buffer.itemId(slot) > 0L ? buffer.itemId(slot) : -(slot + 1L);
            int basePosition = buffer.position(slot);
            if (basePosition < 0) {
                basePosition = 0;
            }
            queue.add(new PredictedQueueItem(visualKey, stack.copy(), basePosition));
        }
        return queue;
    }

    static List<RenderItem> predict(ConveyorBuffer buffer, double accumulator, double slotsPerTick,
                                    long elapsedTicks, long blockedItemId, Predicate<ItemStack> canExit, int updateIntervalTicks) {
        List<PredictedQueueItem> queue = snapshot(buffer);
        if (queue.isEmpty()) {
            return List.of();
        }

        double projectedAccumulator = Math.max(0.0D, accumulator + (elapsedTicks * slotsPerTick));
        int projectedWholeSteps = (int) Math.floor(projectedAccumulator);
        int simulatedWholeSteps = Math.min(projectedWholeSteps, Math.max(buffer.size() * 2, MAX_PREDICTION_STEPS));
        double partialProgress = projectedWholeSteps > simulatedWholeSteps
                ? 0.0D
                : Mth.clamp(projectedAccumulator - projectedWholeSteps, 0.0D, 1.0D);

        double coastTicks = COAST_TICKS + updateIntervalTicks - 1.0D;
        double coastSlots = Math.min(MAX_COAST_SLOTS * updateIntervalTicks, slotsPerTick * coastTicks);
        boolean outputOpen = canPredictOutputDeparture(queue.get(0), buffer.size(), simulatedWholeSteps + partialProgress + coastSlots,
                blockedItemId, canExit);
        for (int step = 0; step < simulatedWholeSteps; step++) {
            if (!simulateRenderSubStep(queue, buffer.size(), outputOpen)) {
                break;
            }
        }

        return interpolate(queue, buffer.size(), outputOpen, partialProgress, coastSlots);
    }

    private static List<RenderItem> interpolate(List<PredictedQueueItem> queue, int size,
                                                boolean outputOpen, double partialProgress, double coastSlots) {
        List<PredictedQueueItem> previewQueue = copyRenderQueue(queue);
        simulateRenderSubStep(previewQueue, size, outputOpen);

        List<RenderItem> renderedItems = new ArrayList<>(queue.size());
        int headStop = outputOpen ? size : size - 1;
        for (int i = 0; i < queue.size(); i++) {
            PredictedQueueItem item = queue.get(i);
            // The first slot starts on the input edge. A departing head advances to the output edge.
            double slotUnits = Mth.lerp(partialProgress, item.position, previewQueue.get(i).position);
            double maxSlotUnits = Math.min(slotUnits + coastSlots, headStop - i);
            renderedItems.add(new RenderItem(item.visualKey, item.stack, slotUnits, maxSlotUnits));
        }

        return renderedItems;
    }

    private static boolean canPredictOutputDeparture(PredictedQueueItem head, int size, double projectedTravel,
                                                      long blockedItemId, Predicate<ItemStack> canExit) {
        // A receiving inventory on the client can look empty even when the server sees a jam.
        return head.position + projectedTravel >= size - 1 && head.visualKey != blockedItemId
                && canExit.test(head.stack);
    }

    private static boolean simulateRenderSubStep(List<PredictedQueueItem> queue, int size, boolean outputOpen) {
        PredictedQueueItem head = queue.get(0);
        boolean reachedEdge = head.position == size - 1 && outputOpen;
        if (reachedEdge) {
            // Hold the same item at the edge until a server snapshot confirms its departure.
            head.position = size;
        }

        return advanceRenderQueue(queue, size) || reachedEdge;
    }

    private static boolean advanceRenderQueue(List<PredictedQueueItem> queue, int size) {
        boolean moved = false;
        boolean[] taken = new boolean[size];
        for (PredictedQueueItem item : queue) {
            if (item.position == size) {
                continue;
            }
            int current = item.position;
            if (current < 0) {
                current = 0;
            }
            current = Mth.clamp(current, 0, size - 1);

            int desired = Math.min(size - 1, current + 1);
            int next = (desired >= 0 && desired < taken.length && !taken[desired]) ? desired : current;
            if (next >= 0 && next < taken.length) {
                taken[next] = true;
            }
            moved |= item.position != next;
            item.position = next;
        }
        return moved;
    }

    private static List<PredictedQueueItem> copyRenderQueue(List<PredictedQueueItem> source) {
        List<PredictedQueueItem> copy = new ArrayList<>(source.size());
        for (PredictedQueueItem item : source) {
            copy.add(new PredictedQueueItem(item.visualKey, item.stack, item.position));
        }
        return copy;
    }

    private static final class PredictedQueueItem {
        private final long visualKey;
        private final ItemStack stack;
        private int position;

        private PredictedQueueItem(long visualKey, ItemStack stack, int position) {
            this.visualKey = visualKey;
            this.stack = stack;
            this.position = position;
        }
    }

    record RenderItem(long visualKey, ItemStack stack, double slotUnits, double maxSlotUnits) {}
}
