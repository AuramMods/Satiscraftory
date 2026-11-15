package art.arcane.satiscraftory.client.render;

import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity;
import art.arcane.satiscraftory.client.ConveyorMiningProgress;
import art.arcane.satiscraftory.data.route.BeltRoute;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.world.level.Level;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public class ConveyorRenderer implements BlockEntityRenderer<ConveyorBlockEntity> {
    public ConveyorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ConveyorBlockEntity blockEntity,
                       float partialTick,
                       PoseStack poseStack,
                       MultiBufferSource bufferSource,
                       int packedLight,
                       int packedOverlay) {
        ConveyorModel template = ConveyorModel.get(blockEntity.getBlockState());
        if (template == null || template.faces().isEmpty()) {
            return;
        }

        if (blockEntity.getEndPos() == null) {
            return;
        }

        Level level = blockEntity.getLevel();
        if (level == null) {
            return;
        }

        BeltRoute route = blockEntity.getVisualRoute();

        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();
        int stage = ConveyorMiningProgress.stageFor(blockEntity);
        // Get the fixed crack buffer before the regular belt buffer, which flushes when its type changes.
        VertexConsumer cracks = stage < 0 ? null : new SheetedDecalTextureGenerator(
                bufferSource.getBuffer(ModelBakery.DESTROY_TYPES.get(stage)), pose, normal, 1.0F);
        VertexConsumer cutout = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        VertexConsumer consumer = cracks == null ? cutout : VertexMultiConsumer.create(cutout, cracks);

        new ConveyorMesh(consumer, pose, normal, level, blockEntity.getBlockPos(), packedLight, packedOverlay, route)
                .render(template);
    }

    @Override
    public boolean shouldRenderOffScreen(ConveyorBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
