package art.arcane.satiscraftory.interaction;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = Satiscraftory.MODID)
public final class BeltTargeting {
    // Each level's index is accessed only on that level's game thread.
    private static final Map<Level, Index> LEVELS = new ConcurrentHashMap<>();

    private BeltTargeting() {}

    public static void update(ConveyorBlockEntity belt) {
        remove(belt);
        Level level = belt.getLevel();
        if (level == null || belt.isRemoved() || belt.getEndPos() == null) {
            return;
        }
        Index index = LEVELS.computeIfAbsent(level, ignored -> new Index());
        AABB bounds = belt.getRenderBoundingBox();
        List<Long> chunks = new ArrayList<>();
        for (int x = chunk(bounds.minX); x <= chunk(bounds.maxX); x++) {
            for (int z = chunk(bounds.minZ); z <= chunk(bounds.maxZ); z++) {
                long key = ChunkPos.asLong(x, z);
                index.sections.computeIfAbsent(key, ignored -> new HashSet<>()).add(belt);
                chunks.add(key);
            }
        }
        index.memberships.put(belt, chunks);
    }

    public static void remove(ConveyorBlockEntity belt) {
        Level level = belt.getLevel();
        Index index = level == null ? null : LEVELS.get(level);
        if (index == null) {
            return;
        }
        List<Long> chunks = index.memberships.remove(belt);
        if (chunks != null) {
            for (long key : chunks) {
                Set<ConveyorBlockEntity> belts = index.sections.get(key);
                belts.remove(belt);
                if (belts.isEmpty()) {
                    index.sections.remove(key);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            LEVELS.remove(level);
        }
    }

    public static Set<ConveyorBlockEntity> candidates(Level level, Ray ray) {
        Index index = LEVELS.get(level);
        if (index == null) {
            return Set.of();
        }
        Set<ConveyorBlockEntity> result = new HashSet<>();
        AABB bounds = new AABB(ray.from(), ray.to());
        for (int x = chunk(bounds.minX); x <= chunk(bounds.maxX); x++) {
            for (int z = chunk(bounds.minZ); z <= chunk(bounds.maxZ); z++) {
                Set<ConveyorBlockEntity> section = index.sections.get(ChunkPos.asLong(x, z));
                if (section != null) {
                    result.addAll(section);
                }
            }
        }
        return result;
    }

    public static Ray playerRay(Player player, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 end = eye.add(player.getViewVector(partialTick).scale(player.getBlockReach()));
        var block = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return new Ray(eye, block.getType() == HitResult.Type.BLOCK ? block.getLocation() : end);
    }

    @Nullable
    public static Hit nearestBelt(Level level, Ray ray) {
        Hit nearest = null;
        double closest = ray.from().distanceToSqr(ray.to());
        for (ConveyorBlockEntity belt : candidates(level, ray)) {
            if (belt.isRemoved() || belt.getEndPos() == null) {
                continue;
            }
            AABB bounds = belt.getRenderBoundingBox();
            if (!bounds.contains(ray.from()) && bounds.clip(ray.from(), ray.to()).isEmpty()) {
                continue;
            }
            Vec3 offset = Vec3.atLowerCornerOf(belt.getBlockPos());
            Vec3 hit = belt.getHitbox().clip(ray.from().subtract(offset), ray.to().subtract(offset));
            if (hit != null) {
                Vec3 location = hit.add(offset);
                double distance = ray.from().distanceToSqr(location);
                if (distance < closest) {
                    nearest = new Hit(belt, location);
                    closest = distance;
                }
            }
        }
        return nearest;
    }

    public static AABB itemBounds(Vec3 location) {
        return new AABB(location.x - 0.25D, location.y, location.z - 0.25D,
                location.x + 0.25D, location.y + 0.6D, location.z + 0.25D);
    }

    private static int chunk(double coordinate) {
        return SectionPos.blockToSectionCoord(Mth.floor(coordinate));
    }

    public record Ray(Vec3 from, Vec3 to) {}

    public record Hit(ConveyorBlockEntity belt, Vec3 location) {}

    private static final class Index {
        private final Map<Long, Set<ConveyorBlockEntity>> sections = new HashMap<>();
        private final Map<ConveyorBlockEntity, List<Long>> memberships = new HashMap<>();
    }
}
