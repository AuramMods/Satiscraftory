package art.arcane.satiscraftory.client.render;

import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity.SplineVisualItemEntity;
import art.arcane.satiscraftory.interaction.BeltTargeting;
import com.mojang.blaze3d.vertex.DefaultedVertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.WeakHashMap;

public final class ConveyorItemRenderer extends EntityRenderer<SplineVisualItemEntity> {
    private static final double CONTACT_CLEARANCE = 1.0D / 1024.0D;
    private static final double MAX_CARGO_FOOTPRINT = 0.5D;
    private static final double SLOT_FOOTPRINT_FRACTION = 0.75D;
    private static final double PICK_PADDING = 1.0D / 64.0D;
    private static final Map<SplineVisualItemEntity, CachedModel> RESTING_MODELS = new WeakHashMap<>();

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
        var location = visual.locationOnBelt(partialTick);
        RestingModel resting = restingModel(itemRenderer, visual, stack, model).fitToSlot(location.slotLength());

        poseStack.pushPose();
        poseStack.mulPose(beltRotation(location.direction()));
        poseStack.translate(resting.offset.x, resting.offset.y, resting.offset.z);
        poseStack.scale(resting.scale, resting.scale, resting.scale);
        poseStack.mulPose(resting.rotation);
        itemRenderer.render(stack, ItemDisplayContext.GROUND, false, poseStack, buffers,
                packedLight, OverlayTexture.NO_OVERLAY, model);
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(SplineVisualItemEntity visual) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    @Nullable
    public static Vec3 clip(SplineVisualItemEntity visual, BeltTargeting.Ray ray, float partialTick) {
        ItemRenderer renderer = Minecraft.getInstance().getItemRenderer();
        ItemStack stack = visual.getItem();
        BakedModel model = renderer.getModel(stack, visual.level(), null, visual.getId());
        var location = visual.locationOnBelt(partialTick);
        RestingModel resting = restingModel(renderer, visual, stack, model).fitToSlot(location.slotLength());
        Vec3 origin = location.position();
        Quaternionf rotation = beltRotation(location.direction());
        Quaternionf inverse = new Quaternionf(rotation).conjugate();
        Vec3 from = rotate(ray.from().subtract(origin), inverse);
        Vec3 to = rotate(ray.to().subtract(origin), inverse);
        AABB bounds = resting.bounds.inflate(PICK_PADDING);
        if (bounds.contains(from)) {
            return ray.from();
        }
        return bounds.clip(from, to).map(hit -> origin.add(rotate(hit, rotation))).orElse(null);
    }

    private static Quaternionf beltRotation(Vec3 direction) {
        float yaw = (float) Math.atan2(direction.x, direction.z);
        float pitch = (float) -Math.atan2(direction.y, Math.hypot(direction.x, direction.z));
        return new Quaternionf().rotationY(yaw).rotateX(pitch);
    }

    private static Vec3 rotate(Vec3 point, Quaternionf rotation) {
        Vector3f rotated = rotation.transform((float) point.x, (float) point.y, (float) point.z, new Vector3f());
        return new Vec3(rotated.x, rotated.y, rotated.z);
    }

    private static RestingModel restingModel(ItemRenderer renderer, SplineVisualItemEntity visual,
                                             ItemStack stack, BakedModel model) {
        CachedModel cached = RESTING_MODELS.get(visual);
        if (cached != null && cached.model == model && cached.stack == stack) {
            return cached.resting;
        }

        // Measure the same render passes and ground transform used to draw the item, including custom models.
        ModelBounds measured = new ModelBounds();
        renderer.render(stack, ItemDisplayContext.GROUND, false, new PoseStack(), type -> measured.consumer(),
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, model);
        RestingModel resting = restOnDeck(measured.bounds(), stack.getItem() instanceof BlockItem && model.isGui3d());
        RESTING_MODELS.put(visual, new CachedModel(stack, model, resting));
        return resting;
    }

    private static RestingModel restOnDeck(AABB bounds, boolean keepBlockBase) {
        Quaternionf rotation = new Quaternionf();
        double width = bounds.getXsize();
        double depth = bounds.getZsize();
        // A model rests on the plane perpendicular to its thinnest axis. Cube ties keep the normal base.
        if (!keepBlockBase && Math.min(width, depth) + 1.0E-4D < bounds.getYsize()) {
            if (depth <= width) {
                rotation = Axis.XN.rotationDegrees(90.0F);
                bounds = new AABB(bounds.minX, bounds.minZ, -bounds.maxY, bounds.maxX, bounds.maxZ, -bounds.minY);
            } else {
                rotation = Axis.ZP.rotationDegrees(90.0F);
                bounds = new AABB(-bounds.maxY, bounds.minX, bounds.minZ, -bounds.minY, bounds.maxX, bounds.maxZ);
            }
        }

        Vec3 center = bounds.getCenter();
        Vec3 offset = new Vec3(-center.x, CONTACT_CLEARANCE - bounds.minY, -center.z);
        return new RestingModel(rotation, offset, bounds.move(offset), 1.0F);
    }

    private record CachedModel(ItemStack stack, BakedModel model, RestingModel resting) {}

    private record RestingModel(Quaternionf rotation, Vec3 offset, AABB bounds, float scale) {
        RestingModel fitToSlot(double slotLength) {
            // Leave room for corners, including on over-capacity belts that are still draining old slots.
            double footprint = Math.min(MAX_CARGO_FOOTPRINT, slotLength * SLOT_FOOTPRINT_FRACTION);
            double size = Math.max(bounds.getXsize(), bounds.getZsize());
            if (size <= footprint) {
                return this;
            }

            float fit = (float) (footprint / size);
            double lift = CONTACT_CLEARANCE * (1.0D - fit);
            AABB fittedBounds = new AABB(bounds.minX * fit, bounds.minY * fit + lift, bounds.minZ * fit,
                    bounds.maxX * fit, bounds.maxY * fit + lift, bounds.maxZ * fit);
            return new RestingModel(rotation, offset.scale(fit).add(0.0D, lift, 0.0D), fittedBounds, fit);
        }
    }

    private static final class ModelBounds {
        private final Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        private final Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);

        private AABB bounds() {
            return Float.isFinite(min.x) ? new AABB(min.x, min.y, min.z, max.x, max.y, max.z)
                    : new AABB(-0.125D, -0.125D, -0.125D, 0.125D, 0.125D, 0.125D);
        }

        private VertexConsumer consumer() {
            return new DefaultedVertexConsumer() {
                @Override
                public VertexConsumer vertex(double x, double y, double z) {
                    min.set(Math.min(min.x, (float) x), Math.min(min.y, (float) y), Math.min(min.z, (float) z));
                    max.set(Math.max(max.x, (float) x), Math.max(max.y, (float) y), Math.max(max.z, (float) z));
                    return this;
                }

                @Override
                public VertexConsumer color(int red, int green, int blue, int alpha) {
                    return this;
                }

                @Override
                public VertexConsumer uv(float u, float v) {
                    return this;
                }

                @Override
                public VertexConsumer overlayCoords(int u, int v) {
                    return this;
                }

                @Override
                public VertexConsumer uv2(int u, int v) {
                    return this;
                }

                @Override
                public VertexConsumer normal(float x, float y, float z) {
                    return this;
                }

                @Override
                public void endVertex() {}
            };
        }
    }
}
