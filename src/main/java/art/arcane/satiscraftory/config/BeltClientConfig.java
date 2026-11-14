package art.arcane.satiscraftory.config;

import art.arcane.satiscraftory.data.route.BeltRouteMode;
import net.minecraftforge.common.ForgeConfigSpec;

public final class BeltClientConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.ConfigValue<String> ROUTE_MODE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ROUTE_MODE = builder.comment("Last selected conveyor placement mode.")
                .define("beltRouteMode", BeltRouteMode.DEFAULT.id());
        SPEC = builder.build();
    }

    private BeltClientConfig() {
    }

    public static BeltRouteMode preferredMode() {
        BeltRouteMode mode = BeltRouteMode.byId(ROUTE_MODE.get());
        return mode == null ? BeltRouteMode.DEFAULT : mode;
    }

    public static void rememberMode(BeltRouteMode mode) {
        ROUTE_MODE.set(mode.id());
        ROUTE_MODE.save();
    }
}
