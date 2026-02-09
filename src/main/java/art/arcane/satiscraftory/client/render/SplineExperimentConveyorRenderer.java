package art.arcane.satiscraftory.client.render;

import art.arcane.satiscraftory.block.SplineExperimentConveyorBlock;
import art.arcane.satiscraftory.block.entity.SplineExperimentConveyorBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class SplineExperimentConveyorRenderer implements BlockEntityRenderer<SplineExperimentConveyorBlockEntity> {
    private enum AlongAxis {
        U,
        V
    }

    private record FaceUv(AlongAxis alongAxis, boolean invertAlong, float crossStartPixels, float crossEndPixels) {
    }

    private static final int CURVE_SEGMENTS = 80;
    private static final float BELT_CENTER_Y_OFFSET = 0.03125F;
    private static final float RAIL_CENTER_Y_OFFSET = 0.03125F;
    private static final double EDGE_OFFSET = 0.5D;

    private static final float BELT_HALF_WIDTH = 0.375F;
    private static final float BELT_HALF_HEIGHT = 0.03125F;
    private static final float RAIL_CENTER_OFFSET = 0.4375F;
    private static final float RAIL_HALF_WIDTH = 0.0625F;
    private static final float RAIL_HALF_HEIGHT = 0.0625F;

    private static final float BELT_UV_PIXELS_PER_BLOCK = 16.0F;
    private static final float BAR_UV_PIXELS_PER_BLOCK = 8.0F;
    private static final float BELT_UV_WRAP_PIXELS = 16.0F;
    private static final float BAR_UV_WRAP_PIXELS = 8.0F;

    private static final FaceUv BELT_TOP_UV = new FaceUv(AlongAxis.V, true, 16.0F, 0.0F);
    private static final FaceUv BELT_BOTTOM_UV = new FaceUv(AlongAxis.V, false, 0.0F, 16.0F);
    private static final FaceUv BELT_SIDE_UV = new FaceUv(AlongAxis.U, false, 4.0F, 6.0F);

    private static final FaceUv BAR_TOP_UV = new FaceUv(AlongAxis.U, false, 0.0F, 2.0F);
    private static final FaceUv BAR_BOTTOM_UV = new FaceUv(AlongAxis.U, false, 0.0F, 2.0F);
    private static final FaceUv BAR_SIDE_UV = new FaceUv(AlongAxis.U, false, 0.0F, 2.0F);

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
        Level level = blockEntity.getLevel();
        if (level == null) {
            return;
        }

        Vec3 start = anchorToEdge(new Vec3(0.5D, BELT_CENTER_Y_OFFSET, 0.5D), startFacing.getOpposite());
        Vec3 end = anchorToEdge(toLocalCenter(blockEntity.getBlockPos(), endPos, BELT_CENTER_Y_OFFSET), endFacing);

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
            renderPrismSegment(cutoutBuffer, pose, normal, beltSprite,
                    from, to, fromPerpendicular, toPerpendicular,
                    0.0F, 0.0F, BELT_HALF_WIDTH, BELT_HALF_HEIGHT,
                    alongStartBelt, alongEndBelt, BELT_UV_WRAP_PIXELS,
                    BELT_TOP_UV, BELT_BOTTOM_UV, BELT_SIDE_UV,
                    level, blockEntity.getBlockPos(), packedLight, packedOverlay);

            double alongStartBar = distances[i] * BAR_UV_PIXELS_PER_BLOCK;
            double alongEndBar = distances[i + 1] * BAR_UV_PIXELS_PER_BLOCK;
            renderPrismSegment(cutoutBuffer, pose, normal, barSprite,
                    from, to, fromPerpendicular, toPerpendicular,
                    RAIL_CENTER_OFFSET, RAIL_CENTER_Y_OFFSET, RAIL_HALF_WIDTH, RAIL_HALF_HEIGHT,
                    alongStartBar, alongEndBar, BAR_UV_WRAP_PIXELS,
                    BAR_TOP_UV, BAR_BOTTOM_UV, BAR_SIDE_UV,
                    level, blockEntity.getBlockPos(), packedLight, packedOverlay);
            renderPrismSegment(cutoutBuffer, pose, normal, barSprite,
                    from, to, fromPerpendicular, toPerpendicular,
                    -RAIL_CENTER_OFFSET, RAIL_CENTER_Y_OFFSET, RAIL_HALF_WIDTH, RAIL_HALF_HEIGHT,
                    alongStartBar, alongEndBar, BAR_UV_WRAP_PIXELS,
                    BAR_TOP_UV, BAR_BOTTOM_UV, BAR_SIDE_UV,
                    level, blockEntity.getBlockPos(), packedLight, packedOverlay);
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

    private static void renderPrismSegment(VertexConsumer consumer,
                                           Matrix4f pose,
                                           Matrix3f normalMatrix,
                                           TextureAtlasSprite sprite,
                                           Vec3 from,
                                           Vec3 to,
                                           Vec3 fromPerpendicularUnit,
                                           Vec3 toPerpendicularUnit,
                                           float centerOffset,
                                           float centerYOffset,
                                           float halfWidth,
                                           float halfHeight,
                                           double alongStartPixels,
                                           double alongEndPixels,
                                           float wrapRangePixels,
                                           FaceUv topUv,
                                           FaceUv bottomUv,
                                           FaceUv sideUv,
                                           Level level,
                                           BlockPos originPos,
                                           int defaultPackedLight,
                                           int packedOverlay) {
        Vec3 fromOffset = fromPerpendicularUnit.scale(centerOffset);
        Vec3 toOffset = toPerpendicularUnit.scale(centerOffset);

        Vec3 fromCenter = new Vec3(from.x + fromOffset.x, from.y + centerYOffset, from.z + fromOffset.z);
        Vec3 toCenter = new Vec3(to.x + toOffset.x, to.y + centerYOffset, to.z + toOffset.z);

        Vec3 fromLeft = fromCenter.add(fromPerpendicularUnit.scale(halfWidth));
        Vec3 fromRight = fromCenter.subtract(fromPerpendicularUnit.scale(halfWidth));
        Vec3 toLeft = toCenter.add(toPerpendicularUnit.scale(halfWidth));
        Vec3 toRight = toCenter.subtract(toPerpendicularUnit.scale(halfWidth));

        Vec3 fromTopLeft = fromLeft.add(0.0D, halfHeight, 0.0D);
        Vec3 fromTopRight = fromRight.add(0.0D, halfHeight, 0.0D);
        Vec3 toTopLeft = toLeft.add(0.0D, halfHeight, 0.0D);
        Vec3 toTopRight = toRight.add(0.0D, halfHeight, 0.0D);

        Vec3 fromBottomLeft = fromLeft.subtract(0.0D, halfHeight, 0.0D);
        Vec3 fromBottomRight = fromRight.subtract(0.0D, halfHeight, 0.0D);
        Vec3 toBottomLeft = toLeft.subtract(0.0D, halfHeight, 0.0D);
        Vec3 toBottomRight = toRight.subtract(0.0D, halfHeight, 0.0D);

        emitWrappedQuad(consumer, pose, normalMatrix, sprite,
                fromTopLeft, fromTopRight, toTopRight, toTopLeft,
                alongStartPixels, alongEndPixels, wrapRangePixels, topUv,
                level, originPos, defaultPackedLight, packedOverlay, new Vec3(0.0D, 1.0D, 0.0D));

        emitWrappedQuad(consumer, pose, normalMatrix, sprite,
                fromBottomRight, fromBottomLeft, toBottomLeft, toBottomRight,
                alongStartPixels, alongEndPixels, wrapRangePixels, bottomUv,
                level, originPos, defaultPackedLight, packedOverlay, new Vec3(0.0D, -1.0D, 0.0D));

        Vec3 avgPerpendicular = fromPerpendicularUnit.add(toPerpendicularUnit);
        if (avgPerpendicular.lengthSqr() < 1.0E-6D) {
            avgPerpendicular = fromPerpendicularUnit;
        }
        if (avgPerpendicular.lengthSqr() < 1.0E-6D) {
            avgPerpendicular = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            avgPerpendicular = avgPerpendicular.normalize();
        }

        emitWrappedQuad(consumer, pose, normalMatrix, sprite,
                fromTopLeft, fromBottomLeft, toBottomLeft, toTopLeft,
                alongStartPixels, alongEndPixels, wrapRangePixels, sideUv,
                level, originPos, defaultPackedLight, packedOverlay, avgPerpendicular);

        emitWrappedQuad(consumer, pose, normalMatrix, sprite,
                fromBottomRight, fromTopRight, toTopRight, toBottomRight,
                alongStartPixels, alongEndPixels, wrapRangePixels, sideUv,
                level, originPos, defaultPackedLight, packedOverlay, avgPerpendicular.scale(-1.0D));
    }

    private static void emitWrappedQuad(VertexConsumer consumer,
                                        Matrix4f pose,
                                        Matrix3f normalMatrix,
                                        TextureAtlasSprite sprite,
                                        Vec3 a,
                                        Vec3 b,
                                        Vec3 c,
                                        Vec3 d,
                                        double alongStartPixels,
                                        double alongEndPixels,
                                        float wrapRangePixels,
                                        FaceUv uv,
                                        Level level,
                                        BlockPos originPos,
                                        int defaultPackedLight,
                                        int packedOverlay,
                                        Vec3 preferredNormal) {
        float startWrapped = wrapPixels(alongStartPixels, wrapRangePixels);
        float endWrapped = wrapPixels(alongEndPixels, wrapRangePixels);

        if (endWrapped >= startWrapped) {
            emitQuad(consumer, pose, normalMatrix, sprite, a, b, c, d,
                    startWrapped, endWrapped, wrapRangePixels, uv,
                    level, originPos, defaultPackedLight, packedOverlay, preferredNormal);
            return;
        }

        double span = (wrapRangePixels - startWrapped) + endWrapped;
        if (span <= 1.0E-6D) {
            emitQuad(consumer, pose, normalMatrix, sprite, a, b, c, d,
                    startWrapped, startWrapped + 0.01F, wrapRangePixels, uv,
                    level, originPos, defaultPackedLight, packedOverlay, preferredNormal);
            return;
        }

        double splitRatio = (wrapRangePixels - startWrapped) / span;
        Vec3 midA = lerp(a, d, splitRatio);
        Vec3 midB = lerp(b, c, splitRatio);

        emitQuad(consumer, pose, normalMatrix, sprite, a, b, midB, midA,
                startWrapped, wrapRangePixels, wrapRangePixels, uv,
                level, originPos, defaultPackedLight, packedOverlay, preferredNormal);
        emitQuad(consumer, pose, normalMatrix, sprite, midA, midB, c, d,
                0.0F, endWrapped, wrapRangePixels, uv,
                level, originPos, defaultPackedLight, packedOverlay, preferredNormal);
    }

    private static void emitQuad(VertexConsumer consumer,
                                 Matrix4f pose,
                                 Matrix3f normalMatrix,
                                 TextureAtlasSprite sprite,
                                 Vec3 a,
                                 Vec3 b,
                                 Vec3 c,
                                 Vec3 d,
                                 float alongStartPixels,
                                 float alongEndPixels,
                                 float wrapRangePixels,
                                 FaceUv uv,
                                 Level level,
                                 BlockPos originPos,
                                 int defaultPackedLight,
                                 int packedOverlay,
                                 Vec3 preferredNormal) {
        float along0 = uv.invertAlong ? wrapRangePixels - alongStartPixels : alongStartPixels;
        float along1 = uv.invertAlong ? wrapRangePixels - alongEndPixels : alongEndPixels;

        along0 = clamp(along0, 0.0F, 16.0F);
        along1 = clamp(along1, 0.0F, 16.0F);
        float cross0 = clamp(uv.crossStartPixels, 0.0F, 16.0F);
        float cross1 = clamp(uv.crossEndPixels, 0.0F, 16.0F);

        float uA;
        float uB;
        float uC;
        float uD;
        float vA;
        float vB;
        float vC;
        float vD;

        if (uv.alongAxis == AlongAxis.U) {
            float uStart = sprite.getU(along0);
            float uEnd = sprite.getU(along1);
            float vStart = sprite.getV(cross0);
            float vEnd = sprite.getV(cross1);

            uA = uStart;
            uB = uStart;
            uC = uEnd;
            uD = uEnd;

            vA = vStart;
            vB = vEnd;
            vC = vEnd;
            vD = vStart;
        } else {
            float uStart = sprite.getU(cross0);
            float uEnd = sprite.getU(cross1);
            float vStart = sprite.getV(along0);
            float vEnd = sprite.getV(along1);

            uA = uStart;
            uB = uEnd;
            uC = uEnd;
            uD = uStart;

            vA = vStart;
            vB = vStart;
            vC = vEnd;
            vD = vEnd;
        }

        Vector3f faceNormal = computeNormal(a, b, c);
        if (preferredNormal != null) {
            float dot = (faceNormal.x() * (float) preferredNormal.x)
                    + (faceNormal.y() * (float) preferredNormal.y)
                    + (faceNormal.z() * (float) preferredNormal.z);
            if (dot < 0.0F) {
                faceNormal = new Vector3f(-faceNormal.x(), -faceNormal.y(), -faceNormal.z());
            }
        }
        int lightA = samplePackedLight(level, originPos, a, defaultPackedLight);
        int lightB = samplePackedLight(level, originPos, b, defaultPackedLight);
        int lightC = samplePackedLight(level, originPos, c, defaultPackedLight);
        int lightD = samplePackedLight(level, originPos, d, defaultPackedLight);

        putVertex(consumer, pose, normalMatrix, a, uA, vA, lightA, packedOverlay, faceNormal);
        putVertex(consumer, pose, normalMatrix, b, uB, vB, lightB, packedOverlay, faceNormal);
        putVertex(consumer, pose, normalMatrix, c, uC, vC, lightC, packedOverlay, faceNormal);
        putVertex(consumer, pose, normalMatrix, d, uD, vD, lightD, packedOverlay, faceNormal);
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

    private static Vector3f computeNormal(Vec3 a, Vec3 b, Vec3 c) {
        Vec3 ab = b.subtract(a);
        Vec3 ac = c.subtract(a);
        Vec3 cross = ab.cross(ac);
        if (cross.lengthSqr() < 1.0E-10D) {
            return new Vector3f(0.0F, 1.0F, 0.0F);
        }
        Vec3 normalized = cross.normalize();
        return new Vector3f((float) normalized.x, (float) normalized.y, (float) normalized.z);
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

    private static int samplePackedLight(Level level, BlockPos originPos, Vec3 localPos, int fallbackLight) {
        BlockPos worldSamplePos = BlockPos.containing(
                originPos.getX() + localPos.x,
                originPos.getY() + localPos.y,
                originPos.getZ() + localPos.z
        );
        int sampled = LevelRenderer.getLightColor(level, worldSamplePos);
        if (sampled == 0) {
            return fallbackLight;
        }
        return sampled;
    }
}
