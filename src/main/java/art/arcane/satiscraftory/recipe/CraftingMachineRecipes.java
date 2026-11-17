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

/** Resolves actual recipe grids using a bounded number of distinct input stacks. */
public final class CraftingMachineRecipes {
    private CraftingMachineRecipes() {
    }

    public record Input(ItemStack stack, int count) {}
    public record Choice(ResourceLocation id, ItemStack result, List<Input> inputs) {}
    public record Plan(int[] consumed, ItemStack result, List<ItemStack> remainders) {}

    public static List<Choice> find(Level level, ItemStack requested, int maxTypes) {
        if (requested.isEmpty()) {
            return List.of();
        }
        List<Choice> choices = new ArrayList<>();
        for (CraftingRecipe recipe : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if ((recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)
                    && !recipe.getResultItem(level.registryAccess()).is(requested.getItem())) {
                continue;
            }
            var search = new Search(level, recipe, requested, List.of(), maxTypes, true);
            if (search.solve()) {
                List<Input> inputs = new ArrayList<>();
                for (int type = 0; type < search.stacks.size(); type++) {
                    if (search.used[type] > 0) {
                        inputs.add(new Input(search.stacks.get(type).copyWithCount(1), search.used[type]));
                    }
                }
                choices.add(new Choice(recipe.getId(), search.result.copy(), List.copyOf(inputs)));
            }
        }
        choices.sort(Comparator.comparing((Choice choice) -> !choice.id().getNamespace().equals("minecraft"))
                .thenComparing(choice -> choice.id().toString()));
        return List.copyOf(choices);
    }

    /** Tests a partial delivery while reserving enough buffers for all missing ingredient types. */
    public static boolean canComplete(Level level, ResourceLocation id, List<ItemStack> proposed, ItemStack requested) {
        var found = level.getRecipeManager().byKey(id);
        if (found.isEmpty() || !(found.get() instanceof CraftingRecipe recipe)) {
            return false;
        }
        List<ItemStack> fixed = new ArrayList<>();
        int emptySlots = 0;
        for (ItemStack stack : proposed) {
            if (stack.isEmpty()) {
                emptySlots++;
            } else if (fixed.stream().noneMatch(other -> ItemStack.isSameItemSameTags(stack, other))) {
                fixed.add(stack.copyWithCount(9));
            }
        }
        return new Search(level, recipe, requested, fixed, fixed.size() + emptySlots, true).solve();
    }

    public static Optional<Plan> plan(Level level, ResourceLocation id, List<ItemStack> inventory) {
        var found = level.getRecipeManager().byKey(id);
        if (found.isEmpty() || !(found.get() instanceof CraftingRecipe recipe)) {
            return Optional.empty();
        }
        var search = new Search(level, recipe, ItemStack.EMPTY, inventory, inventory.size(), false);
        if (!search.solve()) {
            return Optional.empty();
        }
        List<ItemStack> remainders = recipe.getRemainingItems(search.grid).stream().filter(stack -> !stack.isEmpty())
                .map(ItemStack::copy).toList();
        return Optional.of(new Plan(search.used.clone(), search.result.copy(), remainders));
    }

    /** Maximum quantity of this exact input that can participate in one valid craft. */
    public static int maxInputCount(Level level, ResourceLocation id, ItemStack input, ItemStack requested, int maxTypes) {
        var found = level.getRecipeManager().byKey(id);
        if (found.isEmpty() || !(found.get() instanceof CraftingRecipe recipe) || input.isEmpty()) {
            return 0;
        }
        int maximum = (int) cells(recipe).stream().filter(cell -> cell.ingredient().test(input)).count();
        for (int count = maximum; count > 0; count--) {
            var search = new Search(level, recipe, requested, List.of(input.copyWithCount(9)), maxTypes, true, count);
            if (search.solve()) {
                return count;
            }
        }
        return 0;
    }

    private record Cell(int slot, Ingredient ingredient) {}

