package art.arcane.satiscraftory.data.route;

import art.arcane.satiscraftory.data.ConveyorTier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Selection geometry in belt-local coordinates. The profile follows the deck and both rails. */
public final class BeltHitbox {
    private static final double EPSILON = 1.0E-8D;
    private static final double DECK_HEIGHT = 1.0D / 16.0D;

    private final Vec3[][] rings;
    private final AABB[] spans;
    private final List<Quad> caps = new ArrayList<>();

    public BeltHitbox(BeltRoute route, ConveyorTier tier) {
        double width = tier == ConveyorTier.MARK_1 || tier == ConveyorTier.MARK_2 ? 6.0D / 16.0D : 0.5D;
        double inside = width - 2.0D / 16.0D;
        boolean raisedRim = tier == ConveyorTier.MARK_5;
        double railHeight = raisedRim ? 3.0D / 16.0D : 2.0D / 16.0D;
        List<ProfilePoint> rightSide = new ArrayList<>();
        rightSide.add(new ProfilePoint(width, 0.0D));
        rightSide.add(new ProfilePoint(width, railHeight));
        if (raisedRim) {
            double rimOutside = inside + 1.0D / 16.0D;
            rightSide.add(new ProfilePoint(rimOutside, railHeight));
            rightSide.add(new ProfilePoint(rimOutside, 5.0D / 16.0D));
            rightSide.add(new ProfilePoint(inside, 5.0D / 16.0D));
        } else {
            rightSide.add(new ProfilePoint(inside, railHeight));
        }
        rightSide.add(new ProfilePoint(inside, DECK_HEIGHT));

        rings = new Vec3[route.pointCount()][rightSide.size() * 2];
        spans = new AABB[rings.length - 1];
        for (int i = 0; i < rings.length; i++) {
            for (int j = 0; j < rightSide.size(); j++) {
                ProfilePoint point = rightSide.get(j);
                rings[i][j] = crossSection(route, i, point.lateral(), point.height());
                rings[i][rings[i].length - 1 - j] = crossSection(route, i, -point.lateral(), point.height());
            }
            if (i > 0) {
                spans[i - 1] = bounds(rings[i - 1], rings[i]).inflate(EPSILON);
            }
        }
        for (int end : new int[]{0, rings.length - 1}) {
            addCap(route, end, -inside, inside, 0.0D, DECK_HEIGHT);
            addCap(route, end, -width, -inside, 0.0D, railHeight);
            addCap(route, end, inside, width, 0.0D, railHeight);
            if (raisedRim) {
                addCap(route, end, -inside - 1.0D / 16.0D, -inside, railHeight, 5.0D / 16.0D);
                addCap(route, end, inside, inside + 1.0D / 16.0D, railHeight, 5.0D / 16.0D);
            }
        }
    }

    @Nullable
    public Vec3 clip(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < spans.length; i++) {
            if (!spans[i].contains(from) && spans[i].clip(from, to).isEmpty()) {
                continue;
            }
            Vec3[] a = rings[i];
            Vec3[] b = rings[i + 1];
            for (int j = 0; j < a.length; j++) {
                int next = (j + 1) % a.length;
                nearest = Math.min(nearest, triangle(from, delta, a[j], a[next], b[next]));
                nearest = Math.min(nearest, triangle(from, delta, a[j], b[next], b[j]));
            }
        }
        for (Quad cap : caps) {
            nearest = Math.min(nearest, triangle(from, delta, cap.a(), cap.b(), cap.c()));
            nearest = Math.min(nearest, triangle(from, delta, cap.a(), cap.c(), cap.d()));
        }
        return Double.isFinite(nearest) ? from.add(delta.scale(nearest)) : null;
    }

    public void forEachOutline(OutlineConsumer draw) {
        for (int i = 1; i < rings.length; i++) {
            for (int j = 0; j < rings[i].length; j++) {
                draw.line(rings[i - 1][j], rings[i][j]);
            }
        }
        for (Vec3[] end : new Vec3[][]{rings[0], rings[rings.length - 1]}) {
            for (int j = 0; j < end.length; j++) {
                draw.line(end[j], end[(j + 1) % end.length]);
            }
        }
    }

    private void addCap(BeltRoute route, int end, double left, double right, double bottom, double top) {
        caps.add(new Quad(crossSection(route, end, left, bottom), crossSection(route, end, right, bottom),
                crossSection(route, end, right, top), crossSection(route, end, left, top)));
    }

    private static Vec3 crossSection(BeltRoute route, int i, double lateral, double height) {
        return route.point(i).add(route.side(i).scale(lateral)).add(0.0D, height, 0.0D);
    }

    private static AABB bounds(Vec3[] a, Vec3[] b) {
        AABB box = new AABB(a[0], b[0]);
        for (int i = 1; i < a.length; i++) {
            box = box.minmax(new AABB(a[i], b[i]));
        }
        return box;
    }

    private static double triangle(Vec3 origin, Vec3 ray, Vec3 a, Vec3 b, Vec3 c) {
        Vec3 edge = b.subtract(a);
        Vec3 side = c.subtract(a);
        Vec3 cross = ray.cross(side);
        double determinant = edge.dot(cross);
        if (Math.abs(determinant) < EPSILON) {
            return Double.POSITIVE_INFINITY;
        }
        Vec3 offset = origin.subtract(a);
        double u = offset.dot(cross) / determinant;
        Vec3 other = offset.cross(edge);
        double v = ray.dot(other) / determinant;
        double t = side.dot(other) / determinant;
        return u >= -EPSILON && v >= -EPSILON && u + v <= 1.0D + EPSILON && t >= 0.0D && t <= 1.0D
                ? t : Double.POSITIVE_INFINITY;
    }

    @FunctionalInterface
    public interface OutlineConsumer {
        void line(Vec3 from, Vec3 to);
    }

    private record ProfilePoint(double lateral, double height) {}

    private record Quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d) {}
}
