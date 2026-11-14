package art.arcane.satiscraftory.client;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity.SplineVisualItemEntity;
import art.arcane.satiscraftory.client.render.ConveyorItemRenderer;
import art.arcane.satiscraftory.client.render.ConveyorRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Satiscraftory.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SatiscraftoryClient {
    private SatiscraftoryClient() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Satiscraftory.CONVEYOR_ITEM_VISUAL.get(), ConveyorItemRenderer::new);
        event.registerBlockEntityRenderer(
                Satiscraftory.CONVEYOR_BLOCK_ENTITY.get(),
                ConveyorRenderer::new
        );
    }

    public static boolean addConveyorItemVisual(SplineVisualItemEntity visual) {
        ClientLevel level = (ClientLevel) visual.level();
        // Keep local visual IDs separate from positive IDs assigned by the server.
        visual.setId(-visual.getId());
        level.putNonPlayerEntity(visual.getId(), visual);
        return level.getEntity(visual.getId()) == visual;
    }
}
