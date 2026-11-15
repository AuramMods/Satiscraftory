package art.arcane.satiscraftory.client;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = Satiscraftory.MODID, value = Dist.CLIENT)
public final class ConveyorMiningProgress {
    private static final int TIMEOUT_TICKS = 60;
    private static final Map<Integer, Progress> BY_BREAKER = new HashMap<>();

    private ConveyorMiningProgress() {}

    public static void update(BlockPos anchor, int breakerId, int stage) {
        var level = Minecraft.getInstance().level;
        if (stage >= 0 && stage < 10 && level != null && level.getBlockEntity(anchor) instanceof ConveyorBlockEntity belt) {
            BY_BREAKER.put(breakerId, new Progress(belt, stage, level.getGameTime() + TIMEOUT_TICKS));
        } else {
            Progress current = BY_BREAKER.get(breakerId);
            if (current != null && current.belt().getBlockPos().equals(anchor)) {
                BY_BREAKER.remove(breakerId);
            }
        }
    }

    public static int stageFor(ConveyorBlockEntity belt) {
        int stage = -1;
        for (Progress progress : BY_BREAKER.values()) {
            if (progress.belt() == belt) {
                stage = Math.max(stage, progress.stage());
            }
        }
        return stage;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || BY_BREAKER.isEmpty()) {
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            BY_BREAKER.clear();
            return;
        }
        long now = level.getGameTime();
        BY_BREAKER.values().removeIf(progress -> progress.belt().getLevel() != level
                || progress.belt().isRemoved() || progress.expiresAt() <= now);
    }

    private record Progress(ConveyorBlockEntity belt, int stage, long expiresAt) {}
}
