package art.arcane.satiscraftory.client.render;

import art.arcane.satiscraftory.block.ConveyorBlock;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A baked belt profile loaded from the tier's block model. */
record ConveyorModel(List<ConveyorModel.ModelFace> faces) {
    enum AlongAxis {
        U,
        V
    }

    record UvPixel(float u, float v) {
    }

    record FaceParams(float s, float t) {
    }

    record ModelFace(
            Direction direction,
            TextureAtlasSprite sprite,
            boolean emissive,
            float x1,
            float x2,
            float y1,
            float y2,
            float z1,
            float z2,
            float u1,
            float v1,
            float u2,
            float v2,
            int rotation,
            boolean repeatEnabled,
            AlongAxis repeatAxis,
            float repeatStart,
            float repeatMin,
            float repeatPixelsPerBlock,
            float repeatWrapRange
    ) {
    }

    private record TextureReference(ResourceLocation textureLocation, boolean emissive) {
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    static final float EPSILON = 1.0E-6F;

    private static final ResourceLocation DEFAULT_SOURCE_MODEL_JSON = ResourceLocation.fromNamespaceAndPath(
            "satiscraftory", "models/block/conveyor_1.json"
    );

    private static final Map<ResourceLocation, ConveyorModel> cachedTemplates = new HashMap<>();
    private static final Set<ResourceLocation> failedTemplates = new HashSet<>();
    @Nullable
    private static ResourceManager cachedResourceManager;

    private static ResourceLocation getSourceModelForState(BlockState state) {
        if (state.getBlock() instanceof ConveyorBlock conveyorBlock) {
            return conveyorBlock.getSourceModelJson();
        }
        return DEFAULT_SOURCE_MODEL_JSON;
    }

    @Nullable
    static ConveyorModel get(BlockState state) {
        ResourceLocation sourceModelJson = getSourceModelForState(state);
        Minecraft minecraft = Minecraft.getInstance();
        ResourceManager resourceManager = minecraft.getResourceManager();

        if (resourceManager != cachedResourceManager) {
            cachedResourceManager = resourceManager;
            cachedTemplates.clear();
            failedTemplates.clear();
        }

        if (failedTemplates.contains(sourceModelJson)) {
            return null;
        }

        ConveyorModel cachedTemplate = cachedTemplates.get(sourceModelJson);
        if (cachedTemplate != null) {
            return cachedTemplate;
        }

        ConveyorModel loaded = loadTemplate(minecraft, resourceManager, sourceModelJson);
        if (loaded == null) {
            failedTemplates.add(sourceModelJson);
            return null;
        }

        cachedTemplates.put(sourceModelJson, loaded);
        return loaded;
    }

    @Nullable
    private static ConveyorModel loadTemplate(Minecraft minecraft,
                                                    ResourceManager resourceManager,
                                                    ResourceLocation sourceModelJson) {
        Optional<Resource> modelResource = resourceManager.getResource(sourceModelJson);
        if (modelResource.isEmpty()) {
            LOGGER.warn("Spline source model not found: {}", sourceModelJson);
            return null;
        }

        TextureAtlas atlas = minecraft.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);

