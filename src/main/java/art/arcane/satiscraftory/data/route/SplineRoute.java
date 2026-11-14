package art.arcane.satiscraftory.data.route;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

final class SplineRoute {
    private static final int SEGMENTS = 80;

    private SplineRoute() {
    }

    static BeltRoute build(Vec3 start, Direction startFacing, Vec3 end, Direction endFacing) {
        Vec3 startForward = BeltRouteMode.forward(startFacing);
        Vec3 endForward = BeltRouteMode.forward(endFacing);
        Vec3 a = start.subtract(startForward.scale(0.5D));
        Vec3 d = end.add(endForward.scale(0.5D));
        double horizontalDistance = Math.hypot(d.x - a.x, d.z - a.z);
        double handleLength = Math.max(0.85D, Math.min(4.0D, horizontalDistance * 0.45D));
        Vec3 b = a.add(startForward.scale(handleLength));
        Vec3 c = d.subtract(endForward.scale(handleLength));

        List<Vec3> points = new ArrayList<>(SEGMENTS + 1);
        points.add(a);
        for (int i = 1; i <= SEGMENTS; i++) {
            double t = (double) i / SEGMENTS;
            double u = 1.0D - t;
            Vec3 point = a.scale(u * u * u).add(b.scale(3.0D * u * u * t))
                    .add(c.scale(3.0D * u * t * t)).add(d.scale(t * t * t));
            if (point.distanceToSqr(points.get(points.size() - 1)) > 1.0E-12D) {
                points.add(point);
            }
        }
        return new BeltRoute(points);
    }
}
