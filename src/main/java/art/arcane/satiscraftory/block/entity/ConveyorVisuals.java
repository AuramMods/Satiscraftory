package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.block.ConveyorBlock;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity.CargoHit;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity.SplineVisualItemEntity;
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
    private static final int HANDOFF_GRACE_TICKS = 3;

    private static long nextClientSnapshotOrder;

    private final ConveyorBlockEntity belt;
    private final Map<Long, SplineVisualItemEntity> items = new HashMap<>();
    private long lastClientAppliedRevision = Long.MIN_VALUE;
    private long clientSnapshotGameTime = Long.MIN_VALUE;
    private long clientSnapshotOrder;

    ConveyorVisuals(ConveyorBlockEntity belt) {
        this.belt = belt;
    }

    boolean acceptsSnapshot(long revision) {
        return revision >= lastClientAppliedRevision;
    }

    void applySnapshot(long gameTime, long revision, long[] pickedUpItems) {
        lastClientAppliedRevision = revision;
        clientSnapshotGameTime = gameTime;
        clientSnapshotOrder = ++nextClientSnapshotOrder;
        for (long itemId : pickedUpItems) {
            SplineVisualItemEntity visual = items.remove(itemId);
            if (visual != null) {
                visual.discard();
            }
        }
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
        double maxCorrection = Math.max(MIN_CLIENT_CORRECTION_SLOTS_PER_TICK,
                slotsPerTick * CLIENT_CORRECTION_SPEED_FRACTION);

        Set<Long> activeVisualKeys = new HashSet<>();
        double nextVisualLimit = belt.bufferSize();
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

        finishDepartingVisuals(activeVisualKeys, nextBelt != null, now, slotsPerTick, maxCorrection);
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
            Vec3 position = belt.computeVisualPositionForSlotUnits(startUnits);
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

    private void finishDepartingVisuals(Set<Long> activeVisualKeys, boolean directOutput, long now,
                                        double slotsPerTick, double maxCorrection) {
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
                    if (directOutput && ++visual.departureTicks <= HANDOFF_GRACE_TICKS) {
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
        items.clear();
    }

    @Nullable
    CargoHit pickDisplayedItem(BeltTargeting.Ray ray, float partialTick) {
        CargoHit nearest = null;
        double closest = ray.from().distanceToSqr(ray.to());
        for (var entry : items.entrySet()) {
            if (entry.getKey() <= 0L || !entry.getValue().isAlive()) {
                continue;
            }
            Vec3 hit = SatiscraftoryClient.clipConveyorItem(entry.getValue(), ray, partialTick);
            if (hit != null && ray.from().distanceToSqr(hit) < closest) {
                nearest = new CargoHit(belt, entry.getKey(), hit);
                closest = ray.from().distanceToSqr(hit);
            }
        }
        return nearest;
    }

}
