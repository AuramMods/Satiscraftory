package art.arcane.satiscraftory.client.render;

import art.arcane.satiscraftory.block.SplineExperimentConveyorBlock;
import art.arcane.satiscraftory.block.entity.SplineExperimentConveyorBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class SplineExperimentConveyorRenderer implements BlockEntityRenderer<SplineExperimentConveyorBlockEntity> {
    private enum UvMode {
        STANDARD,
        BAR,
        ROTATE_LEFT
    }

    private static final int CURVE_SEGMENTS = 64;
    private static final float BELT_Y_OFFSET = 0.11F;
    private static final double EDGE_OFFSET = 0.5D;
    private static final float BELT_HALF_WIDTH = 0.375F;
    private static final float RAIL_CENTER_OFFSET = 0.4375F;
    private static final float RAIL_HALF_WIDTH = 0.0625F;
    private static final float RAIL_HEIGHT = 0.12F;
    private static final float BELT_UV_PIXELS_PER_BLOCK = 16.0F;
    private static final float BAR_UV_PIXELS_PER_BLOCK = 4.0F;
    private static final float UV_WRAP_PIXELS = 16.0F;
    private static final ResourceLocation BELT_TEXTURE = ResourceLocation.fromNamespaceAndPath("satiscraftory", "block/conveyor_belt");
    private static final ResourceLocation BAR_TEXTURE = ResourceLocation.fromNamespaceAndPath("satiscraftory", "block/conveyor_bar");

    public SplineExperimentConveyorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SplineExperimentConveyorBlockEntity blockEntity,
                       float partialTick,
                       PoseStack poseStack,
                       MultiBufferSource bufferSource,
                       int packedLight,
                       int packedOverlay) {
        BlockPos endPos = blockEntity.getEndPos();
        if (endPos == null) {
            return;
        }

        Direction startFacing = getFacing(blockEntity.getBlockState());
        Direction endFacing = blockEntity.getEndFacing();
        Vec3 start = anchorToEdge(new Vec3(0.5D, BELT_Y_OFFSET, 0.5D), startFacing);
        Vec3 end = anchorToEdge(toLocalCenter(blockEntity.getBlockPos(), endPos, BELT_Y_OFFSET), endFacing);

        Vec3 startForward = directionVector(startFacing);
        Vec3 endForward = directionVector(endFacing);

        Vec3 flatDelta = new Vec3(end.x - start.x, 0.0D, end.z - start.z);
        double horizontalDistance = Math.sqrt(flatDelta.lengthSqr());
        double tangentLength = Math.max(0.85D, Math.min(4.0D, horizontalDistance * 0.45D));

        Vec3 c1 = start.add(startForward.scale(tangentLength));
        Vec3 c2 = end.subtract(endForward.scale(tangentLength));

        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite beltSprite = atlas.getSprite(BELT_TEXTURE);
        TextureAtlasSprite barSprite = atlas.getSprite(BAR_TEXTURE);
        VertexConsumer cutoutBuffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();

        Vec3[] points = new Vec3[CURVE_SEGMENTS + 1];
        Vec3[] perpendiculars = new Vec3[CURVE_SEGMENTS + 1];
        double[] distances = new double[CURVE_SEGMENTS + 1];

        for (int i = 0; i <= CURVE_SEGMENTS; i++) {
            double t = i / (double) CURVE_SEGMENTS;
            points[i] = sampleBezier(start, c1, c2, end, t);
            if (i > 0) {
                distances[i] = distances[i - 1] + points[i].distanceTo(points[i - 1]);
            }
        }

        for (int i = 0; i <= CURVE_SEGMENTS; i++) {
            perpendiculars[i] = perpendicularAt(points, i, startForward);
        }

        for (int i = 0; i < CURVE_SEGMENTS; i++) {
            Vec3 from = points[i];
            Vec3 to = points[i + 1];
            Vec3 fromPerpendicular = perpendiculars[i];
            Vec3 toPerpendicular = perpendiculars[i + 1];

            double alongStartBelt = distances[i] * BELT_UV_PIXELS_PER_BLOCK;
            double alongEndBelt = distances[i + 1] * BELT_UV_PIXELS_PER_BLOCK;
            renderRibbonSegment(cutoutBuffer, pose, normal, beltSprite,
                    from, to, fromPerpendicular, toPerpendicular,
                    BELT_HALF_WIDTH, 0.0F, UvMode.ROTATE_LEFT,
                    alongStartBelt, alongEndBelt, packedLight, packedOverlay, 0.0F);

            double alongStartBar = distances[i] * BAR_UV_PIXELS_PER_BLOCK;
            double alongEndBar = distances[i + 1] * BAR_UV_PIXELS_PER_BLOCK;
            renderRibbonSegment(cutoutBuffer, pose, normal, barSprite,
                    from, to, fromPerpendicular, toPerpendicular,
                    RAIL_HALF_WIDTH, RAIL_HEIGHT, UvMode.BAR,
                    alongStartBar, alongEndBar, packedLight, packedOverlay, RAIL_CENTER_OFFSET);
            renderRibbonSegment(cutoutBuffer, pose, normal, barSprite,
                    from, to, fromPerpendicular, toPerpendicular,
                    RAIL_HALF_WIDTH, RAIL_HEIGHT, UvMode.BAR,
                    alongStartBar, alongEndBar, packedLight, packedOverlay, -RAIL_CENTER_OFFSET);
        }
    }

    @Override
    public boolean shouldRenderOffScreen(SplineExperimentConveyorBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    private static Direction getFacing(BlockState state) {
        if (state.hasProperty(SplineExperimentConveyorBlock.FACING)) {
            return state.getValue(SplineExperimentConveyorBlock.FACING);
        }
        return Direction.NORTH;
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

    private static Vec3 toLocalCenter(BlockPos origin, BlockPos target, double yOffset) {
        return new Vec3(
                (target.getX() - origin.getX()) + 0.5D,
                (target.getY() - origin.getY()) + yOffset,
                (target.getZ() - origin.getZ()) + 0.5D
        );
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

    private static Vec3 perpendicularAt(Vec3[] points, int index, Vec3 fallbackForward) {
        Vec3 tangent;
        if (index == 0) {
            tangent = points[1].subtract(points[0]);
        } else if (index == points.length - 1) {
            tangent = points[index].subtract(points[index - 1]);
        } else {
            tangent = points[index + 1].subtract(points[index - 1]);
        }

        Vec3 horizontal = new Vec3(tangent.x, 0.0D, tangent.z);
        if (horizontal.lengthSqr() < 1.0E-6D) {
            horizontal = new Vec3(fallbackForward.x, 0.0D, fallbackForward.z);
        }
        if (horizontal.lengthSqr() < 1.0E-6D) {
            horizontal = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            horizontal = horizontal.normalize();
        }

        return new Vec3(-horizontal.z, 0.0D, horizontal.x);
    }

    private static void renderRibbonSegment(VertexConsumer consumer,
                                            Matrix4f pose,
                                            Matrix3f normalMatrix,
                                            TextureAtlasSprite sprite,
                                            Vec3 from,
                                            Vec3 to,
                                            Vec3 fromPerpendicularUnit,
                                            Vec3 toPerpendicularUnit,
                                            float halfWidth,
                                            float yLift,
                                            UvMode uvMode,
                                            double uStart,
                                            double uEnd,
                                            int packedLight,
                                            int packedOverlay) {
        renderRibbonSegment(consumer, pose, normalMatrix, sprite, from, to,
                fromPerpendicularUnit, toPerpendicularUnit, halfWidth, yLift, uvMode,
                uStart, uEnd, packedLight, packedOverlay, 0.0F);
    }

    private static void renderRibbonSegment(VertexConsumer consumer,
                                            Matrix4f pose,
                                            Matrix3f normalMatrix,
                                            TextureAtlasSprite sprite,
                                            Vec3 from,
                                            Vec3 to,
                                            Vec3 fromPerpendicularUnit,
                                            Vec3 toPerpendicularUnit,
                                            float halfWidth,
                                            float yLift,
                                            UvMode uvMode,
                                            double uStart,
                                            double uEnd,
                                            int packedLight,
                                            int packedOverlay,
                                            float centerOffset) {
        Vec3 fromOffset = fromPerpendicularUnit.scale(centerOffset);
        Vec3 toOffset = toPerpendicularUnit.scale(centerOffset);
        Vec3 fromCenter = new Vec3(from.x + fromOffset.x, from.y + yLift, from.z + fromOffset.z);
        Vec3 toCenter = new Vec3(to.x + toOffset.x, to.y + yLift, to.z + toOffset.z);

        Vec3 fromLeft = fromCenter.add(fromPerpendicularUnit.scale(halfWidth));
        Vec3 fromRight = fromCenter.subtract(fromPerpendicularUnit.scale(halfWidth));
        Vec3 toLeft = toCenter.add(toPerpendicularUnit.scale(halfWidth));
        Vec3 toRight = toCenter.subtract(toPerpendicularUnit.scale(halfWidth));

        Vector3f surfaceNormal = computeNormal(fromCenter, toCenter, fromLeft, fromRight);
        emitWrappedQuad(consumer, pose, normalMatrix, sprite,
                fromLeft, fromRight, toRight, toLeft,
                uStart, uEnd, packedLight, packedOverlay, surfaceNormal, uvMode);
    }

    private static void emitWrappedQuad(VertexConsumer consumer,
                                        Matrix4f pose,
                                        Matrix3f normalMatrix,
                                        TextureAtlasSprite sprite,
                                        Vec3 fromLeft,
                                        Vec3 fromRight,
                                        Vec3 toRight,
                                        Vec3 toLeft,
                                        double uStartPixels,
                                        double uEndPixels,
                                        int packedLight,
                                        int packedOverlay,
                                        Vector3f normal,
                                        UvMode uvMode) {
        float wrapRange = uvWrapRange(uvMode);
        float startWrapped = wrapPixels(uStartPixels, wrapRange);
        float endWrapped = wrapPixels(uEndPixels, wrapRange);

        if (endWrapped >= startWrapped) {
            emitQuad(consumer, pose, normalMatrix, sprite, fromLeft, fromRight, toRight, toLeft,
                    startWrapped, endWrapped, packedLight, packedOverlay, normal, uvMode);
            return;
        }

        double span = (wrapRange - startWrapped) + endWrapped;
        if (span <= 1.0E-6D) {
            emitQuad(consumer, pose, normalMatrix, sprite, fromLeft, fromRight, toRight, toLeft,
                    startWrapped, startWrapped + 0.01F, packedLight, packedOverlay, normal, uvMode);
            return;
        }

        double splitRatio = (wrapRange - startWrapped) / span;
        Vec3 midLeft = lerp(fromLeft, toLeft, splitRatio);
        Vec3 midRight = lerp(fromRight, toRight, splitRatio);

        emitQuad(consumer, pose, normalMatrix, sprite, fromLeft, fromRight, midRight, midLeft,
                startWrapped, wrapRange, packedLight, packedOverlay, normal, uvMode);
        emitQuad(consumer, pose, normalMatrix, sprite, midLeft, midRight, toRight, toLeft,
                0.0F, endWrapped, packedLight, packedOverlay, normal, uvMode);
    }

    private static void emitQuad(VertexConsumer consumer,
                                 Matrix4f pose,
                                 Matrix3f normalMatrix,
                                 TextureAtlasSprite sprite,
                                 Vec3 a,
                                 Vec3 b,
                                 Vec3 c,
                                 Vec3 d,
                                 float uStartPixels,
                                 float uEndPixels,
                                 int packedLight,
                                 int packedOverlay,
                                 Vector3f normal,
                                 UvMode uvMode) {
        if (uvMode == UvMode.ROTATE_LEFT) {
            float v0 = sprite.getV(clamp(16.0F - uStartPixels, 0.0F, 16.0F));
            float v1 = sprite.getV(clamp(16.0F - uEndPixels, 0.0F, 16.0F));
            float uLeft = sprite.getU(16.0F);
            float uRight = sprite.getU(0.0F);
            putVertex(consumer, pose, normalMatrix, a, uLeft, v0, packedLight, packedOverlay, normal);
            putVertex(consumer, pose, normalMatrix, d, uLeft, v1, packedLight, packedOverlay, normal);
            putVertex(consumer, pose, normalMatrix, c, uRight, v1, packedLight, packedOverlay, normal);
            putVertex(consumer, pose, normalMatrix, b, uRight, v0, packedLight, packedOverlay, normal);
            return;
        }

        if (uvMode == UvMode.BAR) {
            float u0 = sprite.getU(clamp(uStartPixels, 0.0F, 8.0F));
            float u1 = sprite.getU(clamp(uEndPixels, 0.0F, 8.0F));
            float vTop = sprite.getV(0.0F);
            float vBottom = sprite.getV(12.0F);
            putVertex(consumer, pose, normalMatrix, a, u0, vTop, packedLight, packedOverlay, normal);
            putVertex(consumer, pose, normalMatrix, d, u1, vTop, packedLight, packedOverlay, normal);
            putVertex(consumer, pose, normalMatrix, c, u1, vBottom, packedLight, packedOverlay, normal);
            putVertex(consumer, pose, normalMatrix, b, u0, vBottom, packedLight, packedOverlay, normal);
            return;
        }

        float u0 = sprite.getU(uStartPixels);
        float u1 = sprite.getU(uEndPixels);
        float vLeft = sprite.getV(0.0F);
        float vRight = sprite.getV(16.0F);
        putVertex(consumer, pose, normalMatrix, a, u0, vLeft, packedLight, packedOverlay, normal);
        putVertex(consumer, pose, normalMatrix, d, u1, vLeft, packedLight, packedOverlay, normal);
        putVertex(consumer, pose, normalMatrix, c, u1, vRight, packedLight, packedOverlay, normal);
        putVertex(consumer, pose, normalMatrix, b, u0, vRight, packedLight, packedOverlay, normal);
    }

    private static void putVertex(VertexConsumer consumer,
                                  Matrix4f pose,
                                  Matrix3f normalMatrix,
                                  Vec3 position,
                                  float u,
                                  float v,
                                  int packedLight,
                                  int packedOverlay,
                                  Vector3f normal) {
        consumer.vertex(pose, (float) position.x, (float) position.y, (float) position.z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(packedOverlay)
                .uv2(packedLight)
                .normal(normalMatrix, normal.x(), normal.y(), normal.z())
                .endVertex();
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(
                a.x + ((b.x - a.x) * t),
                a.y + ((b.y - a.y) * t),
                a.z + ((b.z - a.z) * t)
        );
    }

    private static Vector3f computeNormal(Vec3 fromCenter, Vec3 toCenter, Vec3 fromLeft, Vec3 fromRight) {
        Vec3 along = toCenter.subtract(fromCenter);
        Vec3 width = fromLeft.subtract(fromRight);
        Vec3 cross = along.cross(width);
        if (cross.lengthSqr() < 1.0E-10D) {
            return new Vector3f(0.0F, 1.0F, 0.0F);
        }
        if (cross.y < 0.0D) {
            cross = cross.scale(-1.0D);
        }
        Vec3 normalized = cross.normalize();
        return new Vector3f((float) normalized.x, (float) normalized.y, (float) normalized.z);
    }

    private static float uvWrapRange(UvMode uvMode) {
        return uvMode == UvMode.BAR ? 8.0F : UV_WRAP_PIXELS;
    }

    private static float wrapPixels(double pixels, float wrapRange) {
        double wrapped = pixels % wrapRange;
        if (wrapped < 0.0D) {
            wrapped += wrapRange;
        }
        return (float) wrapped;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
