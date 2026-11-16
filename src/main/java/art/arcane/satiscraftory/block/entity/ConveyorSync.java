package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.data.route.BeltRoute;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Schedules snapshots independently for each viewer and retains their unsent transfer cues. */
final class ConveyorSync {
    static final String INTERVAL_TAG = "sync_interval_ticks";
    static final String GAME_TIME_TAG = "sync_game_time";
    static final String PICKED_UP_ITEMS_TAG = "picked_up_items";
    static final int MAX_INTERVAL_TICKS = 20;
    private static final double CLOSE_RANGE_BLOCKS = 4.0D;
    private static final double VISUAL_RANGE_BLOCKS = 16.0D;

    private final ConveyorBlockEntity belt;
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Set<Long> pickedUpItems = new HashSet<>();
    private long revision;
    private boolean dirty = true;
    private boolean immediate;

    ConveyorSync(ConveyorBlockEntity belt) {
        this.belt = belt;
    }

    long revision() {
        return revision;
    }

    void loadRevision(long revision) {
        this.revision = Math.max(0L, revision);
        viewers.clear();
        pickedUpItems.clear();
        dirty = true;
        immediate = false;
    }

    void markDirty() {
        dirty = true;
    }

    void requestImmediate() {
        dirty = true;
        immediate = true;
    }

    void pickedUp(long itemId) {
        pickedUpItems.add(itemId);
        requestImmediate();
    }

    void writeUpdateMetadata(CompoundTag tag) {
        if (belt.getLevel() != null) {
            tag.putLong(GAME_TIME_TAG, belt.getLevel().getGameTime());
        }
        writeIds(tag, PICKED_UP_ITEMS_TAG, pickedUpItems.stream().toList());
    }

    void tick(ServerLevel level) {
        boolean hasCargo = belt.transport().hasCargo();
        if (dirty || hasCargo) {
            revision++;
        }
        long now = level.getGameTime();
        CompoundTag snapshot = null;
        for (ServerPlayer player : level.players()) {
            double distanceSqr = distanceToBeltSqr(player);
            if (distanceSqr > VISUAL_RANGE_BLOCKS * VISUAL_RANGE_BLOCKS) {
                continue;
            }
            Viewer viewer = viewers.computeIfAbsent(player.getUUID(), ignored -> new Viewer());
            viewer.lastSeenTick = now;
            viewer.pickups.addAll(pickedUpItems);
            viewer.arrivals.addAll(belt.transport().containerArrivals());
            viewer.departures.addAll(belt.transport().containerDepartures());
            int interval = intervalTicks(Math.sqrt(distanceSqr));
            boolean changed = hasCargo || viewer.revision != revision || viewer.interval != interval;
            if (!changed || (!immediate && viewer.lastSentTick != Long.MIN_VALUE
                    && now - viewer.lastSentTick < interval)) {
                continue;
            }
            if (snapshot == null) {
                snapshot = belt.getUpdateTag();
            }
            CompoundTag tag = snapshot.copy();
            tag.putInt(INTERVAL_TAG, interval);
            writeIds(tag, PICKED_UP_ITEMS_TAG, viewer.pickups);
            writeIds(tag, ConveyorTransport.CONTAINER_ARRIVALS_TAG, viewer.arrivals);
            writeIds(tag, ConveyorTransport.CONTAINER_DEPARTURES_TAG, viewer.departures);
            player.connection.send(ClientboundBlockEntityDataPacket.create(belt, ignored -> tag));
            viewer.lastSentTick = now;
            viewer.revision = revision;
            viewer.interval = interval;
            viewer.pickups.clear();
            viewer.arrivals.clear();
            viewer.departures.clear();
        }
        viewers.values().removeIf(viewer -> viewer.lastSeenTick != now);
        pickedUpItems.clear();
        dirty = false;
        immediate = false;
    }

    static int intervalTicks(double distance) {
        double fraction = Mth.clamp((distance - CLOSE_RANGE_BLOCKS) / (VISUAL_RANGE_BLOCKS - CLOSE_RANGE_BLOCKS), 0.0D, 1.0D);
        return 1 + (int) Math.round(fraction * (MAX_INTERVAL_TICKS - 1));
    }

    boolean isWithinVisualRange(Entity entity) {
        return distanceToBeltSqr(entity) <= VISUAL_RANGE_BLOCKS * VISUAL_RANGE_BLOCKS;
    }

    private double distanceToBeltSqr(Entity entity) {
        if (!entity.isAlive()) {
            return Double.POSITIVE_INFINITY;
        }
        Vec3 local = entity.position().subtract(belt.getBlockPos().getX(),
                belt.getBlockPos().getY() + BeltRoute.DECK_HEIGHT, belt.getBlockPos().getZ());
        BeltRoute route = belt.getVisualRoute();
        double closest = Double.POSITIVE_INFINITY;
        for (int i = 1; i < route.pointCount(); i++) {
            Vec3 start = route.point(i - 1);
            Vec3 segment = route.point(i).subtract(start);
            double fraction = Mth.clamp(local.subtract(start).dot(segment) / segment.lengthSqr(), 0.0D, 1.0D);
            closest = Math.min(closest, local.distanceToSqr(start.x + segment.x * fraction,
                    start.y + segment.y * fraction, start.z + segment.z * fraction));
            if (closest <= CLOSE_RANGE_BLOCKS * CLOSE_RANGE_BLOCKS) {
                break;
            }
        }
        return closest;
    }

    private static void writeIds(CompoundTag tag, String key, List<Long> ids) {
        tag.remove(key);
        if (!ids.isEmpty()) {
            tag.putLongArray(key, ids);
        }
    }

    private static final class Viewer {
        private long lastSentTick = Long.MIN_VALUE;
        private long lastSeenTick;
        private long revision = Long.MIN_VALUE;
        private int interval;
        private final List<Long> pickups = new ArrayList<>();
        private final List<Long> arrivals = new ArrayList<>();
        private final List<Long> departures = new ArrayList<>();
    }
}
