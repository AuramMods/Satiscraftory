package art.arcane.satiscraftory.data.route;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

public enum BeltRouteMode {
    STRAIGHT("straight", "Straight", StraightRoute::build),
    SPLINE("spline", "Spline", SplineRoute::build);

    public static final BeltRouteMode DEFAULT = STRAIGHT;

    private final String id;
    private final String label;
    private final RouteBuilder builder;

    BeltRouteMode(String id, String label, RouteBuilder builder) {
        this.id = id;
        this.label = label;
        this.builder = builder;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public BeltRouteMode next() {
        BeltRouteMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }

    @Nullable
    public static BeltRouteMode byId(String id) {
        for (BeltRouteMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }
        return null;
    }

    public BeltRoute create(BlockPos start, Direction startFacing, BlockPos end, Direction endFacing) {
        Vec3 startCenter = new Vec3(0.5D, 0.0D, 0.5D);
        Vec3 endCenter = startCenter.add((double) end.getX() - start.getX(),
                (double) end.getY() - start.getY(), (double) end.getZ() - start.getZ());
        return builder.build(startCenter, startFacing, endCenter, endFacing);
    }

    static Vec3 forward(Direction direction) {
        return new Vec3(direction.getStepX(), 0.0D, direction.getStepZ());
    }

    @FunctionalInterface
    private interface RouteBuilder {
        BeltRoute build(Vec3 start, Direction startFacing, Vec3 end, Direction endFacing);
    }
}