        try (Reader reader = modelResource.get().openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            Map<String, String> textures = new HashMap<>();
            JsonObject texturesObject = root.has("textures") && root.get("textures").isJsonObject()
                    ? root.getAsJsonObject("textures")
                    : null;
            if (texturesObject != null) {
                for (Map.Entry<String, JsonElement> entry : texturesObject.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        textures.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }

            JsonArray elements = root.has("elements") && root.get("elements").isJsonArray()
                    ? root.getAsJsonArray("elements")
                    : null;
            if (elements == null || elements.isEmpty()) {
                LOGGER.warn("Spline source model has no elements: {}", sourceModelJson);
                return null;
            }

            List<ModelFace> faces = new ArrayList<>();

            for (JsonElement elementValue : elements) {
                if (!elementValue.isJsonObject()) {
                    continue;
                }

                JsonObject element = elementValue.getAsJsonObject();
                if (!element.has("from") || !element.has("to") || !element.has("faces")) {
                    continue;
                }

                float[] from = readVec3(element.getAsJsonArray("from"));
                float[] to = readVec3(element.getAsJsonArray("to"));
                if (from == null || to == null) {
                    continue;
                }

                float x1 = from[0] / 16.0F;
                float y1 = from[1] / 16.0F;
                float z1 = from[2] / 16.0F;
                float x2 = to[0] / 16.0F;
                float y2 = to[1] / 16.0F;
                float z2 = to[2] / 16.0F;

                JsonObject facesObject = element.getAsJsonObject("faces");
                for (Map.Entry<String, JsonElement> faceEntry : facesObject.entrySet()) {
                    Direction direction = Direction.byName(faceEntry.getKey());
                    if (direction == null || !faceEntry.getValue().isJsonObject()) {
                        continue;
                    }

                    JsonObject faceJson = faceEntry.getValue().getAsJsonObject();
                    if (!faceJson.has("texture") || !faceJson.get("texture").isJsonPrimitive()) {
                        continue;
                    }

                    String textureRef = faceJson.get("texture").getAsString();
                    TextureReference textureReference = resolveTextureReference(textures, textureRef, sourceModelJson.getNamespace());
                    if (textureReference == null) {
                        continue;
                    }

                    float[] uv = readUv(faceJson.getAsJsonArray("uv"));
                    if (uv == null) {
                        continue;
                    }

                    int rotation = faceJson.has("rotation") && faceJson.get("rotation").isJsonPrimitive()
                            ? faceJson.get("rotation").getAsInt()
                            : 0;

                    TextureAtlasSprite sprite = atlas.getSprite(textureReference.textureLocation());
                    faces.add(buildFace(
                            direction,
                            sprite,
                            textureReference.emissive(),
                            x1,
                            x2,
                            y1,
                            y2,
                            z1,
                            z2,
                            uv[0],
                            uv[1],
                            uv[2],
                            uv[3],
                            rotation
                    ));
                }
            }

            return new ConveyorModel(faces);
        } catch (Exception e) {
            LOGGER.error("Failed to load spline source model {}", sourceModelJson, e);
            return null;
        }
    }

    private static ModelFace buildFace(Direction direction,
                                       TextureAtlasSprite sprite,
                                       boolean emissive,
                                       float x1,
                                       float x2,
                                       float y1,
                                       float y2,
                                       float z1,
                                       float z2,
                                       float u1,
                                       float v1,
                                       float u2,
                                       float v2,
                                       int rotation) {
        boolean repeatEnabled = false;
        AlongAxis repeatAxis = AlongAxis.U;
        float repeatStart = 0.0F;
        float repeatMin = 0.0F;
        float repeatPixelsPerBlock = 0.0F;
        float repeatWrapRange = 0.0F;

        float zSpan = z2 - z1;
        if (zSpan > EPSILON && direction != Direction.NORTH && direction != Direction.SOUTH) {
            float centerX = (x1 + x2) * 0.5F;
            float centerY = (y1 + y2) * 0.5F;

            UvPixel uvStart = uvFromLocal(direction, x1, x2, y1, y2, z1, z2, u1, v1, u2, v2, rotation, centerX, centerY, z1);
            UvPixel uvEnd = uvFromLocal(direction, x1, x2, y1, y2, z1, z2, u1, v1, u2, v2, rotation, centerX, centerY, z2);

            float du = uvEnd.u() - uvStart.u();
            float dv = uvEnd.v() - uvStart.v();

            if (Math.abs(du) >= Math.abs(dv)) {
                repeatAxis = AlongAxis.U;
                repeatStart = uvStart.u();
                repeatMin = Math.min(uvStart.u(), uvEnd.u());
                repeatWrapRange = Math.abs(du);
                repeatPixelsPerBlock = du / zSpan;
            } else {
                repeatAxis = AlongAxis.V;
                repeatStart = uvStart.v();
                repeatMin = Math.min(uvStart.v(), uvEnd.v());
                repeatWrapRange = Math.abs(dv);
                repeatPixelsPerBlock = dv / zSpan;
            }

            repeatEnabled = repeatWrapRange > EPSILON && Math.abs(repeatPixelsPerBlock) > EPSILON;
        }

        return new ModelFace(
                direction,
                sprite,
                emissive,
                x1,
                x2,
                y1,
                y2,
                z1,
                z2,
                u1,
                v1,
                u2,
                v2,
                rotation,
                repeatEnabled,
                repeatAxis,
                repeatStart,
                repeatMin,
                repeatPixelsPerBlock,
                repeatWrapRange
        );
    }

