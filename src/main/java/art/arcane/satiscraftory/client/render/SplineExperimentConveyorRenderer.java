package art.arcane.satiscraftory.client.render;

import art.arcane.satiscraftory.block.SplineExperimentConveyorBlock;
import art.arcane.satiscraftory.block.entity.SplineExperimentConveyorBlockEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
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
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class SplineExperimentConveyorRenderer implements BlockEntityRenderer<SplineExperimentConveyorBlockEntity> {
    private enum AlongAxis {
        U,
        V
    }

    private enum StripAxis {
        NONE,
        S,
        T
    }

    private record UvPixel(float u, float v) {
    }

    private record FaceParams(float s, float t) {
    }

    private record LocalVertex(float x, float y, float z, float s, float t) {
    }

    private record ModelFace(
            Direction direction,
            TextureAtlasSprite sprite,
            float x1,
            float x2,
            float y1,
            float y2,
            float z1,
            float z2,
            float u1,
            float v1,
            float u2,
            float v2,
            int rotation,
            boolean repeatEnabled,
            AlongAxis repeatAxis,
            float repeatStart,
            float repeatMin,
            float repeatPixelsPerBlock,
            float repeatWrapRange
    ) {
    }

    private record SplineModelTemplate(List<ModelFace> faces) {
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CURVE_SEGMENTS = 96;
    private static final float EDGE_OFFSET = 0.5F;
    private static final float EPSILON = 1.0E-6F;
    private static final float WRAP_SEAM_RAW_EPSILON_SCALE = 0.0001F;
    private static final int MAX_WRAP_SPLITS_PER_STRIP = 16;

    // Input model for the generic spline renderer.
    private static final ResourceLocation SOURCE_MODEL_JSON = ResourceLocation.fromNamespaceAndPath(
            "satiscraftory", "models/block/conveyor_straight.json"
    );

    @Nullable
    private static SplineModelTemplate cachedTemplate;
    @Nullable
    private static ResourceManager cachedResourceManager;
    private static boolean templateLoadFailed;

    public SplineExperimentConveyorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SplineExperimentConveyorBlockEntity blockEntity,
                       float partialTick,
                       PoseStack poseStack,
                       MultiBufferSource bufferSource,
                       int packedLight,
                       int packedOverlay) {
        SplineModelTemplate template = getOrLoadTemplate();
        if (template == null || template.faces().isEmpty()) {
            return;
        }

        BlockPos endPos = blockEntity.getEndPos();
        if (endPos == null) {
            return;
        }

        Level level = blockEntity.getLevel();
        if (level == null) {
            return;
        }

        Direction startFacing = getFacing(blockEntity.getBlockState());
        Direction endFacing = blockEntity.getEndFacing();

        Vec3 start = anchorToEdge(new Vec3(0.5D, 0.0D, 0.5D), startFacing.getOpposite());
        Vec3 end = anchorToEdge(toLocalCenter(blockEntity.getBlockPos(), endPos, 0.0D), endFacing);

        Vec3 startForward = directionVector(startFacing);
        Vec3 endForward = directionVector(endFacing);

        Vec3 flatDelta = new Vec3(end.x - start.x, 0.0D, end.z - start.z);
        double horizontalDistance = Math.sqrt(flatDelta.lengthSqr());
        double tangentLength = Math.max(0.85D, Math.min(4.0D, horizontalDistance * 0.45D));

        Vec3 c1 = start.add(startForward.scale(tangentLength));
        Vec3 c2 = end.subtract(endForward.scale(tangentLength));

        Vec3[] points = new Vec3[CURVE_SEGMENTS + 1];
        Vec3[] perpendiculars = new Vec3[CURVE_SEGMENTS + 1];
        Vec3[] tangents = new Vec3[CURVE_SEGMENTS + 1];
        double[] distances = new double[CURVE_SEGMENTS + 1];

        for (int i = 0; i <= CURVE_SEGMENTS; i++) {
            double t = i / (double) CURVE_SEGMENTS;
            points[i] = sampleBezier(start, c1, c2, end, t);
            if (i > 0) {
                distances[i] = distances[i - 1] + points[i].distanceTo(points[i - 1]);
            }
        }

        for (int i = 0; i <= CURVE_SEGMENTS; i++) {
            tangents[i] = tangentAt(points, i, startForward);
            perpendiculars[i] = perpendicularFromTangent(tangents[i], startForward);
        }

        VertexConsumer cutoutBuffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();

        for (ModelFace face : template.faces()) {
            renderSplineFace(
                    face,
                    cutoutBuffer,
                    pose,
                    normal,
                    level,
                    blockEntity.getBlockPos(),
                    packedLight,
                    packedOverlay,
                    points,
                    perpendiculars,
                    tangents,
                    distances
            );
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

    private static void renderSplineFace(ModelFace face,
                                         VertexConsumer consumer,
                                         Matrix4f pose,
                                         Matrix3f normalMatrix,
                                         Level level,
                                         BlockPos originPos,
                                         int defaultPackedLight,
                                         int packedOverlay,
                                         Vec3[] points,
                                         Vec3[] perpendiculars,
                                         Vec3[] tangents,
                                         double[] distances) {
        StripAxis stripAxis = stripAxis(face.direction());
        int strips = stripAxis == StripAxis.NONE
                ? 1
                : Math.max(1, (int) Math.ceil((face.z2() - face.z1()) * CURVE_SEGMENTS));

        for (int strip = 0; strip < strips; strip++) {
            float stripStart = strip / (float) strips;
            float stripEnd = (strip + 1) / (float) strips;
            renderStripRange(
                    face,
                    stripAxis,
                    stripStart,
                    stripEnd,
                    consumer,
                    pose,
                    normalMatrix,
                    level,
                    originPos,
                    defaultPackedLight,
                    packedOverlay,
                    points,
                    perpendiculars,
                    tangents,
                    distances
            );
        }
    }

    private static void renderStripRange(ModelFace face,
                                         StripAxis stripAxis,
                                         float stripStart,
                                         float stripEnd,
                                         VertexConsumer consumer,
                                         Matrix4f pose,
                                         Matrix3f normalMatrix,
                                         Level level,
                                         BlockPos originPos,
                                         int defaultPackedLight,
                                         int packedOverlay,
                                         Vec3[] points,
                                         Vec3[] perpendiculars,
                                         Vec3[] tangents,
                                         double[] distances) {
        if (!face.repeatEnabled() || face.repeatWrapRange() < EPSILON) {
            renderStripSegment(
                    face, stripAxis, stripStart, stripEnd, consumer, pose, normalMatrix,
                    level, originPos, defaultPackedLight, packedOverlay,
                    points, perpendiculars, tangents, distances
            );
            return;
        }

        float currentStart = stripStart;
        for (int i = 0; i < MAX_WRAP_SPLITS_PER_STRIP && (stripEnd - currentStart) > EPSILON; i++) {
            float currentEnd = stripEnd;
            float nextStart = currentEnd;
            float rawStart = repeatRawAt(face, distances, currentStart);
            float rawEnd = repeatRawAt(face, distances, currentEnd);
            float delta = rawEnd - rawStart;

            if (Math.abs(delta) > EPSILON) {
                int bucketStart = repeatBucket(rawStart, face.repeatMin(), face.repeatWrapRange(), delta > 0.0F);
                int bucketEnd = repeatBucket(rawEnd, face.repeatMin(), face.repeatWrapRange(), delta > 0.0F);

                if (bucketStart != bucketEnd) {
                    float boundary = delta > 0.0F
                            ? face.repeatMin() + ((bucketStart + 1) * face.repeatWrapRange())
                            : face.repeatMin() + (bucketStart * face.repeatWrapRange());

                    // Avoid rendering exactly on the wrap boundary where atlas UV modulo
                    // can produce tiny flipped seam strips.
                    float rawSeamEpsilon = Math.max(1.0E-6F, face.repeatWrapRange() * WRAP_SEAM_RAW_EPSILON_SCALE);
                    float beforeBoundaryRaw = boundary + (delta > 0.0F ? -rawSeamEpsilon : rawSeamEpsilon);
                    float afterBoundaryRaw = boundary + (delta > 0.0F ? rawSeamEpsilon : -rawSeamEpsilon);

                    float splitBefore = findSplitTForRaw(face, distances, currentStart, currentEnd, beforeBoundaryRaw, delta > 0.0F);
                    float splitAfter = findSplitTForRaw(face, distances, currentStart, currentEnd, afterBoundaryRaw, delta > 0.0F);

                    if ((splitBefore - currentStart) > EPSILON
                            && (currentEnd - splitAfter) > EPSILON
                            && (splitAfter - splitBefore) > EPSILON) {
                        currentEnd = splitBefore;
                        nextStart = splitAfter;
                    } else {
                        float split = findSplitTForRaw(face, distances, currentStart, currentEnd, boundary, delta > 0.0F);
                        if ((split - currentStart) > EPSILON && (currentEnd - split) > EPSILON) {
                            currentEnd = split;
                            nextStart = split;
                        }
                    }
                }
            }

            if ((currentEnd - currentStart) > EPSILON) {
                renderStripSegment(
                        face, stripAxis, currentStart, currentEnd, consumer, pose, normalMatrix,
                        level, originPos, defaultPackedLight, packedOverlay,
                        points, perpendiculars, tangents, distances
                );
            }

            if ((stripEnd - nextStart) <= EPSILON) {
                break;
            }
            currentStart = nextStart;
        }
    }

    private static void renderStripSegment(ModelFace face,
                                           StripAxis stripAxis,
                                           float stripStart,
                                           float stripEnd,
                                           VertexConsumer consumer,
                                           Matrix4f pose,
                                           Matrix3f normalMatrix,
                                           Level level,
                                           BlockPos originPos,
                                           int defaultPackedLight,
                                           int packedOverlay,
                                           Vec3[] points,
                                           Vec3[] perpendiculars,
                                           Vec3[] tangents,
                                           double[] distances) {
        float zA = lerp(face.z1(), face.z2(), stripStart);
        float zB = lerp(face.z1(), face.z2(), stripEnd);

        LocalVertex a;
        LocalVertex b;
        LocalVertex c;
        LocalVertex d;

        if (stripAxis == StripAxis.S) {
            float sA = sFromZ(face.direction(), zA, face.z1(), face.z2());
            float sB = sFromZ(face.direction(), zB, face.z1(), face.z2());
            a = localVertex(face, sA, 0.0F);
            b = localVertex(face, sB, 0.0F);
            c = localVertex(face, sB, 1.0F);
            d = localVertex(face, sA, 1.0F);
        } else if (stripAxis == StripAxis.T) {
            float tA = tFromZ(face.direction(), zA, face.z1(), face.z2());
            float tB = tFromZ(face.direction(), zB, face.z1(), face.z2());
            a = localVertex(face, 0.0F, tA);
            b = localVertex(face, 1.0F, tA);
            c = localVertex(face, 1.0F, tB);
            d = localVertex(face, 0.0F, tB);
        } else {
            a = localVertex(face, 0.0F, 0.0F);
            b = localVertex(face, 1.0F, 0.0F);
            c = localVertex(face, 1.0F, 1.0F);
            d = localVertex(face, 0.0F, 1.0F);
        }

        Vec3 worldA = mapLocalToSpline(a, points, perpendiculars);
        Vec3 worldB = mapLocalToSpline(b, points, perpendiculars);
        Vec3 worldC = mapLocalToSpline(c, points, perpendiculars);
        Vec3 worldD = mapLocalToSpline(d, points, perpendiculars);

        double distA = sampleDistance(distances, a.z());
        double distB = sampleDistance(distances, b.z());
        double distC = sampleDistance(distances, c.z());
        double distD = sampleDistance(distances, d.z());

        UvPixel uvA = uvPixel(face, a, distA);
        UvPixel uvB = uvPixel(face, b, distB);
        UvPixel uvC = uvPixel(face, c, distC);
        UvPixel uvD = uvPixel(face, d, distD);

        float normalT = (zA + zB) * 0.5F;
        Vec3 preferredNormal = expectedNormal(face.direction(), normalT, perpendiculars, tangents);
        emitQuad(
                consumer,
                pose,
                normalMatrix,
                face.sprite(),
                worldA,
                worldB,
                worldC,
                worldD,
                uvA,
                uvB,
                uvC,
                uvD,
                preferredNormal,
                level,
                originPos,
                defaultPackedLight,
                packedOverlay
        );
    }

    private static Vec3 mapLocalToSpline(LocalVertex local, Vec3[] points, Vec3[] perpendiculars) {
        Vec3 center = sampleVec(points, local.z());
        Vec3 perpendicular = samplePerpendicular(perpendiculars, local.z());
        double xOffset = local.x() - 0.5D;
        return center.add(perpendicular.scale(xOffset)).add(0.0D, local.y(), 0.0D);
    }

    private static UvPixel uvPixel(ModelFace face, LocalVertex local, double distanceAlongSpline) {
        FaceParams rotated = rotateParams(local.s(), local.t(), face.rotation());
        float u = lerp(face.u1(), face.u2(), rotated.s());
        float v = lerp(face.v1(), face.v2(), rotated.t());

        if (face.repeatEnabled()) {
            float rawRepeated = face.repeatStart() + ((float) distanceAlongSpline * face.repeatPixelsPerBlock());
            float repeated = wrapToRange(rawRepeated, face.repeatMin(), face.repeatWrapRange());
            if (face.repeatAxis() == AlongAxis.U) {
                u = repeated;
            } else {
                v = repeated;
            }
        }

        return new UvPixel(u, v);
    }

    private static float repeatRawAt(ModelFace face, double[] distances, float t) {
        return face.repeatStart() + ((float) sampleDistance(distances, t) * face.repeatPixelsPerBlock());
    }

    private static int repeatBucket(float rawValue, float repeatMin, float repeatRange, boolean increasing) {
        float normalized = (rawValue - repeatMin) / repeatRange;
        if (increasing) {
            normalized = (float) Math.nextAfter(normalized, Double.POSITIVE_INFINITY);
        } else {
            normalized = (float) Math.nextAfter(normalized, Double.NEGATIVE_INFINITY);
        }
        return (int) Math.floor(normalized);
    }

    private static float findSplitTForRaw(ModelFace face,
                                          double[] distances,
                                          float tMin,
                                          float tMax,
                                          float boundaryRawValue,
                                          boolean increasing) {
        float low = tMin;
        float high = tMax;
        for (int i = 0; i < 24; i++) {
            float mid = (low + high) * 0.5F;
            float raw = repeatRawAt(face, distances, mid);
            if ((raw < boundaryRawValue) == increasing) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) * 0.5F;
    }

    private static FaceParams rotateParams(float s, float t, int rotation) {
        return switch ((rotation % 360 + 360) % 360) {
            case 90 -> new FaceParams(t, 1.0F - s);
            case 180 -> new FaceParams(1.0F - s, 1.0F - t);
            case 270 -> new FaceParams(1.0F - t, s);
            default -> new FaceParams(s, t);
        };
    }

    private static void emitQuad(VertexConsumer consumer,
                                 Matrix4f pose,
                                 Matrix3f normalMatrix,
                                 TextureAtlasSprite sprite,
                                 Vec3 a,
                                 Vec3 b,
                                 Vec3 c,
                                 Vec3 d,
                                 UvPixel uvA,
                                 UvPixel uvB,
                                 UvPixel uvC,
                                 UvPixel uvD,
                                 Vec3 preferredNormal,
                                 Level level,
                                 BlockPos originPos,
                                 int defaultPackedLight,
                                 int packedOverlay) {
        Vector3f normal = computeNormal(a, b, c);
        if (preferredNormal.lengthSqr() > 1.0E-8D) {
            float dot = (float) ((normal.x() * preferredNormal.x)
                    + (normal.y() * preferredNormal.y)
                    + (normal.z() * preferredNormal.z));
            if (dot < 0.0F) {
                normal = new Vector3f(-normal.x(), -normal.y(), -normal.z());
            }
        }

        int lightA = samplePackedLight(level, originPos, a, defaultPackedLight);
        int lightB = samplePackedLight(level, originPos, b, defaultPackedLight);
        int lightC = samplePackedLight(level, originPos, c, defaultPackedLight);
        int lightD = samplePackedLight(level, originPos, d, defaultPackedLight);

        putVertex(consumer, pose, normalMatrix, a, sprite.getU(uvA.u()), sprite.getV(uvA.v()), lightA, packedOverlay, normal);
        putVertex(consumer, pose, normalMatrix, b, sprite.getU(uvB.u()), sprite.getV(uvB.v()), lightB, packedOverlay, normal);
        putVertex(consumer, pose, normalMatrix, c, sprite.getU(uvC.u()), sprite.getV(uvC.v()), lightC, packedOverlay, normal);
        putVertex(consumer, pose, normalMatrix, d, sprite.getU(uvD.u()), sprite.getV(uvD.v()), lightD, packedOverlay, normal);
    }

    private static LocalVertex localVertex(ModelFace face, float s, float t) {
        Direction direction = face.direction();

        float x = switch (direction) {
            case NORTH -> lerp(face.x1(), face.x2(), s);
            case SOUTH -> lerp(face.x2(), face.x1(), s);
            case WEST -> face.x1();
            case EAST -> face.x2();
            case UP, DOWN -> lerp(face.x1(), face.x2(), s);
            default -> face.x1();
        };

        float y = switch (direction) {
            case NORTH, SOUTH, EAST, WEST -> lerp(face.y2(), face.y1(), t);
            case UP -> face.y2();
            case DOWN -> face.y1();
            default -> face.y1();
        };

        float z = switch (direction) {
            case NORTH -> face.z1();
            case SOUTH -> face.z2();
            case WEST -> lerp(face.z2(), face.z1(), s);
            case EAST -> lerp(face.z1(), face.z2(), s);
            case UP -> lerp(face.z1(), face.z2(), t);
            case DOWN -> lerp(face.z2(), face.z1(), t);
            default -> face.z1();
        };

        return new LocalVertex(x, y, z, clamp01(s), clamp01(t));
    }

    private static StripAxis stripAxis(Direction direction) {
        return switch (direction) {
            case EAST, WEST -> StripAxis.S;
            case UP, DOWN -> StripAxis.T;
            default -> StripAxis.NONE;
        };
    }

    private static float sFromZ(Direction direction, float z, float z1, float z2) {
        return switch (direction) {
            case EAST -> clamp01(invLerp(z1, z2, z));
            case WEST -> clamp01(invLerp(z2, z1, z));
            default -> 0.0F;
        };
    }

    private static float tFromZ(Direction direction, float z, float z1, float z2) {
        return switch (direction) {
            case UP -> clamp01(invLerp(z1, z2, z));
            case DOWN -> clamp01(invLerp(z2, z1, z));
            default -> 0.0F;
        };
    }

    private static Vec3 expectedNormal(Direction faceDirection,
                                       float t,
                                       Vec3[] perpendiculars,
                                       Vec3[] tangents) {
        Vec3 perpendicular = samplePerpendicular(perpendiculars, t);
        Vec3 tangent = sampleVec(tangents, t);
        if (tangent.lengthSqr() < 1.0E-8D) {
            tangent = new Vec3(0.0D, 0.0D, 1.0D);
        } else {
            tangent = tangent.normalize();
        }

        Vec3 local = switch (faceDirection) {
            case EAST -> new Vec3(1.0D, 0.0D, 0.0D);
            case WEST -> new Vec3(-1.0D, 0.0D, 0.0D);
            case UP -> new Vec3(0.0D, 1.0D, 0.0D);
            case DOWN -> new Vec3(0.0D, -1.0D, 0.0D);
            case SOUTH -> new Vec3(0.0D, 0.0D, 1.0D);
            case NORTH -> new Vec3(0.0D, 0.0D, -1.0D);
            default -> new Vec3(0.0D, 1.0D, 0.0D);
        };

        Vec3 mapped = perpendicular.scale(local.x)
                .add(0.0D, local.y, 0.0D)
                .add(tangent.scale(local.z));

        if (mapped.lengthSqr() < 1.0E-8D) {
            return new Vec3(0.0D, 1.0D, 0.0D);
        }
        return mapped.normalize();
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

    private static Vec3 tangentAt(Vec3[] points, int index, Vec3 fallbackForward) {
        Vec3 tangent;
        if (index == 0) {
            tangent = points[1].subtract(points[0]);
        } else if (index == points.length - 1) {
            tangent = points[index].subtract(points[index - 1]);
        } else {
            tangent = points[index + 1].subtract(points[index - 1]);
        }

        if (tangent.lengthSqr() < 1.0E-8D) {
            tangent = fallbackForward;
        }

        if (tangent.lengthSqr() < 1.0E-8D) {
            return new Vec3(0.0D, 0.0D, 1.0D);
        }
        return tangent.normalize();
    }

    private static Vec3 perpendicularFromTangent(Vec3 tangent, Vec3 fallbackForward) {
        Vec3 horizontal = new Vec3(tangent.x, 0.0D, tangent.z);
        if (horizontal.lengthSqr() < 1.0E-8D) {
            horizontal = new Vec3(fallbackForward.x, 0.0D, fallbackForward.z);
        }
        if (horizontal.lengthSqr() < 1.0E-8D) {
            horizontal = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            horizontal = horizontal.normalize();
        }

        return new Vec3(-horizontal.z, 0.0D, horizontal.x);
    }

    private static Vec3 sampleVec(Vec3[] values, double t) {
        double clamped = clamp(t, 0.0D, 1.0D);
        double scaled = clamped * CURVE_SEGMENTS;
        int index = Math.min((int) Math.floor(scaled), CURVE_SEGMENTS - 1);
        double frac = scaled - index;
        return lerp(values[index], values[index + 1], frac);
    }

    private static Vec3 samplePerpendicular(Vec3[] values, double t) {
        Vec3 sampled = sampleVec(values, t);
        Vec3 horizontal = new Vec3(sampled.x, 0.0D, sampled.z);
        if (horizontal.lengthSqr() < 1.0E-8D) {
            return new Vec3(1.0D, 0.0D, 0.0D);
        }
        return horizontal.normalize();
    }

    private static double sampleDistance(double[] values, double t) {
        double clamped = clamp(t, 0.0D, 1.0D);
        double scaled = clamped * CURVE_SEGMENTS;
        int index = Math.min((int) Math.floor(scaled), CURVE_SEGMENTS - 1);
        double frac = scaled - index;
        return values[index] + ((values[index + 1] - values[index]) * frac);
    }

    private static float wrapToRange(float value, float min, float range) {
        if (range < EPSILON) {
            return value;
        }
        float delta = value - min;
        float wrappedDelta = (float) (delta - Math.floor(delta / range) * range);
        return min + wrappedDelta;
    }

    private static float invLerp(float a, float b, float value) {
        float delta = b - a;
        if (Math.abs(delta) < EPSILON) {
            return 0.0F;
        }
        return (value - a) / delta;
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float lerp(float a, float b, float t) {
        return a + ((b - a) * t);
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(
                a.x + ((b.x - a.x) * t),
                a.y + ((b.y - a.y) * t),
                a.z + ((b.z - a.z) * t)
        );
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

    private static Direction getFacing(BlockState state) {
        if (state.hasProperty(SplineExperimentConveyorBlock.FACING)) {
            return state.getValue(SplineExperimentConveyorBlock.FACING);
        }
        return Direction.NORTH;
    }

    @Nullable
    private static SplineModelTemplate getOrLoadTemplate() {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceManager resourceManager = minecraft.getResourceManager();

        if (resourceManager != cachedResourceManager) {
            cachedResourceManager = resourceManager;
            cachedTemplate = null;
            templateLoadFailed = false;
        }

        if (cachedTemplate != null) {
            return cachedTemplate;
        }

        if (templateLoadFailed) {
            return null;
        }

        cachedTemplate = loadTemplate(minecraft, resourceManager);
        if (cachedTemplate == null) {
            templateLoadFailed = true;
        }
        return cachedTemplate;
    }

    @Nullable
    private static SplineModelTemplate loadTemplate(Minecraft minecraft, ResourceManager resourceManager) {
        Optional<Resource> modelResource = resourceManager.getResource(SOURCE_MODEL_JSON);
        if (modelResource.isEmpty()) {
            LOGGER.warn("Spline source model not found: {}", SOURCE_MODEL_JSON);
            return null;
        }

        TextureAtlas atlas = minecraft.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);

        try (Reader reader = modelResource.get().openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            Map<String, String> textures = new HashMap<>();
            JsonObject texturesObject = root.has("textures") && root.get("textures").isJsonObject()
                    ? root.getAsJsonObject("textures")
                    : null;
            if (texturesObject != null) {
                for (Map.Entry<String, JsonElement> entry : texturesObject.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        textures.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }

            JsonArray elements = root.has("elements") && root.get("elements").isJsonArray()
                    ? root.getAsJsonArray("elements")
                    : null;
            if (elements == null || elements.isEmpty()) {
                LOGGER.warn("Spline source model has no elements: {}", SOURCE_MODEL_JSON);
                return null;
            }

            List<ModelFace> faces = new ArrayList<>();

            for (JsonElement elementValue : elements) {
                if (!elementValue.isJsonObject()) {
                    continue;
                }

                JsonObject element = elementValue.getAsJsonObject();
                if (!element.has("from") || !element.has("to") || !element.has("faces")) {
                    continue;
                }

                float[] from = readVec3(element.getAsJsonArray("from"));
                float[] to = readVec3(element.getAsJsonArray("to"));
                if (from == null || to == null) {
                    continue;
                }

                float x1 = from[0] / 16.0F;
                float y1 = from[1] / 16.0F;
                float z1 = from[2] / 16.0F;
                float x2 = to[0] / 16.0F;
                float y2 = to[1] / 16.0F;
                float z2 = to[2] / 16.0F;

                JsonObject facesObject = element.getAsJsonObject("faces");
                for (Map.Entry<String, JsonElement> faceEntry : facesObject.entrySet()) {
                    Direction direction = Direction.byName(faceEntry.getKey());
                    if (direction == null || !faceEntry.getValue().isJsonObject()) {
                        continue;
                    }

                    JsonObject faceJson = faceEntry.getValue().getAsJsonObject();
                    if (!faceJson.has("texture") || !faceJson.get("texture").isJsonPrimitive()) {
                        continue;
                    }

                    String textureRef = faceJson.get("texture").getAsString();
                    ResourceLocation textureLocation = resolveTexture(textures, textureRef, SOURCE_MODEL_JSON.getNamespace());
                    if (textureLocation == null) {
                        continue;
                    }

                    float[] uv = readUv(faceJson.getAsJsonArray("uv"));
                    if (uv == null) {
                        continue;
                    }

                    int rotation = faceJson.has("rotation") && faceJson.get("rotation").isJsonPrimitive()
                            ? faceJson.get("rotation").getAsInt()
                            : 0;

                    TextureAtlasSprite sprite = atlas.getSprite(textureLocation);
                    faces.add(buildFace(
                            direction,
                            sprite,
                            x1,
                            x2,
                            y1,
                            y2,
                            z1,
                            z2,
                            uv[0],
                            uv[1],
                            uv[2],
                            uv[3],
                            rotation
                    ));
                }
            }

            return new SplineModelTemplate(faces);
        } catch (Exception e) {
            LOGGER.error("Failed to load spline source model {}", SOURCE_MODEL_JSON, e);
            return null;
        }
    }

    private static ModelFace buildFace(Direction direction,
                                       TextureAtlasSprite sprite,
                                       float x1,
                                       float x2,
                                       float y1,
                                       float y2,
                                       float z1,
                                       float z2,
                                       float u1,
                                       float v1,
                                       float u2,
                                       float v2,
                                       int rotation) {
        boolean repeatEnabled = false;
        AlongAxis repeatAxis = AlongAxis.U;
        float repeatStart = 0.0F;
        float repeatMin = 0.0F;
        float repeatPixelsPerBlock = 0.0F;
        float repeatWrapRange = 0.0F;

        float zSpan = z2 - z1;
        if (zSpan > EPSILON && direction != Direction.NORTH && direction != Direction.SOUTH) {
            float centerX = (x1 + x2) * 0.5F;
            float centerY = (y1 + y2) * 0.5F;

            UvPixel uvStart = uvFromLocal(direction, x1, x2, y1, y2, z1, z2, u1, v1, u2, v2, rotation, centerX, centerY, z1);
            UvPixel uvEnd = uvFromLocal(direction, x1, x2, y1, y2, z1, z2, u1, v1, u2, v2, rotation, centerX, centerY, z2);

            float du = uvEnd.u() - uvStart.u();
            float dv = uvEnd.v() - uvStart.v();

            if (Math.abs(du) >= Math.abs(dv)) {
                repeatAxis = AlongAxis.U;
                repeatStart = uvStart.u();
                repeatMin = Math.min(uvStart.u(), uvEnd.u());
                repeatWrapRange = Math.abs(du);
                repeatPixelsPerBlock = du / zSpan;
            } else {
                repeatAxis = AlongAxis.V;
                repeatStart = uvStart.v();
                repeatMin = Math.min(uvStart.v(), uvEnd.v());
                repeatWrapRange = Math.abs(dv);
                repeatPixelsPerBlock = dv / zSpan;
            }

            repeatEnabled = repeatWrapRange > EPSILON && Math.abs(repeatPixelsPerBlock) > EPSILON;
        }

        return new ModelFace(
                direction,
                sprite,
                x1,
                x2,
                y1,
                y2,
                z1,
                z2,
                u1,
                v1,
                u2,
                v2,
                rotation,
                repeatEnabled,
                repeatAxis,
                repeatStart,
                repeatMin,
                repeatPixelsPerBlock,
                repeatWrapRange
        );
    }

    private static UvPixel uvFromLocal(Direction direction,
                                       float x1,
                                       float x2,
                                       float y1,
                                       float y2,
                                       float z1,
                                       float z2,
                                       float u1,
                                       float v1,
                                       float u2,
                                       float v2,
                                       int rotation,
                                       float x,
                                       float y,
                                       float z) {
        FaceParams params = faceParams(direction, x1, x2, y1, y2, z1, z2, x, y, z);
        FaceParams rotated = rotateParams(params.s(), params.t(), rotation);
        return new UvPixel(
                lerp(u1, u2, rotated.s()),
                lerp(v1, v2, rotated.t())
        );
    }

    private static FaceParams faceParams(Direction direction,
                                         float x1,
                                         float x2,
                                         float y1,
                                         float y2,
                                         float z1,
                                         float z2,
                                         float x,
                                         float y,
                                         float z) {
        float s;
        float t;

        switch (direction) {
            case NORTH -> {
                s = invLerp(x1, x2, x);
                t = invLerp(y2, y1, y);
            }
            case SOUTH -> {
                s = invLerp(x2, x1, x);
                t = invLerp(y2, y1, y);
            }
            case WEST -> {
                s = invLerp(z2, z1, z);
                t = invLerp(y2, y1, y);
            }
            case EAST -> {
                s = invLerp(z1, z2, z);
                t = invLerp(y2, y1, y);
            }
            case UP -> {
                s = invLerp(x2, x1, x);
                t = invLerp(z2, z1, z);
            }
            case DOWN -> {
                s = invLerp(x2, x1, x);
                t = invLerp(z1, z2, z);
            }
            default -> {
                s = 0.0F;
                t = 0.0F;
            }
        }

        return new FaceParams(clamp01(s), clamp01(t));
    }

    @Nullable
    private static ResourceLocation resolveTexture(Map<String, String> textures,
                                                   String reference,
                                                   String defaultNamespace) {
        String value = reference;
        Set<String> seen = new HashSet<>();

        while (value.startsWith("#")) {
            String key = value.substring(1);
            if (!seen.add(key)) {
                return null;
            }

            value = textures.get(key);
            if (value == null || value.isBlank()) {
                return null;
            }
        }

        if (value.isBlank()) {
            return null;
        }

        String namespaced = value.contains(":") ? value : (defaultNamespace + ":" + value);
        return ResourceLocation.tryParse(namespaced);
    }

    @Nullable
    private static float[] readVec3(@Nullable JsonArray array) {
        if (array == null || array.size() != 3) {
            return null;
        }
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    @Nullable
    private static float[] readUv(@Nullable JsonArray array) {
        if (array == null || array.size() != 4) {
            return null;
        }
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat(), array.get(3).getAsFloat()};
    }
}
