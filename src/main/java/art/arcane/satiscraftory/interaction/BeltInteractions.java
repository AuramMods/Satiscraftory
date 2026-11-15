package art.arcane.satiscraftory.interaction;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity;
import art.arcane.satiscraftory.network.BeltInteractionNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.WeakHashMap;

@Mod.EventBusSubscriber(modid = Satiscraftory.MODID)
public final class BeltInteractions {
    private static final int DIG_TIMEOUT_TICKS = 10;
    private static final int PROGRESS_REFRESH_TICKS = 20;
    private static final Map<ServerPlayer, Dig> DIGGING = new WeakHashMap<>();

    private BeltInteractions() {}

    public static void pickup(ServerPlayer player, BlockPos anchor, long itemId) {
        if (!player.isAlive() || player.isSpectator() || !canInteract(player, anchor)
                || !(player.level().getBlockEntity(anchor) instanceof ConveyorBlockEntity belt)
                || belt.getEndPos() == null) {
            return;
        }
        BeltTargeting.Ray ray = BeltTargeting.playerRay(player, 1.0F);
        BeltTargeting.Hit hit = BeltTargeting.nearestBelt(player.level(), ray);
        if (hit != null) {
            ray = new BeltTargeting.Ray(ray.from(), hit.location());
        }
        if (belt.pickUpItem(player, itemId, ray)) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F, 1.5F);
        }
    }

    public static void dig(ServerPlayer player, BlockPos anchor, boolean held) {
        if (!held) {
            stopDigging(player);
            return;
        }
        BeltTargeting.Hit hit = miningTarget(player, anchor);
        if (hit == null) {
            stopDigging(player);
            return;
        }
        Dig dig = DIGGING.get(player);
        if (dig != null && dig.belt == hit.belt() && ItemStack.isSameItemSameTags(dig.tool, player.getMainHandItem())) {
            dig.lastInputTick = player.tickCount;
            return;
        }

        stopDigging(player);
        var event = ForgeHooks.onLeftClickBlock(player, anchor, aimedFace(player),
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
        if (event.isCanceled() || event.getResult() == Event.Result.DENY || event.getUseItem() == Event.Result.DENY) {
            return;
        }
        if (event.getUseBlock() != Event.Result.DENY) {
            hit.belt().getBlockState().attack(player.level(), anchor, player);
        }
        if (player.isCreative()) {
            breakBelt(player, hit);
            return;
        }
        DIGGING.put(player, new Dig(hit.belt(), player.getMainHandItem().copy(), player.tickCount));
    }

    @SubscribeEvent
    public static void tickPlayer(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        Dig dig = DIGGING.get(player);
        if (dig == null) {
            return;
        }
        BlockPos anchor = dig.belt.getBlockPos();
        if (player.tickCount - dig.lastInputTick > DIG_TIMEOUT_TICKS
                || !ItemStack.isSameItemSameTags(dig.tool, player.getMainHandItem())) {
            stopDigging(player);
            return;
        }
        BeltTargeting.Hit hit = miningTarget(player, anchor);
        if (hit == null || hit.belt() != dig.belt) {
            stopDigging(player);
            return;
        }

        var state = dig.belt.getBlockState();
        dig.progress += state.getDestroyProgress(player, player.level(), anchor);
        if (dig.progress >= 1.0F) {
            stopDigging(player);
            breakBelt(player, hit);
        } else if (dig.progress > 0.0F) {
            int stage = Math.min(9, (int) (dig.progress * 10.0F));
            if (stage != dig.stage || player.tickCount - dig.lastProgressTick >= PROGRESS_REFRESH_TICKS) {
                dig.stage = stage;
                dig.lastProgressTick = player.tickCount;
                BeltInteractionNetwork.progress(player.serverLevel(), anchor, player.getId(), stage);
            }
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stopDigging(player);
        }
    }

    private static void stopDigging(ServerPlayer player) {
        Dig dig = DIGGING.remove(player);
        if (dig != null && dig.stage >= 0 && dig.belt.getLevel() instanceof ServerLevel level) {
            BeltInteractionNetwork.progress(level, dig.belt.getBlockPos(), player.getId(), -1);
        }
    }

    private static void breakBelt(ServerPlayer player, BeltTargeting.Hit hit) {
        var belt = hit.belt();
        var state = belt.getBlockState();
        if (belt.destroyAt(player, hit.location()) && belt.isRemoved()) {
            player.level().levelEvent(2001, BlockPos.containing(hit.location()), Block.getId(state));
        }
    }

    @Nullable
    private static BeltTargeting.Hit miningTarget(ServerPlayer player, BlockPos anchor) {
        if (!player.isAlive() || player.isSpectator() || player.isUsingItem() || !canInteract(player, anchor)
                || player.blockActionRestricted(player.level(), anchor, player.gameMode.getGameModeForPlayer())) {
            return null;
        }
        BeltTargeting.Hit hit = BeltTargeting.nearestBelt(player.level(), BeltTargeting.playerRay(player, 1.0F));
        return hit != null && hit.belt().getBlockPos().equals(anchor)
                && canInteract(player, BlockPos.containing(hit.location())) ? hit : null;
    }

    private static boolean canInteract(ServerPlayer player, BlockPos pos) {
        return player.level().hasChunkAt(pos) && player.level().getWorldBorder().isWithinBounds(pos)
                && player.level().mayInteract(player, pos);
    }

    private static Direction aimedFace(ServerPlayer player) {
        Vec3 direction = player.getLookAngle();
        return Direction.getNearest(-direction.x, -direction.y, -direction.z);
    }

    private static final class Dig {
        private final ConveyorBlockEntity belt;
        private final ItemStack tool;
        private int lastInputTick;
        private float progress;
        private int stage = -1;
        private int lastProgressTick;

        private Dig(ConveyorBlockEntity belt, ItemStack tool, int lastInputTick) {
            this.belt = belt;
            this.tool = tool;
            this.lastInputTick = lastInputTick;
        }
    }
}
