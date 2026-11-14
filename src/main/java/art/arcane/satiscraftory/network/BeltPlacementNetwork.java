package art.arcane.satiscraftory.network;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.data.route.BeltRouteMode;
import art.arcane.satiscraftory.item.ConveyorItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class BeltPlacementNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(Satiscraftory.MODID, "belt_placement"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private BeltPlacementNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(SelectRouteMode.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((message, buffer) -> buffer.writeUtf(message.modeId(), 32))
                .decoder(buffer -> new SelectRouteMode(buffer.readUtf(32)))
                .consumerMainThread((message, context) -> {
                    ServerPlayer player = context.get().getSender();
                    BeltRouteMode mode = BeltRouteMode.byId(message.modeId());
                    if (player != null && mode != null) {
                        ConveyorItem.selectRouteMode(player, mode);
                    }
                })
                .add();
    }

    public static void selectRouteMode(BeltRouteMode mode) {
        CHANNEL.sendToServer(new SelectRouteMode(mode.id()));
    }

    private record SelectRouteMode(String modeId) {
    }
}