    private static List<Cell> cells(CraftingRecipe recipe) {
        // Vanilla's pottery recipe publishes no ingredient metadata.
        if (recipe instanceof DecoratedPotRecipe) {
            Ingredient pottery = Ingredient.of(ItemTags.DECORATED_POT_INGREDIENTS);
            return List.of(new Cell(1, pottery), new Cell(3, pottery), new Cell(5, pottery), new Cell(7, pottery));
        }
        int width = recipe instanceof IShapedRecipe<?> shaped ? shaped.getRecipeWidth() : 3;
        List<Cell> cells = new ArrayList<>();
        var ingredients = recipe.getIngredients();
        if (width < 1 || width > 3 || ingredients.size() > 9) {
            return List.of();
        }
        for (int index = 0; index < ingredients.size(); index++) {
            if (!ingredients.get(index).isEmpty()) {
                int slot = index / width * 3 + index % width;
                if (slot >= 9) {
                    return List.of();
                }
                cells.add(new Cell(slot, ingredients.get(index)));
            }
        }
        return cells;
    }

    private static TransientCraftingContainer emptyGrid() {
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
        return new TransientCraftingContainer(menu, 3, 3);
    }

    private static final class Search {
        private final Level level;
        private final CraftingRecipe recipe;
        private final ItemStack requested;
        private final List<Cell> cells;
        private final List<ItemStack> stacks;
        private final int[] used;
        private final int maxTypes;
        private final int fixedTypes;
        private final boolean allowNewTypes;
        private final int minimumFirstUses;
        private final TransientCraftingContainer grid = emptyGrid();
        private ItemStack result = ItemStack.EMPTY;

        private Search(Level level, CraftingRecipe recipe, ItemStack requested, List<ItemStack> fixed,
                int maxTypes, boolean allowNewTypes) {
            this(level, recipe, requested, fixed, maxTypes, allowNewTypes, 0);
        }

        private Search(Level level, CraftingRecipe recipe, ItemStack requested, List<ItemStack> fixed,
                int maxTypes, boolean allowNewTypes, int minimumFirstUses) {
            this.level = level;
            this.recipe = recipe;
            this.requested = requested;
            this.cells = cells(recipe);
            this.stacks = new ArrayList<>(fixed);
            this.used = new int[maxTypes];
            this.maxTypes = maxTypes;
            this.fixedTypes = fixed.size();
            this.allowNewTypes = allowNewTypes;
            this.minimumFirstUses = minimumFirstUses;
        }

        private boolean solve() {
            return !cells.isEmpty() && recipe.canCraftInDimensions(3, 3) && fill(0);
        }

        private boolean fill(int index) {
            if (minimumFirstUses > 0) {
                int possible = used[0];
                for (int remaining = index; remaining < cells.size(); remaining++) {
                    if (cells.get(remaining).ingredient().test(stacks.get(0))) {
                        possible++;
                    }
                }
                if (possible < minimumFirstUses) {
                    return false;
                }
            }
            if (index == cells.size()) {
                // Partial-input validation must account for every occupied buffer.
                for (int type = 0; allowNewTypes && type < fixedTypes; type++) {
                    if (used[type] == 0) {
                        return false;
                    }
                }
                if (!recipe.matches(grid, level)) {
                    return false;
                }
                ItemStack assembled = recipe.assemble(grid, level.registryAccess());
                if (assembled.isEmpty() || (!requested.isEmpty() && (!assembled.is(requested.getItem())
                        || (requested.hasTag() && !ItemStack.isSameItemSameTags(assembled, requested))))) {
                    return false;
                }
                result = assembled;
                return true;
            }
            Cell cell = cells.get(index);
            for (int type = 0; type < stacks.size(); type++) {
                ItemStack stack = stacks.get(type);
                if (used[type] < stack.getCount() && cell.ingredient().test(stack)) {
                    used[type]++;
                    grid.setItem(cell.slot(), stack.copyWithCount(1));
                    if (fill(index + 1)) {
                        return true;
                    }
                    used[type]--;
                }
            }
            if (allowNewTypes && stacks.size() < maxTypes) {
                for (ItemStack candidate : cell.ingredient().getItems()) {
                    if (stacks.stream().anyMatch(other -> ItemStack.isSameItemSameTags(candidate, other))) {
                        continue;
                    }
                    int type = stacks.size();
                    stacks.add(candidate.copyWithCount(9));
                    used[type] = 1;
                    grid.setItem(cell.slot(), candidate.copyWithCount(1));
                    if (fill(index + 1)) {
                        return true;
                    }
                    stacks.remove(type);
                    used[type] = 0;
                }
            }
            grid.setItem(cell.slot(), ItemStack.EMPTY);
            return false;
        }
    }
}
