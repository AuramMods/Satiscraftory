package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.block.ConveyorBlock;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity.CargoHit;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity.SplineVisualItemEntity;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity.SplineVisualItemEntity.BeltLocation;
import art.arcane.satiscraftory.block.entity.ConveyorPrediction.RenderItem;
import art.arcane.satiscraftory.client.SatiscraftoryClient;
import art.arcane.satiscraftory.interaction.BeltTargeting;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns a belt's client-side cargo and transfers the same visual across connected belts. */
final class ConveyorVisuals {
    private static final double MIN_CLIENT_CORRECTION_SLOTS_PER_TICK = 0.125D;
    private static final double CLIENT_CORRECTION_SPEED_FRACTION = 0.25D;
    // Cargo models are at most half a block wide and deep. Move their whole footprint past the face.
    private static final double CONTAINER_TRAVEL_DISTANCE = 3.0D / 8.0D;
    private static final int HANDOFF_GRACE_TICKS = 3;

    private static long nextClientSnapshotOrder;

    private final ConveyorBlockEntity belt;
    private final Map<Long, SplineVisualItemEntity> items = new HashMap<>();
    private final Set<Long> containerArrivals = new HashSet<>();
    private final Set<SplineVisualItemEntity> containerDepartures = new HashSet<>();
    private long lastClientAppliedRevision = Long.MIN_VALUE;
    private long clientSnapshotGameTime = Long.MIN_VALUE;
    private long clientSnapshotOrder;
    private int updateIntervalTicks = 1;
    private double correctionGain = 0.25D;

    ConveyorVisuals(ConveyorBlockEntity belt) {
        this.belt = belt;
    }

    boolean acceptsSnapshot(long revision) {
        return revision >= lastClientAppliedRevision;
    }

    void applySnapshot(long gameTime, long revision, long[] pickedUpItems, long[] arrivals, long[] departures, int interval) {
        lastClientAppliedRevision = revision;
        clientSnapshotGameTime = gameTime;
        clientSnapshotOrder = ++nextClientSnapshotOrder;
        updateIntervalTicks = interval;
        correctionGain = 1.0D - Math.pow(0.75D, 1.0D / interval);
        for (long itemId : pickedUpItems) {
            SplineVisualItemEntity visual = items.remove(itemId);
            if (visual != null) {
                visual.discard();
            }
        }
        for (long itemId : arrivals) {
            containerArrivals.add(itemId);
        }
        for (long itemId : departures) {
            SplineVisualItemEntity visual = items.remove(itemId);
            if (visual != null && visual.isAlive()) {
                containerDepartures.add(visual);
            }
        }
    }

    int updateIntervalTicks() {
        return updateIntervalTicks;
    }

    double correctionGain() {
        return correctionGain;
    }

    void tick(Level level, BlockState state) {
        if (!(state.getBlock() instanceof ConveyorBlock)) {
            clear();
            return;
        }

        if (!belt.isPlayerWithinVisualRange(level)) {
            clear();
            clientSnapshotGameTime = level.getGameTime();
            return;
        }

        long now = level.getGameTime();
        if (clientSnapshotGameTime == Long.MIN_VALUE) {
            clientSnapshotGameTime = now;
        }
        long elapsedTicks = Math.max(0L, now - clientSnapshotGameTime);
        ConveyorBlockEntity nextBelt = belt.transport().getDirectOutputConveyor();
        ConveyorBlockEntity previousBelt = belt.transport().getDirectInputConveyor();
        List<RenderItem> prediction = belt.predictClientRenderItems(elapsedTicks);
        double slotsPerTick = belt.getStepsPerTick();
        double maxCorrection = Math.max(MIN_CLIENT_CORRECTION_SLOTS_PER_TICK / updateIntervalTicks,
                slotsPerTick * CLIENT_CORRECTION_SPEED_FRACTION);

        Set<Long> activeVisualKeys = new HashSet<>();
        double nextVisualLimit = advanceContainerDepartures(now, slotsPerTick, maxCorrection);
        for (RenderItem item : prediction) {
            if (item.stack().isEmpty()) {
                continue;
            }

            activeVisualKeys.add(item.visualKey());
            double limit = Math.min(nextVisualLimit, item.maxSlotUnits());
            SplineVisualItemEntity visual = updateClientVisual(level, item, previousBelt, nextBelt, now,
                    slotsPerTick, maxCorrection, limit);
            if (visual != null) {
                nextVisualLimit = visual.slotUnits - 1.0D;
            }
        }

        finishDepartingVisuals(activeVisualKeys, nextBelt, now, slotsPerTick, maxCorrection);
        containerArrivals.clear();
    }

