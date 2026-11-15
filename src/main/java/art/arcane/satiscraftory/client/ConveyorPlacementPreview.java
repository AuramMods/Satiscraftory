package art.arcane.satiscraftory.client;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.config.BeltClientConfig;
import art.arcane.satiscraftory.data.route.BeltRoute;
import art.arcane.satiscraftory.data.route.BeltRouteMode;
import art.arcane.satiscraftory.item.ConveyorItem;
import art.arcane.satiscraftory.network.BeltPlacementNetwork;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = Satiscraftory.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ConveyorPlacementPreview {
    private static final float RAIL_OFFSET = 0.16F;
    private static final double BELT_Y_OFFSET = 0.11D;
    private static final double FACE_OUTSET = 0.003D;
    private static PreviewKey previewKey;
    private static BeltRoute previewRoute;

    private ConveyorPlacementPreview() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || !ConveyorItem.isHoldingBelt(player)) {
            return;
        }

        // Use the same crosshair target that Minecraft passes to right-click interactions.
        if (!(minecraft.hitResult instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        if (player.isShiftKeyDown()) {
            renderContainerFace(event, minecraft, level, player, blockHit);
        }

        ConveyorItem.PlacementState placement = ConveyorItem.getActivePlacement(player, level);
        if (placement == null) {
            return;
        }
        BlockPos startPos = placement.startPos();

        BlockPos previewEndPos = ConveyorItem.resolvePreviewEndPos(level, blockHit.getBlockPos(), blockHit.getDirection());
        Direction startFacing = placement.resolveStartFacing(level, previewEndPos, player.getDirection());
        Direction endFacing = placement.resolveEndFacing(level, previewEndPos, startFacing, player.getDirection());
        PreviewKey key = new PreviewKey(startPos, startFacing, previewEndPos, endFacing, placement.mode());
        if (!key.equals(previewKey)) {
            previewRoute = key.mode().create(startPos, startFacing, previewEndPos, endFacing);
            previewKey = key;
        }
        boolean overMaxLength = previewRoute.lengthInBlocks() > ConveyorItem.MAX_BELT_LENGTH_BLOCKS;

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(startPos.getX() - camera.x, startPos.getY() + BELT_Y_OFFSET - camera.y,
                startPos.getZ() - camera.z);

        MultiBufferSource.BufferSource buffer = minecraft.renderBuffers().bufferSource();
        VertexConsumer lineBuffer = buffer.getBuffer(RenderType.lines());
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();

        for (int i = 1; i < previewRoute.pointCount(); i++) {
            Vec3 from = previewRoute.point(i - 1);
            Vec3 to = previewRoute.point(i);
            Vec3 fromSide = previewRoute.side(i - 1).scale(RAIL_OFFSET);
            Vec3 toSide = previewRoute.side(i).scale(RAIL_OFFSET);

            if (overMaxLength) {
                drawLine(lineBuffer, pose, normal, from, to, 255, 64, 64, 255);
                drawLine(lineBuffer, pose, normal, from.add(fromSide), to.add(toSide), 220, 40, 40, 220);
                drawLine(lineBuffer, pose, normal, from.subtract(fromSide), to.subtract(toSide), 220, 40, 40, 220);
            } else {
                drawPreviewLine(lineBuffer, pose, normal, from, to);
                drawLine(lineBuffer, pose, normal, from.add(fromSide), to.add(toSide), 20, 170, 185, 210);
                drawLine(lineBuffer, pose, normal, from.subtract(fromSide), to.subtract(toSide), 20, 170, 185, 210);
            }
        }

        buffer.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static void renderContainerFace(RenderLevelStageEvent event, Minecraft minecraft, ClientLevel level,
                                            LocalPlayer player, BlockHitResult target) {
        BlockPos pos = target.getBlockPos();
        Direction face = target.getDirection();
        if (!ConveyorItem.isContainerInputFace(level, pos, face)
                || !ConveyorItem.canPlaceBeltStart(level, pos.relative(face))) {
            return;
        }

        VoxelShape shape = level.getBlockState(pos).getShape(level, pos, CollisionContext.of(player));
        if (shape.isEmpty()) {
            return;
        }
        AABB bounds = shape.bounds();
        double minX = bounds.minX;
        double maxX = bounds.maxX;
        double minZ = bounds.minZ;
        double maxZ = bounds.maxZ;
        if (face.getAxis() == Direction.Axis.X) {
            minX = maxX = face == Direction.EAST ? maxX + FACE_OUTSET : minX - FACE_OUTSET;
        } else {
            minZ = maxZ = face == Direction.SOUTH ? maxZ + FACE_OUTSET : minZ - FACE_OUTSET;
        }

        Vec3 lowerLeft = new Vec3(minX, bounds.minY, minZ);
        Vec3 lowerRight = new Vec3(maxX, bounds.minY, maxZ);
        Vec3 upperRight = new Vec3(maxX, bounds.maxY, maxZ);
        Vec3 upperLeft = new Vec3(minX, bounds.maxY, minZ);

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);

        MultiBufferSource.BufferSource buffer = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffer.getBuffer(RenderType.lines());
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();
        drawPreviewLine(lines, pose, normal, lowerLeft, lowerRight);
        drawPreviewLine(lines, pose, normal, lowerRight, upperRight);
        drawPreviewLine(lines, pose, normal, upperRight, upperLeft);
        drawPreviewLine(lines, pose, normal, upperLeft, lowerLeft);

        buffer.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getKey() != GLFW.GLFW_KEY_TAB || event.getAction() == GLFW.GLFW_RELEASE) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.screen != null || player == null || !ConveyorItem.isHoldingBelt(player)) {
            return;
        }

        ConveyorItem.PlacementState placement = ConveyorItem.getActivePlacement(player, player.level());
        if (placement == null) {
            return;
        }

        if (event.getAction() == GLFW.GLFW_PRESS) {
            BeltRouteMode mode = placement.mode().next();
            BeltClientConfig.rememberMode(mode);
            ConveyorItem.selectRouteMode(player, mode);
            BeltPlacementNetwork.selectRouteMode(mode);
            player.displayClientMessage(Component.literal("Belt route: " + mode.label()), true);
        }
        if (minecraft.options.keyPlayerList.matches(event.getKey(), event.getScanCode())) {
            minecraft.options.keyPlayerList.setDown(false);
        }
    }

    private static void drawPreviewLine(VertexConsumer lines, Matrix4f pose, Matrix3f normal, Vec3 from, Vec3 to) {
        drawLine(lines, pose, normal, from, to, 45, 240, 255, 255);
    }

    static void drawLine(VertexConsumer consumer,
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

    private record PreviewKey(BlockPos start, Direction startFacing, BlockPos end, Direction endFacing,
                              BeltRouteMode mode) {
    }
}
