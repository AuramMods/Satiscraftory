package art.arcane.satiscraftory.client;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity;
import art.arcane.satiscraftory.interaction.BeltTargeting;
import art.arcane.satiscraftory.network.BeltInteractionNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

@Mod.EventBusSubscriber(modid = Satiscraftory.MODID, value = Dist.CLIENT)
public final class ConveyorInteractionInput {
    private static final int DIG_REFRESH_TICKS = 4;
    private static ConveyorBlockEntity digging;
    private static int lastDigPacketTick;

    private ConveyorInteractionInput() {}

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || player.isSpectator() || !(event.isAttack() || event.isUseItem())) {
            return;
        }
        float partialTick = event.isUseItem() ? minecraft.getFrameTime() : 1.0F;
        BeltTargeting.Ray ray = aim(minecraft, partialTick);
        BeltTargeting.Hit belt = BeltTargeting.nearestBelt(player.level(), ray);
        if (event.isAttack() && belt != null) {
            if (minecraft.gameMode.isDestroying()) {
                minecraft.gameMode.stopDestroyBlock();
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            updateDigTarget(player, belt, minecraft);
        } else if (event.isUseItem() && event.getHand() == InteractionHand.MAIN_HAND) {
            if (belt != null) {
                ray = new BeltTargeting.Ray(ray.from(), belt.location());
            }
            ConveyorBlockEntity.CargoHit cargo = nearestCargo(player, ray, partialTick);
            if (cargo != null) {
                event.setCanceled(true);
                event.setSwingHand(false);
                sendLook(player);
                BeltInteractionNetwork.pickup(cargo.belt().getBlockPos(), cargo.itemId());
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            digging = null;
            return;
        }
        BeltTargeting.Hit target = null;
        if (minecraft.screen == null && minecraft.mouseHandler.isMouseGrabbed() && !minecraft.isPaused()
                && minecraft.options.keyAttack.isDown() && !player.isSpectator() && !player.isUsingItem()) {
            target = BeltTargeting.nearestBelt(player.level(), aim(minecraft, 1.0F));
        }
        updateDigTarget(player, target, minecraft);
    }

    private static void updateDigTarget(LocalPlayer player, @Nullable BeltTargeting.Hit target, Minecraft minecraft) {
        ConveyorBlockEntity next = target == null ? null : target.belt();
        if (next != digging) {
            if (digging != null && digging.getLevel() == player.level()) {
                BeltInteractionNetwork.dig(digging.getBlockPos(), false);
            }
            digging = next;
            lastDigPacketTick = player.tickCount - DIG_REFRESH_TICKS;
        }
        if (target != null && player.tickCount - lastDigPacketTick >= DIG_REFRESH_TICKS) {
            sendLook(player);
            BeltInteractionNetwork.dig(next.getBlockPos(), true);
            lastDigPacketTick = player.tickCount;
            player.swing(InteractionHand.MAIN_HAND);

            Vec3 contact = target.location();
            minecraft.particleEngine.add(new TerrainParticle(minecraft.level, contact.x, contact.y, contact.z,
                    0.0D, 0.1D, 0.0D, next.getBlockState(), next.getBlockPos()));
        }
    }

    private static void sendLook(LocalPlayer player) {
        player.connection.send(new ServerboundMovePlayerPacket.PosRot(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), player.onGround()));
    }

    @SubscribeEvent
    public static void onBlockHighlight(RenderHighlightEvent.Block event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && !minecraft.player.isSpectator() && !minecraft.options.hideGui
                && BeltTargeting.nearestBelt(minecraft.level, aim(minecraft, event.getPartialTick())) != null) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void renderOutline(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.isSpectator() || minecraft.options.hideGui) {
            return;
        }
        BeltTargeting.Hit selected = BeltTargeting.nearestBelt(minecraft.level, aim(minecraft, event.getPartialTick()));
        if (selected == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        BlockPos origin = selected.belt().getBlockPos();
        var poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(origin.getX() - camera.x, origin.getY() - camera.y, origin.getZ() - camera.z);
        var buffer = minecraft.renderBuffers().bufferSource();
        var lines = buffer.getBuffer(RenderType.lines());
        var transform = poseStack.last();
        selected.belt().getHitbox().forEachOutline((from, to) ->
                ConveyorPlacementPreview.drawLine(lines, transform.pose(), transform.normal(), from, to, 0, 0, 0, 102));
        buffer.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static BeltTargeting.Ray aim(Minecraft minecraft, float partialTick) {
        BeltTargeting.Ray ray = BeltTargeting.playerRay(minecraft.player, partialTick);
        if (minecraft.hitResult instanceof EntityHitResult entity
                && ray.from().distanceToSqr(entity.getLocation()) < ray.from().distanceToSqr(ray.to())) {
            return new BeltTargeting.Ray(ray.from(), entity.getLocation());
        }
        return ray;
    }

    @Nullable
    private static ConveyorBlockEntity.CargoHit nearestCargo(LocalPlayer player, BeltTargeting.Ray ray, float partialTick) {
        ConveyorBlockEntity.CargoHit nearest = null;
        double closest = ray.from().distanceToSqr(ray.to());
        for (ConveyorBlockEntity belt : BeltTargeting.candidates(player.level(), ray)) {
            var hit = belt.pickDisplayedItem(ray, partialTick);
            if (hit != null && ray.from().distanceToSqr(hit.location()) < closest) {
                nearest = hit;
                closest = ray.from().distanceToSqr(hit.location());
            }
        }
        return nearest;
    }
}