    @Nullable
    private SplineVisualItemEntity updateClientVisual(Level level, RenderItem item, @Nullable ConveyorBlockEntity previousBelt,
                                                      @Nullable ConveyorBlockEntity nextBelt, long now,
                                                      double slotsPerTick, double maxCorrection, double limit) {
        SplineVisualItemEntity downstream = nextBelt == null ? null : nextBelt.visuals().items.get(item.visualKey());
        if (downstream != null && downstream.snapshotOrder > clientSnapshotOrder
                && ItemStack.isSameItemSameTags(downstream.getItem(), item.stack())) {
            // The next belt may receive its handoff snapshot before this belt's removal snapshot.
            SplineVisualItemEntity stale = items.remove(item.visualKey());
            if (stale != null) {
                stale.discard();
            }
            return null;
        }

        SplineVisualItemEntity visual = items.get(item.visualKey());
        if ((visual == null || !visual.isAlive()) && previousBelt != null) {
            SplineVisualItemEntity incoming = previousBelt.visuals().items.get(item.visualKey());
            if (incoming != null && incoming.isAlive() && clientSnapshotOrder > incoming.snapshotOrder
                    && ItemStack.isSameItemSameTags(incoming.getItem(), item.stack())) {
                if (!incoming.canEnterNextBelt(now)) {
                    return null;
                }
                previousBelt.visuals().items.remove(item.visualKey());
                incoming.enterBelt(belt);
                visual = incoming;
                items.put(item.visualKey(), visual);
            }
        }

        if (visual == null || !visual.isAlive()) {
            double startUnits = item.slotUnits() <= Math.max(1.0D, slotsPerTick + maxCorrection) ? 0.0D : item.slotUnits();
            startUnits = Mth.clamp(startUnits, 0.0D, Math.max(0.0D, limit));
            if (startUnits == 0.0D && containerArrivals.contains(item.visualKey())) {
                startUnits = -containerTravelInSlots();
            }
            Vec3 position = new BeltLocation(belt, startUnits / belt.bufferSize()).position();
            visual = new SplineVisualItemEntity(belt, level, position, item.stack(), startUnits);
            if (!SatiscraftoryClient.addConveyorItemVisual(visual)) {
                items.remove(item.visualKey());
                return null;
            }
            items.put(item.visualKey(), visual);
        } else if (!ItemStack.isSameItemSameTags(visual.getItem(), item.stack())) {
            visual.setItem(item.stack());
        }

        visual.snapshotOrder = clientSnapshotOrder;
        visual.departureTicks = 0;
        visual.advanceTowards(item.slotUnits(), limit, now, slotsPerTick, maxCorrection);
        return visual;
    }

    private double containerTravelInSlots() {
        return CONTAINER_TRAVEL_DISTANCE / belt.visualSlotLength();
    }

    private double advanceContainerDepartures(long now, double slotsPerTick, double maxCorrection) {
        double edge = belt.bufferSize();
        if (containerDepartures.isEmpty()) {
            return edge;
        }

        double end = edge + containerTravelInSlots();
        double nextVisualLimit = edge;
        var iterator = containerDepartures.iterator();
        while (iterator.hasNext()) {
            SplineVisualItemEntity visual = iterator.next();
            if (!visual.isAlive() || visual.slotUnits >= end) {
                visual.discard();
                iterator.remove();
                continue;
            }
            // Catch up to the outlet if the snapshot arrived early, then continue at belt speed inside.
            double target = Math.max(edge, visual.slotUnits + slotsPerTick);
            visual.advanceTowards(target, end, now, slotsPerTick, maxCorrection);
            nextVisualLimit = Math.min(nextVisualLimit, visual.slotUnits - 1.0D);
        }
        return nextVisualLimit;
    }

    private void finishDepartingVisuals(Set<Long> activeVisualKeys, @Nullable ConveyorBlockEntity nextBelt, long now,
                                        double slotsPerTick, double maxCorrection) {
        boolean directOutput = nextBelt != null;
        int handoffGrace = HANDOFF_GRACE_TICKS + Math.max(updateIntervalTicks,
                directOutput ? nextBelt.visuals().updateIntervalTicks : 1) - 1;
        var iterator = items.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, SplineVisualItemEntity> entry = iterator.next();
            SplineVisualItemEntity visual = entry.getValue();
            if (visual.isAlive()) {
                if (activeVisualKeys.contains(entry.getKey())) {
                    continue;
                }
                if (directOutput || visual.slotUnits >= belt.bufferSize() - Math.max(1.0D, slotsPerTick * 2.0D)) {
                    if (visual.slotUnits < belt.bufferSize() || visual.previousSlotUnits < belt.bufferSize()) {
                        visual.advanceTowards(belt.bufferSize(), belt.bufferSize(), now, slotsPerTick, maxCorrection);
                        continue;
                    }
                    if (directOutput && ++visual.departureTicks <= handoffGrace) {
                        continue;
                    }
                }
            }
            visual.discard();
            iterator.remove();
        }
    }

    void clear() {
        for (SplineVisualItemEntity visual : items.values()) {
            visual.discard();
        }
        for (SplineVisualItemEntity visual : containerDepartures) {
            visual.discard();
        }
        items.clear();
        containerDepartures.clear();
        containerArrivals.clear();
    }

    @Nullable
    CargoHit pickDisplayedItem(BeltTargeting.Ray ray, float partialTick) {
        CargoHit nearest = null;
        double closest = ray.from().distanceToSqr(ray.to());
        for (var entry : items.entrySet()) {
            SplineVisualItemEntity visual = entry.getValue();
            if (entry.getKey() <= 0L || !visual.isAlive() || visual.locationOnBelt(partialTick).progress() < 0.0D) {
                continue;
            }
            Vec3 hit = SatiscraftoryClient.clipConveyorItem(visual, ray, partialTick);
            if (hit != null && ray.from().distanceToSqr(hit) < closest) {
                nearest = new CargoHit(belt, entry.getKey(), hit);
                closest = ray.from().distanceToSqr(hit);
            }
        }
        return nearest;
    }

}
