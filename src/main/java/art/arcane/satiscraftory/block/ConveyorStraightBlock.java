package art.arcane.satiscraftory.block;

import art.arcane.satiscraftory.block.entity.ConveyorStraightBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

public class ConveyorStraightBlock extends BaseEntityBlock implements ConveyorConnectable {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<ConveyorShape> SHAPE = EnumProperty.create("shape", ConveyorShape.class);
    private static final VoxelShape SHAPE_NS = Shapes.or(
            Block.box(0.0D, 0.0D, 0.0D, 16.0D, 2.0D, 2.0D),
            Block.box(0.0D, 0.0D, 14.0D, 16.0D, 2.0D, 16.0D),
            Block.box(0.0D, 0.0D, 2.0D, 16.0D, 1.0D, 14.0D)
    );
    private static final VoxelShape SHAPE_EW = Shapes.or(
            Block.box(0.0D, 0.0D, 0.0D, 2.0D, 2.0D, 16.0D),
            Block.box(14.0D, 0.0D, 0.0D, 16.0D, 2.0D, 16.0D),
            Block.box(2.0D, 0.0D, 0.0D, 14.0D, 1.0D, 16.0D)
    );
    private static final VoxelShape SHAPE_CORNER = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 2.0D, 16.0D);

    public enum ConveyorShape implements StringRepresentable {
        STRAIGHT("straight"),
        LEFT("left"),
        RIGHT("right");

        private final String name;

        ConveyorShape(String name) {
            this.name = name;
        }

        public ConveyorShape oppositeTurn() {
            if (this == STRAIGHT) {
                return STRAIGHT;
            }
            return this == LEFT ? RIGHT : LEFT;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public ConveyorStraightBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(SHAPE, ConveyorShape.STRAIGHT));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(SHAPE) == ConveyorShape.STRAIGHT) {
            return state.getValue(FACING).getAxis() == Direction.Axis.X ? SHAPE_NS : SHAPE_EW;
        }
        return SHAPE_CORNER;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(SHAPE, ConveyorShape.STRAIGHT);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        BlockState mirrored = state.rotate(mirror.getRotation(state.getValue(FACING)));
        if (mirror != Mirror.NONE) {
            return mirrored.setValue(SHAPE, state.getValue(SHAPE).oppositeTurn());
        }
        return mirrored;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SHAPE);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) {
            return;
        }
        BlockState placedState = level.getBlockState(pos);
        BlockState resolvedState = resolvePlacedStateFromNeighbors(level, pos, placedState);
        if (!resolvedState.equals(placedState)) {
            level.setBlock(pos, resolvedState, Block.UPDATE_ALL);
            placedState = resolvedState;
        }
        reconcileNeighborConnections(level, pos, placedState);
    }

    private BlockState resolvePlacedStateFromNeighbors(Level level, BlockPos pos, BlockState placedState) {
        if (!(placedState.getBlock() instanceof ConveyorConnectable)) {
            return placedState;
        }

        List<NeighborConnection> neighbors = collectNeighborConnections(level, pos);
        if (neighbors.isEmpty()) {
            return placedState;
        }

        if (neighbors.size() == 1) {
            NeighborConnection neighbor = neighbors.get(0);
            Direction inputDirection;
            Direction outputDirection;
            if (neighbor.outputsToPlaced()) {
                inputDirection = neighbor.direction();
                outputDirection = inputDirection.getOpposite();
            } else if (neighbor.acceptsFromPlaced()) {
                outputDirection = neighbor.direction();
                inputDirection = outputDirection.getOpposite();
            } else {
                inputDirection = neighbor.direction();
                outputDirection = inputDirection.getOpposite();
            }
            return withResolvedConnections(placedState, inputDirection, outputDirection);
        }

        NeighborPair pair = chooseNeighborPair(neighbors);
        Direction inputDirection;
        Direction outputDirection;
        if (pair.a().direction().getOpposite() == pair.b().direction()) {
            outputDirection = resolveStraightOutput(pair.a(), pair.b());
            inputDirection = outputDirection.getOpposite();
        } else {
            Direction[] corner = resolveCornerDirections(pair.a(), pair.b());
            inputDirection = corner[0];
            outputDirection = corner[1];
        }
        return withResolvedConnections(placedState, inputDirection, outputDirection);
    }

    private void reconcileNeighborConnections(Level level, BlockPos placedPos, BlockState placedState) {
        if (!(placedState.getBlock() instanceof ConveyorConnectable connectable)) {
            return;
        }

        Direction inputDirection = connectable.getInputDirection(placedState);
        Direction outputDirection = connectable.getOutputDirection(placedState);

        BlockPos inputNeighborPos = placedPos.relative(inputDirection);
        BlockState inputNeighborState = level.getBlockState(inputNeighborPos);
        if (inputNeighborState.getBlock() instanceof ConveyorConnectable inputNeighbor) {
            Direction neighborOutputDirection = directionFromTo(inputNeighborPos, placedPos);
            if (neighborOutputDirection != null && neighborOutputDirection.getAxis().isHorizontal()) {
                BlockState updatedInputNeighbor = inputNeighbor.withOutputConnection(inputNeighborState, neighborOutputDirection);
                if (!updatedInputNeighbor.equals(inputNeighborState)) {
                    level.setBlock(inputNeighborPos, updatedInputNeighbor, Block.UPDATE_ALL);
                }
            }
        }

        BlockPos outputNeighborPos = placedPos.relative(outputDirection);
        BlockState outputNeighborState = level.getBlockState(outputNeighborPos);
        if (outputNeighborState.getBlock() instanceof ConveyorConnectable outputNeighbor) {
            Direction neighborInputDirection = directionFromTo(outputNeighborPos, placedPos);
            if (neighborInputDirection != null && neighborInputDirection.getAxis().isHorizontal()) {
                BlockState updatedOutputNeighbor = outputNeighbor.withInputConnection(outputNeighborState, neighborInputDirection);
                if (!updatedOutputNeighbor.equals(outputNeighborState)) {
                    level.setBlock(outputNeighborPos, updatedOutputNeighbor, Block.UPDATE_ALL);
                }
            }
        }
    }

    private static List<NeighborConnection> collectNeighborConnections(Level level, BlockPos pos) {
        List<NeighborConnection> neighbors = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState neighborState = level.getBlockState(pos.relative(direction));
            if (!(neighborState.getBlock() instanceof ConveyorConnectable connectable)) {
                continue;
            }

            Direction requiredOutput = direction.getOpposite();
            boolean outputsToPlaced = connectable.getOutputDirection(neighborState) == requiredOutput;
            boolean acceptsFromPlaced = connectable.getInputDirection(neighborState) == requiredOutput;
            neighbors.add(new NeighborConnection(direction, outputsToPlaced, acceptsFromPlaced));
        }
        return neighbors;
    }

    private static NeighborPair chooseNeighborPair(List<NeighborConnection> neighbors) {
        NeighborPair best = new NeighborPair(neighbors.get(0), neighbors.get(1));
        int bestScore = scorePair(best.a(), best.b());
        for (int i = 0; i < neighbors.size(); i++) {
            for (int j = i + 1; j < neighbors.size(); j++) {
                NeighborPair pair = new NeighborPair(neighbors.get(i), neighbors.get(j));
                int score = scorePair(pair.a(), pair.b());
                if (score > bestScore) {
                    best = pair;
                    bestScore = score;
                    continue;
                }
                if (score == bestScore && tieBreaker(pair, best) < 0) {
                    best = pair;
                }
            }
        }
        return best;
    }

    private static int scorePair(NeighborConnection a, NeighborConnection b) {
        int score = 0;
        if (a.direction().getOpposite() == b.direction()) {
            score += 2;
        }
        if (a.outputsToPlaced()) {
            score++;
        }
        if (b.outputsToPlaced()) {
            score++;
        }
        if (a.acceptsFromPlaced()) {
            score++;
        }
        if (b.acceptsFromPlaced()) {
            score++;
        }
        if (a.outputsToPlaced() && b.acceptsFromPlaced()) {
            score += 2;
        }
        if (b.outputsToPlaced() && a.acceptsFromPlaced()) {
            score += 2;
        }
        return score;
    }

    private static int tieBreaker(NeighborPair candidate, NeighborPair current) {
        int cMin = Math.min(directionPriority(candidate.a().direction()), directionPriority(candidate.b().direction()));
        int cMax = Math.max(directionPriority(candidate.a().direction()), directionPriority(candidate.b().direction()));
        int nMin = Math.min(directionPriority(current.a().direction()), directionPriority(current.b().direction()));
        int nMax = Math.max(directionPriority(current.a().direction()), directionPriority(current.b().direction()));
        if (cMin != nMin) {
            return Integer.compare(cMin, nMin);
        }
        return Integer.compare(cMax, nMax);
    }

    private static int directionPriority(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 99;
        };
    }

    private static Direction resolveStraightOutput(NeighborConnection a, NeighborConnection b) {
        if (a.acceptsFromPlaced() != b.acceptsFromPlaced()) {
            return a.acceptsFromPlaced() ? a.direction() : b.direction();
        }
        if (a.outputsToPlaced() != b.outputsToPlaced()) {
            return a.outputsToPlaced() ? b.direction() : a.direction();
        }
        return directionPriority(a.direction()) <= directionPriority(b.direction()) ? a.direction() : b.direction();
    }

    private static Direction[] resolveCornerDirections(NeighborConnection a, NeighborConnection b) {
        Direction inputDirection;
        Direction outputDirection;
        if (a.outputsToPlaced() && b.acceptsFromPlaced()) {
            inputDirection = a.direction();
            outputDirection = b.direction();
        } else if (b.outputsToPlaced() && a.acceptsFromPlaced()) {
            inputDirection = b.direction();
            outputDirection = a.direction();
        } else if (a.outputsToPlaced() != b.outputsToPlaced()) {
            inputDirection = a.outputsToPlaced() ? a.direction() : b.direction();
            outputDirection = inputDirection == a.direction() ? b.direction() : a.direction();
        } else if (a.acceptsFromPlaced() != b.acceptsFromPlaced()) {
            outputDirection = a.acceptsFromPlaced() ? a.direction() : b.direction();
            inputDirection = outputDirection == a.direction() ? b.direction() : a.direction();
        } else {
            inputDirection = directionPriority(a.direction()) <= directionPriority(b.direction()) ? a.direction() : b.direction();
            outputDirection = inputDirection == a.direction() ? b.direction() : a.direction();
        }
        return new Direction[]{inputDirection, outputDirection};
    }

    private BlockState withResolvedConnections(BlockState state, Direction inputDirection, Direction outputDirection) {
        ConveyorShape shape = shapeFromInputAndOutput(inputDirection, outputDirection);
        return state
                .setValue(FACING, outputDirection.getOpposite())
                .setValue(SHAPE, shape);
    }

    private record NeighborConnection(Direction direction, boolean outputsToPlaced, boolean acceptsFromPlaced) {
    }

    private record NeighborPair(NeighborConnection a, NeighborConnection b) {
    }

    private static Direction directionFromTo(BlockPos from, BlockPos to) {
        int x = Integer.signum(to.getX() - from.getX());
        int y = Integer.signum(to.getY() - from.getY());
        int z = Integer.signum(to.getZ() - from.getZ());
        return Direction.fromDelta(x, y, z);
    }

    @Override
    public Direction getInputDirection(BlockState state) {
        Direction output = getOutputDirection(state);
        return switch (state.getValue(SHAPE)) {
            case STRAIGHT -> output.getOpposite();
            case LEFT -> output.getClockWise();
            case RIGHT -> output.getCounterClockWise();
        };
    }

    @Override
    public Direction getOutputDirection(BlockState state) {
        return state.getValue(FACING).getOpposite();
    }

    @Override
    public BlockState withInputConnection(BlockState state, Direction inputDirection) {
        Direction outputDirection = getOutputDirection(state);
        ConveyorShape nextShape = shapeFromInputAndOutput(inputDirection, outputDirection);
        return state.setValue(SHAPE, nextShape);
    }

    @Override
    public BlockState withOutputConnection(BlockState state, Direction outputDirection) {
        Direction inputDirection = getInputDirection(state);
        ConveyorShape nextShape = shapeFromInputAndOutput(inputDirection, outputDirection);
        return state
                .setValue(FACING, outputDirection.getOpposite())
                .setValue(SHAPE, nextShape);
    }

    private static ConveyorShape shapeFromInputAndOutput(Direction inputDirection, Direction outputDirection) {
        if (outputDirection == inputDirection.getOpposite()) {
            return ConveyorShape.STRAIGHT;
        }
        if (outputDirection == inputDirection.getClockWise()) {
            return ConveyorShape.RIGHT;
        }
        if (outputDirection == inputDirection.getCounterClockWise()) {
            return ConveyorShape.LEFT;
        }
        return ConveyorShape.STRAIGHT;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ConveyorStraightBlockEntity(pos, state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof ConveyorStraightBlockEntity conveyorStraightBlockEntity) {
                conveyorStraightBlockEntity.dropContents(level, pos);
            }
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }
}
