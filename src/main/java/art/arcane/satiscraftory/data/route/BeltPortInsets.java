package art.arcane.satiscraftory.data.route;

import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

public record BeltPortInsets(double input, double output) {
    private static final double PORT_DEPTH_EPSILON = 1.0E-6D;

    public static BeltPortInsets measure(Level level, BlockPos start, Direction startFacing, BlockPos end, Direction endFacing) {
        return new BeltPortInsets(inset(level, start, startFacing.getOpposite()), inset(level, end, endFacing));
    }

    public BeltRoute extend(BeltRoute route, Direction startFacing, Direction endFacing) {
        return route.withPortExtensions(BeltRouteMode.forward(startFacing.getOpposite()).scale(input),
                BeltRouteMode.forward(endFacing).scale(output));
    }

    private static double inset(Level level, BlockPos anchor, Direction towardContainer) {
        BlockPos pos = anchor.relative(towardContainer);
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null || entity instanceof ConveyorBlockEntity
                || !(entity.getCapability(ForgeCapabilities.ITEM_HANDLER, towardContainer.getOpposite()).isPresent()
                || entity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent())) {
            return 0.0D;
        }

        // The first surface at deck height is the contact point, even when the shape has several boxes.
        Vec3 direction = BeltRouteMode.forward(towardContainer);
        Vec3 center = Vec3.atBottomCenterOf(pos).add(0.0D, BeltRoute.DECK_HEIGHT, 0.0D);
        Vec3 edge = center.subtract(direction.scale(0.5D));
        var hit = level.getBlockState(pos).getShape(level, pos).clip(edge.subtract(direction.scale(PORT_DEPTH_EPSILON)),
                center.add(direction.scale(0.5D + PORT_DEPTH_EPSILON)), pos);
        if (hit == null || hit.isInside()) {
            return 0.0D;
        }
        double depth = hit.getLocation().subtract(edge).dot(direction);
        return depth > PORT_DEPTH_EPSILON ? Math.min(depth, 1.0D) : 0.0D;
    }
}
