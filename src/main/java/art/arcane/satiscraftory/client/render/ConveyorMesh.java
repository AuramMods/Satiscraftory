package art.arcane.satiscraftory.client.render;

import art.arcane.satiscraftory.client.render.ConveyorModel.AlongAxis;
import art.arcane.satiscraftory.client.render.ConveyorModel.FaceParams;
import art.arcane.satiscraftory.client.render.ConveyorModel.ModelFace;
import art.arcane.satiscraftory.client.render.ConveyorModel.UvPixel;
import art.arcane.satiscraftory.data.route.BeltRoute;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static art.arcane.satiscraftory.client.render.ConveyorModel.EPSILON;
import static art.arcane.satiscraftory.client.render.ConveyorModel.clamp01;
import static art.arcane.satiscraftory.client.render.ConveyorModel.invLerp;
import static art.arcane.satiscraftory.client.render.ConveyorModel.lerp;
import static art.arcane.satiscraftory.client.render.ConveyorModel.rotateParams;

/** Maps a belt profile onto its route and emits strips with distance-based texture repeats. */
final class ConveyorMesh {
    private enum StripAxis {
        NONE,
        S,
        T
    }

    private record LocalVertex(float x, float y, float z, float s, float t) {
    }

    private static final double MAX_STRIP_LENGTH = 0.25D;
    private static final float WRAP_SEAM_RAW_EPSILON_SCALE = 0.0001F;
    private static final int MAX_WRAP_SPLITS_PER_STRIP = 16;

    private final VertexConsumer consumer;
    private final Matrix4f pose;
    private final Matrix3f normalMatrix;
    private final Level level;
    private final BlockPos originPos;
    private final int defaultPackedLight;
    private final int packedOverlay;
    private final BeltRoute route;

    ConveyorMesh(VertexConsumer consumer, Matrix4f pose, Matrix3f normalMatrix, Level level,
                 BlockPos originPos, int defaultPackedLight, int packedOverlay, BeltRoute route) {
        this.consumer = consumer;
        this.pose = pose;
        this.normalMatrix = normalMatrix;
        this.level = level;
        this.originPos = originPos;
        this.defaultPackedLight = defaultPackedLight;
        this.packedOverlay = packedOverlay;
        this.route = route;
    }

    void render(ConveyorModel model) {
        for (ModelFace face : model.faces()) {
            renderRouteFace(face);
        }
    }

    private void renderRouteFace(ModelFace face) {
        StripAxis axis = stripAxis(face.direction());
        if (axis == StripAxis.NONE) {
            renderStripSegment(face, axis, 0.0F, 1.0F);
            return;
        }

        // Split exactly at route vertices so faces never cut across a corner or ramp transition.
        for (int i = 1; i < route.pointCount(); i++) {
            float start = Math.max(face.z1(), (float) route.progressAtPoint(i - 1));
            float end = Math.min(face.z2(), (float) route.progressAtPoint(i));
            if (end - start <= EPSILON) {
                continue;
            }

            int strips = Math.max(1, (int) Math.ceil((end - start) * route.length() / MAX_STRIP_LENGTH));
            float from = invLerp(face.z1(), face.z2(), start);
            float to = invLerp(face.z1(), face.z2(), end);
            for (int strip = 0; strip < strips; strip++) {
                renderStripRange(face, axis, lerp(from, to, strip / (float) strips),
                        lerp(from, to, (strip + 1) / (float) strips));
            }
        }
    }

    private void renderStripRange(ModelFace face, StripAxis stripAxis, float stripStart, float stripEnd) {
        if (!face.repeatEnabled() || face.repeatWrapRange() < EPSILON) {
            renderStripSegment(face, stripAxis, stripStart, stripEnd);
            return;
        }

        float currentStart = stripStart;
        for (int i = 0; i < MAX_WRAP_SPLITS_PER_STRIP && (stripEnd - currentStart) > EPSILON; i++) {
            float currentEnd = stripEnd;
            float nextStart = currentEnd;
            float rawStart = repeatRawAt(face, route, currentStart);
            float rawEnd = repeatRawAt(face, route, currentEnd);
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

                    float splitBefore = findSplitTForRaw(face, route, currentStart, currentEnd, beforeBoundaryRaw, delta > 0.0F);
                    float splitAfter = findSplitTForRaw(face, route, currentStart, currentEnd, afterBoundaryRaw, delta > 0.0F);

                    if ((splitBefore - currentStart) > EPSILON
                            && (currentEnd - splitAfter) > EPSILON
                            && (splitAfter - splitBefore) > EPSILON) {
                        currentEnd = splitBefore;
                        nextStart = splitAfter;
                    } else {
                        float split = findSplitTForRaw(face, route, currentStart, currentEnd, boundary, delta > 0.0F);
                        if ((split - currentStart) > EPSILON && (currentEnd - split) > EPSILON) {
                            currentEnd = split;
                            nextStart = split;
                        }
                    }
                }
            }

            if ((currentEnd - currentStart) > EPSILON) {
                renderStripSegment(face, stripAxis, currentStart, currentEnd);
            }

