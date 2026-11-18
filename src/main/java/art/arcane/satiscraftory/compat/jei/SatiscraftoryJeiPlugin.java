package art.arcane.satiscraftory.compat.jei;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.client.ConstructorScreen;
import art.arcane.satiscraftory.client.AssemblerScreen;
import art.arcane.satiscraftory.client.ManufacturerScreen;
import art.arcane.satiscraftory.client.GhostItemScreen;
import art.arcane.satiscraftory.client.SmartSplitterScreen;
import net.minecraft.client.gui.screens.Screen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

@JeiPlugin
public final class SatiscraftoryJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Satiscraftory.MODID, "constructor");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registerGhostHandler(registration, ConstructorScreen.class);
        registerGhostHandler(registration, AssemblerScreen.class);
        registerGhostHandler(registration, ManufacturerScreen.class);
        registerGhostHandler(registration, SmartSplitterScreen.class);
    }

    private static <T extends Screen & GhostItemScreen> void registerGhostHandler(IGuiHandlerRegistration registration, Class<T> type) {
        registration.addGhostIngredientHandler(type, new IGhostIngredientHandler<T>() {
            @Override
            public <I> List<Target<I>> getTargetsTyped(T screen, ITypedIngredient<I> ingredient, boolean doStart) {
                var stack = ingredient.getItemStack();
                if (stack.isEmpty()) {
                    return List.of();
                }
                List<Target<I>> targets = new java.util.ArrayList<>();
                for (var target : screen.ghostTargets()) {
                    targets.add(new Target<>() {
                        @Override
                        public Rect2i getArea() {
                            return target.area();
                        }

                        @Override
                        public void accept(I ignored) {
                            target.accept(stack.get());
                        }
                    });
                }
                return targets;
            }

            @Override
            public void onComplete() {}
        });
    }
}
