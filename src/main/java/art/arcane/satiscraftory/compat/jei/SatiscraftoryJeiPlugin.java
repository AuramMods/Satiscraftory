package art.arcane.satiscraftory.compat.jei;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.client.ConstructorScreen;
import art.arcane.satiscraftory.client.AssemblerScreen;
import art.arcane.satiscraftory.client.CraftingMachineScreen;
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
    }

    private static <T extends CraftingMachineScreen<?>> void registerGhostHandler(IGuiHandlerRegistration registration, Class<T> type) {
        registration.addGhostIngredientHandler(type, new IGhostIngredientHandler<T>() {
            @Override
            public <I> List<Target<I>> getTargetsTyped(T screen, ITypedIngredient<I> ingredient, boolean doStart) {
                var stack = ingredient.getItemStack();
                if (stack.isEmpty()) {
                    return List.of();
                }
                return List.of(new Target<>() {
                    public Rect2i getArea() {
                        return screen.recipeArea();
                    }
                    public void accept(I ignored) {
                        screen.setRecipeTemplate(stack.get());
                    }
                });
            }

            @Override
            public void onComplete() {}
        });
    }
}
