package art.arcane.satiscraftory.client.render;

import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity.SplineVisualItemEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public final class ConveyorItemRenderer extends EntityRenderer<SplineVisualItemEntity> {
    private final ItemRenderer itemRenderer;

    public ConveyorItemRenderer(EntityRendererProvider.Context context) {
        super(context);
        itemRenderer = context.getItemRenderer();
        shadowRadius = 0.15F;
        shadowStrength = 0.75F;
    }

    @Override
    public Vec3 getRenderOffset(SplineVisualItemEntity visual, float partialTick) {
        // Interpolate distance along the belt so a frame between ticks never cuts across a corner.
        return visual.positionOnBelt(partialTick).subtract(visual.getPosition(partialTick));
    }

    @Override
    public void render(SplineVisualItemEntity visual, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight) {
        ItemStack stack = visual.getItem();
        BakedModel model = itemRenderer.getModel(stack, visual.level(), null, visual.getId());

        ItemTransform ground = model.getTransforms().getTransform(ItemDisplayContext.GROUND);
        Vector3f offset = ground.translation;
        float height = 0.1F + 0.25F * ground.scale.y();
        Vec3 pivot = visual.positionOnBelt(partialTick).add(offset.x(), height + offset.y(), offset.z());
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        visual.turnToward(camera.subtract(pivot), Util.getNanos());

        poseStack.pushPose();
        poseStack.translate(offset.x(), height + offset.y(), offset.z());
        poseStack.mulPose(Axis.YP.rotationDegrees(visual.getFacingYaw()));
        poseStack.mulPose(Axis.XP.rotationDegrees(visual.getFacingPitch()));
        // ItemRenderer applies the ground translation itself. Keep the pivot over the belt.
        poseStack.translate(-offset.x(), -offset.y(), -offset.z());
        itemRenderer.render(stack, ItemDisplayContext.GROUND, false, poseStack, buffers,
                packedLight, OverlayTexture.NO_OVERLAY, model);
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(SplineVisualItemEntity visual) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
