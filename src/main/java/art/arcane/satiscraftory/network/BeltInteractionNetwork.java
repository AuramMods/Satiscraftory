package art.arcane.satiscraftory.network;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.interaction.BeltInteractions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class BeltInteractionNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(Satiscraftory.MODID, "belt_interaction"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private BeltInteractionNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(Pickup.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((message, buffer) -> buffer.writeBlockPos(message.anchor()).writeLong(message.itemId()))
                .decoder(buffer -> new Pickup(buffer.readBlockPos(), buffer.readLong()))
                .consumerMainThread((message, context) -> {
                    var player = context.get().getSender();
                    if (player != null) {
                        BeltInteractions.pickup(player, message.anchor(), message.itemId());
                    }
                })
                .add();
        CHANNEL.messageBuilder(Dig.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder((message, buffer) -> buffer.writeBlockPos(message.anchor()).writeBoolean(message.held()))
                .decoder(buffer -> new Dig(buffer.readBlockPos(), buffer.readBoolean()))
                .consumerMainThread((message, context) -> {
                    var player = context.get().getSender();
                    if (player != null) {
                        BeltInteractions.dig(player, message.anchor(), message.held());
                    }
                })
                .add();
    }

    public static void pickup(BlockPos anchor, long itemId) {
        CHANNEL.sendToServer(new Pickup(anchor, itemId));
    }

    public static void dig(BlockPos anchor, boolean held) {
        CHANNEL.sendToServer(new Dig(anchor, held));
    }

    private record Pickup(BlockPos anchor, long itemId) {}

    private record Dig(BlockPos anchor, boolean held) {}
}
