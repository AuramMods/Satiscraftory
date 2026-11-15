package art.arcane.satiscraftory.network;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.client.ConveyorMiningProgress;
import art.arcane.satiscraftory.interaction.BeltInteractions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class BeltInteractionNetwork {
    private static final String PROTOCOL = "2";
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
        CHANNEL.messageBuilder(Progress.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((message, buffer) -> buffer.writeBlockPos(message.anchor())
                        .writeVarInt(message.breakerId()).writeByte(message.stage()))
                .decoder(buffer -> new Progress(buffer.readBlockPos(), buffer.readVarInt(), buffer.readByte()))
                .consumerMainThread((message, context) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ConveyorMiningProgress.update(message.anchor(), message.breakerId(), message.stage())))
                .add();
    }

    public static void pickup(BlockPos anchor, long itemId) {
        CHANNEL.sendToServer(new Pickup(anchor, itemId));
    }

    public static void dig(BlockPos anchor, boolean held) {
        CHANNEL.sendToServer(new Dig(anchor, held));
    }

    public static void progress(ServerLevel level, BlockPos anchor, int breakerId, int stage) {
        var chunk = level.getChunkSource().getChunkNow(anchor.getX() >> 4, anchor.getZ() >> 4);
        if (chunk != null) {
            CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), new Progress(anchor, breakerId, stage));
        }
    }

    private record Pickup(BlockPos anchor, long itemId) {}

    private record Dig(BlockPos anchor, boolean held) {}

    private record Progress(BlockPos anchor, int breakerId, int stage) {}
}
