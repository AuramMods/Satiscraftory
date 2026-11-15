package art.arcane.satiscraftory.data.route;

import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** A belt centerline in coordinates relative to its start block. Progress is measured by distance. */
public final class BeltRoute {
    public static final double DECK_HEIGHT = 1.0D / 16.0D;
    private static final double DIRECTION_SAMPLE_DISTANCE = 1.0D / 16.0D;

    private final Vec3[] points;
    private final Vec3[] sides;
    private final double[] distances;

    BeltRoute(List<Vec3> points) {
        this(points, Map.of());
    }

    BeltRoute(List<Vec3> points, Map<Integer, Vec3> exactSides) {
        this.points = points.toArray(Vec3[]::new);
        sides = new Vec3[this.points.length];
        distances = new double[this.points.length];

        Vec3 incoming = sideOf(this.points[1].subtract(this.points[0]));
        sides[0] = incoming;
        for (int i = 1; i < this.points.length; i++) {
            distances[i] = distances[i - 1] + this.points[i].distanceTo(this.points[i - 1]);
            if (i == this.points.length - 1) {
                sides[i] = incoming;
                break;
            }

            Vec3 outgoing = sideOf(this.points[i + 1].subtract(this.points[i]));
            Vec3 bisector = incoming.add(outgoing);
            // Extend the shared cross-section at a corner so the inner and outer rails meet.
            sides[i] = bisector.lengthSqr() < 1.0E-8D ? outgoing
                    : bisector.scale(1.0D / Math.max(0.5D, 1.0D + incoming.dot(outgoing)));
            incoming = outgoing;
        }
        exactSides.forEach((index, side) -> sides[index] = side);
    }

    public int pointCount() {
        return points.length;
    }

    public Vec3 point(int index) {
        return points[index];
    }

    public Vec3 side(int index) {
        return sides[index];
    }

    public double progressAtPoint(int index) {
        return distances[index] / length();
    }

    public double length() {
        return distances[distances.length - 1];
    }

    public int lengthInBlocks() {
        // Suppress rounding error on mathematically integral lengths.
        return Math.max(1, (int) Math.ceil(length() - 1.0E-8D));
    }

    public Vec3 position(double progress) {
        double distance = distanceAt(progress);
        int segment = segmentAt(distance);
        return points[segment].lerp(points[segment + 1], fractionInSegment(distance, segment));
    }

    public Vec3 lateral(double progress) {
        double distance = distanceAt(progress);
        int segment = segmentAt(distance);
        return sides[segment].lerp(sides[segment + 1], fractionInSegment(distance, segment));
    }

    public Vec3 tangent(double progress) {
        int segment = segmentAt(distanceAt(progress));
        return points[segment + 1].subtract(points[segment]).normalize();
    }

    public Vec3 surfaceDirection(double progress) {
        // Sample across nearby mesh joints so cargo turns continuously as it moves through them.
        double span = DIRECTION_SAMPLE_DISTANCE / length();
        Vec3 direction = position(progress + span).subtract(position(progress - span)).normalize();
        return direction.lengthSqr() > 0.0D ? direction : tangent(progress);
    }

    private double distanceAt(double progress) {
        return Math.max(0.0D, Math.min(1.0D, progress)) * length();
    }

    private int segmentAt(double distance) {
        int index = Arrays.binarySearch(distances, distance);
        return index < 0 ? -index - 2 : Math.min(index, points.length - 2);
    }

    private double fractionInSegment(double distance, int segment) {
        return (distance - distances[segment]) / (distances[segment + 1] - distances[segment]);
    }

    private static Vec3 sideOf(Vec3 direction) {
        Vec3 lateral = new Vec3(-direction.z, 0.0D, direction.x).normalize();
        return lateral.lengthSqr() == 0.0D ? new Vec3(1.0D, 0.0D, 0.0D) : lateral;
    }
}
