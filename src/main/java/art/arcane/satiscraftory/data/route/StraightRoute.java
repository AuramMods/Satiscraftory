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
    private static final int CORNER_SEGMENTS = 8;
    private static final double MAX_CORNER_RADIUS = 0.625D;
    private static final double RAMP_LANDING = 0.125D;
    private static final double MIN_RAMP_RUN = 0.25D;
    private static final double FULL_RAMP_LEG = 2.0D;

    private StraightRoute() {
    }

    static BeltRoute build(Vec3 start, Direction startFacing, Vec3 end, Direction endFacing) {
        List<Vec3> plan = planWithLeads(start, startFacing, end, endFacing, false);
        double[] radii = cornerRadii(plan);
        int rampEnd = -1;
        if (start.y != end.y) {
            rampEnd = findRampLeg(plan, radii);
            if (rampEnd == -1) {
                // Tight turns can use the whole leg. Find a little more straight run for the ramp.
                plan = planWithLeads(start, startFacing, end, endFacing, true);
                radii = cornerRadii(plan);
                rampEnd = findRampLeg(plan, radii);
            }
        }

        List<Vec3> points = new ArrayList<>();
        Map<Integer, Vec3> arcSides = new HashMap<>();
        double height = start.y;
        for (int i = 0; i < plan.size(); i++) {
            Vec3 point = plan.get(i);
            if (i == rampEnd) {
                Vec3 previous = plan.get(i - 1);
                Vec3 heading = point.subtract(previous).normalize();
                append(points, atHeight(previous.add(heading.scale(rampInset(radii[i - 1]))), height));
                append(points, atHeight(point.subtract(heading.scale(rampInset(radii[i]))), end.y));
                height = end.y;
            }

            double radius = radii[i];
            Vec3 corner = atHeight(point, height);
            if (radius > 0.0D) {
                Vec3 into = point.subtract(plan.get(i - 1)).normalize();
                Vec3 out = plan.get(i + 1).subtract(point).normalize();
                appendCorner(points, arcSides, corner, into, out, radius);
            } else {
                append(points, corner);
            }
        }
        return new BeltRoute(points, arcSides);
    }

    private static List<Vec3> planWithLeads(Vec3 start, Direction startFacing, Vec3 end, Direction endFacing,
                                            boolean requireRampRoom) {
        List<Vec3> plan = horizontalPlan(start, startFacing, end, endFacing, requireRampRoom);
        plan.add(0, atHeight(start.subtract(BeltRouteMode.forward(startFacing).scale(0.5D)), 0.0D));
        plan.add(atHeight(end.add(BeltRouteMode.forward(endFacing).scale(0.5D)), 0.0D));
        return plan;
    }

    private static double[] cornerRadii(List<Vec3> plan) {
        boolean[] turns = new boolean[plan.size()];
        for (int i = 1; i < plan.size() - 1; i++) {
            Vec3 into = plan.get(i).subtract(plan.get(i - 1));
            Vec3 out = plan.get(i + 1).subtract(plan.get(i));
            turns[i] = into.dot(out) == 0.0D;
        }

        double[] radii = new double[plan.size()];
        for (int i = 1; i < plan.size() - 1; i++) {
            if (turns[i]) {
                double before = plan.get(i).distanceTo(plan.get(i - 1)) / (turns[i - 1] ? 2.0D : 1.0D);
                double after = plan.get(i).distanceTo(plan.get(i + 1)) / (turns[i + 1] ? 2.0D : 1.0D);
                radii[i] = Math.min(MAX_CORNER_RADIUS, Math.min(before, after));
            }
        }
        return radii;
    }

    private static int findRampLeg(List<Vec3> plan, double[] radii) {
        int best = -1;
        double longest = MIN_RAMP_RUN;
        // Leave the two half-block port leads flat.
        for (int i = 2; i < plan.size() - 1; i++) {
            double run = plan.get(i).distanceTo(plan.get(i - 1)) - rampInset(radii[i - 1]) - rampInset(radii[i]);
            if (run >= longest) {
                best = i;
                longest = run;
            }
        }
        return best;
    }

    private static double rampInset(double radius) {
        return radius == 0.0D ? 0.0D : radius + RAMP_LANDING;
    }

    private static Vec3 atHeight(Vec3 point, double height) {
        return new Vec3(point.x, height, point.z);
    }

    private static int append(List<Vec3> points, Vec3 point) {
        if (points.isEmpty() || points.get(points.size() - 1).distanceToSqr(point) > 1.0E-16D) {
            points.add(point);
        }
        return points.size() - 1;
    }

    private static void appendCorner(List<Vec3> points, Map<Integer, Vec3> sides, Vec3 corner,
                                     Vec3 into, Vec3 out, double radius) {
        Vec3 entry = corner.subtract(into.scale(radius));
        Vec3 center = entry.add(out.scale(radius));
        for (int i = 0; i <= CORNER_SEGMENTS; i++) {
            double angle = i * (Math.PI * 0.5D / CORNER_SEGMENTS);
            double sin = i == CORNER_SEGMENTS ? 1.0D : Math.sin(angle);
            double cos = i == CORNER_SEGMENTS ? 0.0D : Math.cos(angle);
            Vec3 radial = into.scale(sin).subtract(out.scale(cos));
            Vec3 tangent = into.scale(cos).add(out.scale(sin));
            int index = append(points, center.add(radial.scale(radius)));
            // A full-width belt can turn around a half-block radius without its inner rail folding back.
            sides.put(index, new Vec3(-tangent.z, 0.0D, tangent.x));
        }
    }

    private static List<Vec3> horizontalPlan(Vec3 start, Direction startFacing, Vec3 end, Direction endFacing,
                                             boolean requireRampRoom) {
        double margin = requireRampRoom ? FULL_RAMP_LEG : 1.0D;
        double[] xs = tracks(start.x, end.x, margin);
        double[] zs = tracks(start.z, end.z, margin);
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
        Step first = new Step(new Node(startX, startZ, startFacing, 0.0D, !requireRampRoom), 0.0D, 0, 0.0D, null);
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

                double leg = Math.abs(xs[x] - xs[node.x()]) + Math.abs(zs[z] - zs[node.z()]);
                double run = node.hasRampRoom() ? 0.0D
                        : leg + (direction == node.heading() ? node.runLength() : 0.0D);
                boolean hasRampRoom = node.hasRampRoom() || run >= FULL_RAMP_LEG;
                if (reachesEnd && !hasRampRoom) {
                    continue;
                }
                double distance = step.distance() + leg;
                Node next = new Node(x, z, direction, hasRampRoom ? 0.0D : run, hasRampRoom);
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

    private static double[] tracks(double start, double end, double margin) {
        double min = Math.min(start, end);
        double max = Math.max(start, end);
        if (min == max) {
            return new double[]{min - margin, min, min + margin};
        }
        if (max - min < 2.0D) {
            return new double[]{min - margin, min, max, max + margin};
        }
        return new double[]{min - margin, min, (min + max) * 0.5D, max, max + margin};
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

    private record Node(int x, int z, Direction heading, double runLength, boolean hasRampRoom) {
    }

    private record Step(Node node, double distance, int turns, double turnOffset, Step previous) {
    }
}
