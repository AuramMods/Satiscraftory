package art.arcane.satiscraftory.client;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.client.render.ConveyorRenderer;
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
        event.registerBlockEntityRenderer(
                Satiscraftory.CONVEYOR_BLOCK_ENTITY.get(),
                ConveyorRenderer::new
        );
    }
}
