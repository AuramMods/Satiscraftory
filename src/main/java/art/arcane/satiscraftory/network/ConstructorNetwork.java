package art.arcane.satiscraftory.network;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.menu.ConstructorMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ConstructorNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(Satiscraftory.MODID, "constructor"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private ConstructorNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(Template.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((message, buffer) -> {
                    buffer.writeVarInt(message.menuId());
                    buffer.writeItem(message.stack());
                })
                .decoder(buffer -> new Template(buffer.readVarInt(), buffer.readItem()))
                .consumerMainThread((message, context) -> {
                    var player = context.get().getSender();
                    if (player != null && player.containerMenu instanceof ConstructorMenu menu
                            && menu.containerId == message.menuId() && menu.stillValid(player)) {
                        menu.constructor().setTemplate(message.stack());
                    }
                }).add();
    }

    public static void setTemplate(int menuId, ItemStack stack) {
        CHANNEL.sendToServer(new Template(menuId, stack.copyWithCount(1)));
    }

    private record Template(int menuId, ItemStack stack) {}
}
