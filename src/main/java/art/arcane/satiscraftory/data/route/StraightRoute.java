package art.arcane.satiscraftory.data.route;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

final class StraightRoute {
    private StraightRoute() {
    }

    static List<Vec3> points(Vec3 start, Direction startFacing, Vec3 end, Direction endFacing) {
        List<Vec3> plan = horizontalPlan(start, startFacing, end, endFacing);

        // Change height on a single straight run. The longest run gives the gentlest incline.
        int rampEnd = 1;
        for (int i = 2; i < plan.size(); i++) {
            if (plan.get(i).distanceToSqr(plan.get(i - 1))
                    > plan.get(rampEnd).distanceToSqr(plan.get(rampEnd - 1))) {
                rampEnd = i;
            }
        }

        List<Vec3> points = new ArrayList<>(plan.size() + 2);
        points.add(start.subtract(BeltRouteMode.forward(startFacing).scale(0.5D)));
        for (int i = 0; i < plan.size(); i++) {
            Vec3 point = plan.get(i);
            points.add(new Vec3(point.x, i < rampEnd ? start.y : end.y, point.z));
        }
        points.add(end.add(BeltRouteMode.forward(endFacing).scale(0.5D)));
        return points;
    }

    private static List<Vec3> horizontalPlan(Vec3 start, Direction startFacing, Vec3 end, Direction endFacing) {
        double[] xs = tracks(start.x, end.x);
        double[] zs = tracks(start.z, end.z);
        int startX = indexOf(xs, start.x);
        int startZ = indexOf(zs, start.z);
        int endX = indexOf(xs, end.x);
        int endZ = indexOf(zs, end.z);

        // Outside tracks provide room to turn around. A middle track centers two-turn offsets.
        // Heading is part of the search state so the belt never doubles back through itself at a bend.
        Comparator<Step> order = Comparator.comparingDouble(Step::distance)
                .thenComparingInt(Step::turns)
                .thenComparingDouble(Step::turnOffset);
        PriorityQueue<Step> pending = new PriorityQueue<>(order);
        Map<Node, Step> best = new HashMap<>();
        Step first = new Step(new Node(startX, startZ, startFacing), 0.0D, 0, 0.0D, null);
        pending.add(first);
        best.put(first.node(), first);
        Vec3 middle = start.lerp(end, 0.5D);

        while (!pending.isEmpty()) {
            Step step = pending.remove();
            Node node = step.node();
            if (best.get(node) != step) {
                continue;
            }
            if (step.previous() != null && node.x() == endX && node.z() == endZ) {
                return waypoints(step, xs, zs);
            }

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (direction == node.heading().getOpposite()) {
                    continue;
                }
                int x = node.x() + direction.getStepX();
                int z = node.z() + direction.getStepZ();
                if (x < 0 || x >= xs.length || z < 0 || z >= zs.length) {
                    continue;
                }
                boolean reachesEnd = x == endX && z == endZ;
                if (reachesEnd ? direction == endFacing.getOpposite() : x == startX && z == startZ) {
                    continue;
                }

                int turns = step.turns();
                double turnOffset = step.turnOffset();
                if (direction != node.heading()) {
                    turns++;
                    turnOffset += Math.abs(xs[node.x()] - middle.x) + Math.abs(zs[node.z()] - middle.z);
                }
                if (reachesEnd && direction != endFacing) {
                    turns++;
                    turnOffset += Math.abs(end.x - middle.x) + Math.abs(end.z - middle.z);
                }

                double distance = step.distance() + Math.abs(xs[x] - xs[node.x()]) + Math.abs(zs[z] - zs[node.z()]);
                Node next = new Node(x, z, direction);
                Step candidate = new Step(next, distance, turns, turnOffset, step);
                Step known = best.get(next);
                if (known == null || order.compare(candidate, known) < 0) {
                    best.put(next, candidate);
                    pending.add(candidate);
                }
            }
        }
        throw new IllegalStateException("Could not connect belt endpoints");
    }

    private static double[] tracks(double start, double end) {
        double min = Math.min(start, end);
        double max = Math.max(start, end);
        if (min == max) {
            return new double[]{min - 1.0D, min, min + 1.0D};
        }
        if (max - min < 2.0D) {
            return new double[]{min - 1.0D, min, max, max + 1.0D};
        }
        return new double[]{min - 1.0D, min, (min + max) * 0.5D, max, max + 1.0D};
    }

    private static int indexOf(double[] tracks, double coordinate) {
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i] == coordinate) {
                return i;
            }
        }
        throw new IllegalArgumentException("Endpoint must lie on a routing track");
    }

    private static List<Vec3> waypoints(Step last, double[] xs, double[] zs) {
        List<Vec3> points = new ArrayList<>();
        Step next = null;
        for (Step step = last; step != null; step = step.previous()) {
            Node node = step.node();
            if (next == null || step.previous() == null || node.heading() != next.node().heading()) {
                points.add(new Vec3(xs[node.x()], 0.0D, zs[node.z()]));
            }
            next = step;
        }
        Collections.reverse(points);
        return points;
    }

    private record Node(int x, int z, Direction heading) {
    }

    private record Step(Node node, double distance, int turns, double turnOffset, Step previous) {
    }
}
