package art.arcane.satiscraftory.client;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.interaction.BeltPickBlock;
import art.arcane.satiscraftory.interaction.BeltTargeting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Satiscraftory.MODID, value = Dist.CLIENT)
public final class ConveyorPickBlockInput {
    private ConveyorPickBlockInput() {
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Post event) {
        if (event.getAction() == InputConstants.PRESS
                && Minecraft.getInstance().options.keyPickItem.matchesMouse(event.getButton())) {
            pickBelt();
        }
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() != InputConstants.RELEASE
                && Minecraft.getInstance().options.keyPickItem.matches(event.getKey(), event.getScanCode())) {
            pickBelt();
        }
    }

    private static void pickBelt() {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.gameMode == null || minecraft.screen != null
                || minecraft.getOverlay() != null || !minecraft.mouseHandler.isMouseGrabbed() || minecraft.isPaused()
                || player.isSpectator() || player.isUsingItem()) {
            return;
        }
        float partialTick = minecraft.getFrameTime();
        minecraft.gameRenderer.pick(partialTick);
        var hit = BeltTargeting.nearestBelt(minecraft.level, ConveyorInteractionInput.aim(minecraft, partialTick));
        if (hit == null) {
            return;
        }
        // Vanilla never fires its pick-block interaction hook for a MISS, including belts against air.
        // Post-input events run after the configured key's click has been queued, even for a quick press/release.
        while (minecraft.options.keyPickItem.consumeClick()) {
            if (!ForgeHooksClient.onClickInput(2, minecraft.options.keyPickItem, InteractionHand.MAIN_HAND).isCanceled()) {
                BeltPickBlock.pick(player, hit.belt(), minecraft.gameMode::handlePickItem,
                        () -> minecraft.gameMode.handleCreativeModeItemAdd(player.getInventory().getSelected(),
                                36 + player.getInventory().selected));
            }
        }
    }
}
