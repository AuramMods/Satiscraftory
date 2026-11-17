package art.arcane.satiscraftory.recipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.DecoratedPotRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.IShapedRecipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Finds crafting recipes that can be filled entirely from one homogeneous input stack. */
public final class ConstructorRecipes {
    private ConstructorRecipes() {
    }

    public record Choice(ResourceLocation id, int count, ItemStack result, List<ItemStack> inputs) {}

    public record Plan(int count, ItemStack result, List<ItemStack> remainders) {}

    public static List<Choice> find(Level level, ItemStack requested) {
        if (requested.isEmpty()) {
            return List.of();
        }
        List<Choice> choices = new ArrayList<>();
        for (CraftingRecipe recipe : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if ((recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)
                    && !recipe.getResultItem(level.registryAccess()).is(requested.getItem())) {
                continue;
            }
            Layout layout = layout(recipe);
            List<Ingredient> ingredients = layout.ingredients().stream().filter(ingredient -> !ingredient.isEmpty()).toList();
            if (ingredients.isEmpty() || ingredients.size() > 9 || !recipe.canCraftInDimensions(3, 3)) {
                continue;
            }
            List<ItemStack> inputs = new ArrayList<>();
            ItemStack result = ItemStack.EMPTY;
            for (ItemStack candidate : ingredients.get(0).getItems()) {
                if (!ingredients.stream().allMatch(ingredient -> ingredient.test(candidate))) {
                    continue;
                }
                var grid = grid(layout, candidate);
                if (!recipe.matches(grid, level)) {
                    continue;
                }
                ItemStack assembled = recipe.assemble(grid, level.registryAccess());
                if (!assembled.is(requested.getItem()) || (requested.hasTag() && !ItemStack.isSameItemSameTags(assembled, requested))) {
                    continue;
                }
                inputs.add(candidate.copyWithCount(1));
                result = assembled;
            }
            if (!inputs.isEmpty()) {
                choices.add(new Choice(recipe.getId(), ingredients.size(), result.copy(), List.copyOf(inputs)));
            }
        }
        choices.sort(Comparator.comparing((Choice choice) -> !choice.id().getNamespace().equals("minecraft"))
                .thenComparing(choice -> choice.id().toString()));
        return List.copyOf(choices);
    }

    public static Optional<Plan> plan(Level level, ResourceLocation id, ItemStack input) {
        var found = level.getRecipeManager().byKey(id);
        if (found.isEmpty() || !(found.get() instanceof CraftingRecipe recipe)) {
            return Optional.empty();
        }
        Layout layout = layout(recipe);
        long count = layout.ingredients().stream().filter(ingredient -> !ingredient.isEmpty()).count();
        if (count < 1 || count > 9 || input.getCount() < count || !recipe.canCraftInDimensions(3, 3)) {
            return Optional.empty();
        }
        var grid = grid(layout, input);
        if (!recipe.matches(grid, level)) {
            return Optional.empty();
        }
        ItemStack result = recipe.assemble(grid, level.registryAccess());
        if (result.isEmpty()) {
            return Optional.empty();
        }
        List<ItemStack> remainders = recipe.getRemainingItems(grid).stream().filter(stack -> !stack.isEmpty())
                .map(ItemStack::copy).toList();
        return Optional.of(new Plan((int) count, result.copy(), remainders));
    }

    private record Layout(List<Ingredient> ingredients, int width) {}

    private static Layout layout(CraftingRecipe recipe) {
        // This vanilla special recipe does not expose its ingredients through the recipe API.
        if (recipe instanceof DecoratedPotRecipe) {
            Ingredient pottery = Ingredient.of(ItemTags.DECORATED_POT_INGREDIENTS);
            return new Layout(List.of(Ingredient.EMPTY, pottery, Ingredient.EMPTY, pottery, Ingredient.EMPTY,
                    pottery, Ingredient.EMPTY, pottery, Ingredient.EMPTY), 3);
        }
        int width = recipe instanceof IShapedRecipe<?> shaped ? shaped.getRecipeWidth() : 3;
        return new Layout(recipe.getIngredients(), width);
    }

    private static TransientCraftingContainer grid(Layout layout, ItemStack input) {
        var menu = new AbstractContainerMenu(null, -1) {
            @Override
            public ItemStack quickMoveStack(Player player, int slot) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(Player player) {
                return true;
            }
        };
        var grid = new TransientCraftingContainer(menu, 3, 3);
        var ingredients = layout.ingredients();
        int width = layout.width();
        for (int i = 0; i < ingredients.size(); i++) {
            if (!ingredients.get(i).isEmpty()) {
                grid.setItem(i / width * 3 + i % width, input.copyWithCount(1));
            }
        }
        return grid;
    }
}