            if ((stripEnd - nextStart) <= EPSILON) {
                break;
            }
            currentStart = nextStart;
        }
    }

    private void renderStripSegment(ModelFace face, StripAxis stripAxis, float stripStart, float stripEnd) {
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

        Vec3 worldA = mapLocalToRoute(a, route);
        Vec3 worldB = mapLocalToRoute(b, route);
        Vec3 worldC = mapLocalToRoute(c, route);
        Vec3 worldD = mapLocalToRoute(d, route);

        double distA = route.length() * a.z();
        double distB = route.length() * b.z();
        double distC = route.length() * c.z();
        double distD = route.length() * d.z();

        float normalT = (zA + zB) * 0.5F;
        double midDistance = route.length() * normalT;
        UvPixel uvA = uvPixel(face, a, distA, midDistance);
        UvPixel uvB = uvPixel(face, b, distB, midDistance);
        UvPixel uvC = uvPixel(face, c, distC, midDistance);
        UvPixel uvD = uvPixel(face, d, distD, midDistance);

        Vec3 preferredNormal = expectedNormal(face.direction(), normalT, route);
        emitQuad(
                face.sprite(),
                face.emissive(),
                worldA,
                worldB,
                worldC,
                worldD,
                uvA,
                uvB,
                uvC,
                uvD,
                preferredNormal
        );
    }

    private static Vec3 mapLocalToRoute(LocalVertex local, BeltRoute route) {
        Vec3 side = route.lateral(local.z());
        return route.position(local.z()).add(side.scale(local.x() - 0.5D)).add(0.0D, local.y(), 0.0D);
    }

    private static UvPixel uvPixel(ModelFace face, LocalVertex local, double distance, double midDistance) {
        FaceParams rotated = rotateParams(local.s(), local.t(), face.rotation());
        float u = lerp(face.u1(), face.u2(), rotated.s());
        float v = lerp(face.v1(), face.v2(), rotated.t());

        if (face.repeatEnabled()) {
            float rawRepeated = face.repeatStart() + ((float) distance * face.repeatPixelsPerBlock());
            float repeated = wrapToRange(rawRepeated, face.repeatMin(), face.repeatWrapRange());

            // Select the wrap edge on this strip's side of an exact UV seam.
            if (Math.abs(repeated - face.repeatMin()) < 1.0E-5F) {
                float rawMiddle = face.repeatStart() + ((float) midDistance * face.repeatPixelsPerBlock());
                if (rawRepeated > rawMiddle) {
                    repeated = face.repeatMin() + face.repeatWrapRange();
                }
            }

            if (face.repeatAxis() == AlongAxis.U) {
                u = repeated;
            } else {
                v = repeated;
            }
        }

        return new UvPixel(u, v);
    }

    private static float repeatRawAt(ModelFace face, BeltRoute route, float t) {
        double distance = route.length() * lerp(face.z1(), face.z2(), t);
        return face.repeatStart() + ((float) distance * face.repeatPixelsPerBlock());
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
                                          BeltRoute route,
                                          float tMin,
                                          float tMax,
                                          float boundaryRawValue,
                                          boolean increasing) {
        float low = tMin;
        float high = tMax;
        for (int i = 0; i < 24; i++) {
            float mid = (low + high) * 0.5F;
            float raw = repeatRawAt(face, route, mid);
            if ((raw < boundaryRawValue) == increasing) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) * 0.5F;
    }

    private void emitQuad(TextureAtlasSprite sprite,
                          boolean emissive,
                          Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                          UvPixel uvA, UvPixel uvB, UvPixel uvC, UvPixel uvD,
                          Vec3 preferredNormal) {
        Vector3f normal = computeNormal(a, b, c);
        if (preferredNormal.lengthSqr() > 1.0E-8D) {
            float dot = (float) ((normal.x() * preferredNormal.x)
                    + (normal.y() * preferredNormal.y)
                    + (normal.z() * preferredNormal.z));
            if (dot < 0.0F) {
                normal = new Vector3f(-normal.x(), -normal.y(), -normal.z());
            }
        }

        int lightA;
        int lightB;
        int lightC;
        int lightD;
        if (emissive) {
            lightA = LightTexture.FULL_BRIGHT;
            lightB = LightTexture.FULL_BRIGHT;
            lightC = LightTexture.FULL_BRIGHT;
            lightD = LightTexture.FULL_BRIGHT;
        } else {
            lightA = samplePackedLight(level, originPos, a, defaultPackedLight);
            lightB = samplePackedLight(level, originPos, b, defaultPackedLight);
            lightC = samplePackedLight(level, originPos, c, defaultPackedLight);
            lightD = samplePackedLight(level, originPos, d, defaultPackedLight);
        }

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

    private static Vec3 expectedNormal(Direction faceDirection, float t, BeltRoute route) {
        Vec3 perpendicular = route.lateral(t).normalize();
        Vec3 tangent = route.tangent(t);

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

    private static float wrapToRange(float value, float min, float range) {
        if (range < EPSILON) {
            return value;
        }
        float delta = value - min;
        float wrappedDelta = (float) (delta - Math.floor(delta / range) * range);
        return min + wrappedDelta;
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
