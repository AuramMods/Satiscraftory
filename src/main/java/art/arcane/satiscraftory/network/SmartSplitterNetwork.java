package art.arcane.satiscraftory.network;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.data.filter.SplitterOutput;
import art.arcane.satiscraftory.menu.SmartSplitterMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class SmartSplitterNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(Satiscraftory.MODID, "smart_splitter"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private SmartSplitterNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(Filter.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((message, buffer) -> {
                    buffer.writeVarInt(message.menuId());
                    buffer.writeEnum(message.output());
                    buffer.writeVarInt(message.slot());
                    buffer.writeItem(message.stack());
                })
                .decoder(buffer -> new Filter(buffer.readVarInt(), buffer.readEnum(SplitterOutput.class),
                        buffer.readVarInt(), buffer.readItem()))
                .consumerMainThread((message, context) -> {
                    var player = context.get().getSender();
                    if (player != null && player.containerMenu instanceof SmartSplitterMenu menu
                            && menu.containerId == message.menuId() && menu.stillValid(player)) {
                        menu.setFilter(player, message.output(), message.slot(), message.stack());
                    }
                }).add();
    }

    public static void setFilter(int menuId, SplitterOutput output, int slot, ItemStack stack) {
        CHANNEL.sendToServer(new Filter(menuId, output, slot, stack.copyWithCount(1)));
    }

    private record Filter(int menuId, SplitterOutput output, int slot, ItemStack stack) {}
}