    private static UvPixel uvFromLocal(Direction direction,
                                       float x1,
                                       float x2,
                                       float y1,
                                       float y2,
                                       float z1,
                                       float z2,
                                       float u1,
                                       float v1,
                                       float u2,
                                       float v2,
                                       int rotation,
                                       float x,
                                       float y,
                                       float z) {
        FaceParams params = faceParams(direction, x1, x2, y1, y2, z1, z2, x, y, z);
        FaceParams rotated = rotateParams(params.s(), params.t(), rotation);
        return new UvPixel(
                lerp(u1, u2, rotated.s()),
                lerp(v1, v2, rotated.t())
        );
    }

    private static FaceParams faceParams(Direction direction,
                                         float x1,
                                         float x2,
                                         float y1,
                                         float y2,
                                         float z1,
                                         float z2,
                                         float x,
                                         float y,
                                         float z) {
        float s;
        float t;

        switch (direction) {
            case NORTH -> {
                s = invLerp(x1, x2, x);
                t = invLerp(y2, y1, y);
            }
            case SOUTH -> {
                s = invLerp(x2, x1, x);
                t = invLerp(y2, y1, y);
            }
            case WEST -> {
                s = invLerp(z2, z1, z);
                t = invLerp(y2, y1, y);
            }
            case EAST -> {
                s = invLerp(z1, z2, z);
                t = invLerp(y2, y1, y);
            }
            case UP -> {
                s = invLerp(x2, x1, x);
                t = invLerp(z2, z1, z);
            }
            case DOWN -> {
                s = invLerp(x2, x1, x);
                t = invLerp(z1, z2, z);
            }
            default -> {
                s = 0.0F;
                t = 0.0F;
            }
        }

        return new FaceParams(clamp01(s), clamp01(t));
    }

    @Nullable
    private static TextureReference resolveTextureReference(Map<String, String> textures,
                                                            String reference,
                                                            String defaultNamespace) {
        String value = reference;
        Set<String> seen = new HashSet<>();
        boolean emissive = false;

        while (value.startsWith("#")) {
            String key = value.substring(1);
            if (!seen.add(key)) {
                return null;
            }
            if (key.endsWith("_emissive")) {
                emissive = true;
            }

            value = textures.get(key);
            if (value == null || value.isBlank()) {
                return null;
            }
        }

        if (value.isBlank()) {
            return null;
        }

        String namespaced = value.contains(":") ? value : (defaultNamespace + ":" + value);
        ResourceLocation textureLocation = ResourceLocation.tryParse(namespaced);
        if (textureLocation == null) {
            return null;
        }
        if (textureLocation.getPath().endsWith("_emissive")) {
            emissive = true;
        }
        return new TextureReference(textureLocation, emissive);
    }

    @Nullable
    private static float[] readVec3(@Nullable JsonArray array) {
        if (array == null || array.size() != 3) {
            return null;
        }
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    @Nullable
    private static float[] readUv(@Nullable JsonArray array) {
        if (array == null || array.size() != 4) {
            return null;
        }
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat(), array.get(3).getAsFloat()};
    }

    static FaceParams rotateParams(float s, float t, int rotation) {
        return switch ((rotation % 360 + 360) % 360) {
            case 90 -> new FaceParams(t, 1.0F - s);
            case 180 -> new FaceParams(1.0F - s, 1.0F - t);
            case 270 -> new FaceParams(1.0F - t, s);
            default -> new FaceParams(s, t);
        };
    }

    static float invLerp(float a, float b, float value) {
        float delta = b - a;
        if (Math.abs(delta) < EPSILON) {
            return 0.0F;
        }
        return (value - a) / delta;
    }

    static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    static float lerp(float a, float b, float t) {
        return a + ((b - a) * t);
    }
}
