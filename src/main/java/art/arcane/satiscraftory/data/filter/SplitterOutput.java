package art.arcane.satiscraftory.data.filter;

import net.minecraft.core.Direction;

import javax.annotation.Nullable;

/** Viewed from the input, looking along the direction of item travel. */
public enum SplitterOutput {
    LEFT, CENTER, RIGHT;

    public Direction direction(Direction input) {
        return switch (this) {
            case LEFT -> input.getClockWise();
            case CENTER -> input.getOpposite();
            case RIGHT -> input.getCounterClockWise();
        };
    }

    @Nullable
    public static SplitterOutput fromDirection(Direction input, Direction side) {
        for (SplitterOutput output : values()) {
            if (output.direction(input) == side) {
                return output;
            }
        }
        return null;
    }
}
