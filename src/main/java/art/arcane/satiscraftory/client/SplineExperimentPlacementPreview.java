package art.arcane.satiscraftory.client;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.item.SplineExperimentConveyorItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

@Mod.EventBusSubscriber(modid = Satiscraftory.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SplineExperimentPlacementPreview {
    private static final int CURVE_SEGMENTS = 40;
    private static final float RAIL_OFFSET = 0.16F;
    private static final double BELT_Y_OFFSET = 0.11D;
    private static final double EDGE_OFFSET = 0.5D;

    private SplineExperimentPlacementPreview() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || !holdingSplineItem(player)) {
            return;
        }

        BlockPos startPos = SplineExperimentConveyorItem.getStoredStartPos(player, level);
        if (startPos == null) {
            return;
        }

        HitResult hit = player.pick(32.0D, event.getPartialTick(), false);
        if (!(hit instanceof BlockHitResult blockHit)) {
            return;
        }

        BlockPos previewEndPos = SplineExperimentConveyorItem.resolvePreviewEndPos(level, blockHit.getBlockPos(), blockHit.getDirection());
        Direction startFacing = SplineExperimentConveyorItem.resolveStartFacing(level, startPos, previewEndPos, player.getDirection());
        Direction endFacing = SplineExperimentConveyorItem.resolveEndFacing(level, startPos, previewEndPos, player.getDirection());

        Vec3 start = anchorToEdge(new Vec3(startPos.getX() + 0.5D, startPos.getY() + BELT_Y_OFFSET, startPos.getZ() + 0.5D), startFacing.getOpposite());
        Vec3 end = anchorToEdge(new Vec3(previewEndPos.getX() + 0.5D, previewEndPos.getY() + BELT_Y_OFFSET, previewEndPos.getZ() + 0.5D), endFacing);

        Vec3 flatDelta = new Vec3(end.x - start.x, 0.0D, end.z - start.z);
        double horizontalDistance = Math.sqrt(flatDelta.lengthSqr());
        double tangentLength = Math.max(0.85D, Math.min(4.0D, horizontalDistance * 0.45D));
        Vec3 startForward = directionVector(startFacing);
        Vec3 endForward = directionVector(endFacing);
        Vec3 c1 = start.add(startForward.scale(tangentLength));
        Vec3 c2 = end.subtract(endForward.scale(tangentLength));

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        MultiBufferSource.BufferSource buffer = minecraft.renderBuffers().bufferSource();
        VertexConsumer lineBuffer = buffer.getBuffer(RenderType.lines());
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();

        Vec3 previous = sampleBezier(start, c1, c2, end, 0.0D);
        for (int i = 1; i <= CURVE_SEGMENTS; i++) {
            double t = i / (double) CURVE_SEGMENTS;
            Vec3 current = sampleBezier(start, c1, c2, end, t);

            Vec3 segment = current.subtract(previous);
            Vec3 horizontal = new Vec3(segment.x, 0.0D, segment.z);
            if (horizontal.lengthSqr() < 1.0E-6D) {
                horizontal = new Vec3(1.0D, 0.0D, 0.0D);
            } else {
                horizontal = horizontal.normalize();
            }
            Vec3 perpendicular = new Vec3(-horizontal.z, 0.0D, horizontal.x).scale(RAIL_OFFSET);

            drawLine(lineBuffer, pose, normal, previous, current, 45, 240, 255, 255);
            drawLine(lineBuffer, pose, normal, previous.add(perpendicular), current.add(perpendicular), 20, 170, 185, 210);
            drawLine(lineBuffer, pose, normal, previous.subtract(perpendicular), current.subtract(perpendicular), 20, 170, 185, 210);
            previous = current;
        }

        buffer.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static boolean holdingSplineItem(LocalPlayer player) {
        return player.getMainHandItem().getItem() instanceof SplineExperimentConveyorItem
                || player.getOffhandItem().getItem() instanceof SplineExperimentConveyorItem;
    }

    private static Vec3 sampleBezier(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double oneMinus = 1.0D - t;
        double oneMinus2 = oneMinus * oneMinus;
        double oneMinus3 = oneMinus2 * oneMinus;
        double t2 = t * t;
        double t3 = t2 * t;
        return new Vec3(
                (p0.x * oneMinus3) + (3.0D * p1.x * oneMinus2 * t) + (3.0D * p2.x * oneMinus * t2) + (p3.x * t3),
                (p0.y * oneMinus3) + (3.0D * p1.y * oneMinus2 * t) + (3.0D * p2.y * oneMinus * t2) + (p3.y * t3),
                (p0.z * oneMinus3) + (3.0D * p1.z * oneMinus2 * t) + (3.0D * p2.z * oneMinus * t2) + (p3.z * t3)
        );
    }

    private static void drawLine(VertexConsumer consumer,
                                 Matrix4f pose,
                                 Matrix3f normalMatrix,
                                 Vec3 from,
                                 Vec3 to,
                                 int red,
                                 int green,
                                 int blue,
                                 int alpha) {
        Vector3f normal = toNormal(from, to);
        consumer.vertex(pose, (float) from.x, (float) from.y, (float) from.z)
                .color(red, green, blue, alpha)
                .normal(normalMatrix, normal.x(), normal.y(), normal.z())
                .endVertex();
        consumer.vertex(pose, (float) to.x, (float) to.y, (float) to.z)
                .color(red, green, blue, alpha)
                .normal(normalMatrix, normal.x(), normal.y(), normal.z())
                .endVertex();
    }

    private static Vector3f toNormal(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double lengthSqr = (dx * dx) + (dy * dy) + (dz * dz);
        if (lengthSqr < 1.0E-10D) {
            return new Vector3f(0.0F, 1.0F, 0.0F);
        }
        double invLength = 1.0D / Math.sqrt(lengthSqr);
        return new Vector3f((float) (dx * invLength), (float) (dy * invLength), (float) (dz * invLength));
    }

    private static Vec3 anchorToEdge(Vec3 center, Direction direction) {
        return center.add(direction.getStepX() * EDGE_OFFSET, 0.0D, direction.getStepZ() * EDGE_OFFSET);
    }

    private static Vec3 directionVector(Direction direction) {
        if (direction.getAxis().isHorizontal()) {
            return new Vec3(direction.getStepX(), 0.0D, direction.getStepZ());
        }
        return new Vec3(0.0D, 0.0D, -1.0D);
    }
}
