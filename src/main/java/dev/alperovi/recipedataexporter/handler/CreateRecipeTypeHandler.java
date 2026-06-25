package dev.alperovi.recipedataexporter.handler;

import dev.alperovi.recipedataexporter.model.RecipeTypeExport;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Recipe handler for Create (and Create addon) recipe types.
 * Provides fixed item/fluid IO counts per recipe type, converted into [width, height]
 * dimensions capped at a maximum width.
 */
public class CreateRecipeTypeHandler extends RecipeTypeHandler {

    private static final int MAX_WIDTH = 3;

    /** Recipe type id of Create's mechanical crafting recipes. */
    private static final String MECHANICAL_CRAFTING_TYPE_ID = "create:mechanical_crafting";

    /**
     * The recipe category currently being converted. Captured so the dimension
     * methods (which only receive the recipe type uid) can look up the category's
     * recipes when dimensions are derived dynamically.
     */
    private IRecipeCategory<?> currentCategory;

    /**
     * IO counts for a recipe type, expressed as raw item/fluid input and output counts.
     */
    private record IoCounts(int itemInput, int itemOutput, int fluidInput, int fluidOutput) {
    }

    private static final Map<String, IoCounts> IO_COUNTS = Map.ofEntries(
            Map.entry("create:fan_blasting", new IoCounts(1, 1, 0, 0)),
            Map.entry("create:fan_haunting", new IoCounts(1, 1, 0, 0)),
            Map.entry("create:fan_smoking", new IoCounts(1, 1, 0, 0)),
            Map.entry("create:pressing", new IoCounts(1, 1, 0, 0)),
            Map.entry("create:sawing", new IoCounts(1, 1, 0, 0)),
            Map.entry("create_new_age:energising", new IoCounts(1, 1, 0, 0)),
            Map.entry("create:fan_washing", new IoCounts(1, 2, 0, 0)),
            Map.entry("create:crushing", new IoCounts(1, 5, 0, 0)),
            Map.entry("create:milling", new IoCounts(1, 3, 0, 0)),
            Map.entry("create:mixing", new IoCounts(6, 1, 6, 1)),
            Map.entry("create:packing", new IoCounts(6, 1, 6, 1)),
            Map.entry("create:spout_filling", new IoCounts(1, 1, 1, 0)),
            Map.entry("createdieselgenerators:basin_fermenting", new IoCounts(3, 2, 3, 2))
    );

    public CreateRecipeTypeHandler(IRecipeManager recipeManager) {
        super(recipeManager);
    }

    @Override
    public RecipeTypeExport convert(IRecipeCategory<?> category) {
        this.currentCategory = category;
        return super.convert(category);
    }

    /**
     * Checks if this handler can handle the given recipe category.
     * Returns true for the Create recipe types with explicitly defined IO counts,
     * as well as Create's mechanical crafting (whose dimensions are derived from
     * its recipes).
     */
    public static boolean canHandle(IRecipeCategory<?> category) {
        String uid = category.getRecipeType().getUid().toString();
        return IO_COUNTS.containsKey(uid) || MECHANICAL_CRAFTING_TYPE_ID.equals(uid);
    }

    @Override
    protected int[] getItemInputDimensions(String recipeTypeUid) {
        if (MECHANICAL_CRAFTING_TYPE_ID.equals(recipeTypeUid)) {
            return mechanicalCraftingInputDimensions();
        }
        return dimensions(counts(recipeTypeUid).itemInput());
    }

    @Override
    protected int[] getItemOutputDimensions(String recipeTypeUid) {
        if (MECHANICAL_CRAFTING_TYPE_ID.equals(recipeTypeUid)) {
            return new int[]{1, 1};
        }
        return dimensions(counts(recipeTypeUid).itemOutput());
    }

    @Override
    protected int[] getFluidInputDimensions(String recipeTypeUid) {
        return dimensions(counts(recipeTypeUid).fluidInput());
    }

    @Override
    protected int[] getFluidOutputDimensions(String recipeTypeUid) {
        return dimensions(counts(recipeTypeUid).fluidOutput());
    }

    private IoCounts counts(String recipeTypeUid) {
        return IO_COUNTS.getOrDefault(recipeTypeUid, new IoCounts(0, 0, 0, 0));
    }

    /**
     * Computes the item input dimensions for mechanical crafting from the largest
     * merged input count across all of its recipes, rounded up to a multiple of
     * {@link #MAX_WIDTH} and laid out as a {@code MAX_WIDTH}-wide grid.
     */
    private int[] mechanicalCraftingInputDimensions() {
        int maxInputs = maxMechanicalCraftingInputCount(currentCategory);
        if (maxInputs <= 0) {
            return new int[]{0, 0};
        }
        int rounded = ((maxInputs + MAX_WIDTH - 1) / MAX_WIDTH) * MAX_WIDTH;
        return new int[]{MAX_WIDTH, rounded / MAX_WIDTH};
    }

    /**
     * Returns the largest number of merged inputs among the given category's
     * recipes. Inputs are merged the same way as in the data export: ingredients
     * resolving to the same item or tag count once. Returns 0 when the category or
     * its recipes are unavailable.
     */
    private <T> int maxMechanicalCraftingInputCount(IRecipeCategory<T> category) {
        if (category == null) {
            return 0;
        }
        int[] max = {0};
        try {
            recipeManager.createRecipeLookup(category.getRecipeType())
                    .includeHidden()
                    .get()
                    .forEach(recipe -> {
                        int count = mergedInputCount(recipe);
                        if (count > max[0]) {
                            max[0] = count;
                        }
                    });
        } catch (Exception ignored) {
            // fall through: no dynamic dimension data available
        }
        return max[0];
    }

    /**
     * Counts the distinct merged inputs of a single recipe, keying ingredients by
     * the same identity used during the data export so the count matches the
     * exported input slots.
     */
    private static int mergedInputCount(Object recipe) {
        if (!(recipe instanceof Recipe<?> recipeObject)) {
            return 0;
        }
        Set<String> keys = new HashSet<>();
        for (Ingredient ingredient : recipeObject.getIngredients()) {
            String key = RecipeDataHandler.ingredientKey(ingredient);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys.size();
    }

    /**
     * Converts an IO count into [width, height] dimensions, capping the width at {@link #MAX_WIDTH}
     * and wrapping the remainder into additional rows.
     */
    private static int[] dimensions(int count) {
        if (count <= 0) {
            return new int[]{0, 0};
        }
        int width = Math.min(count, MAX_WIDTH);
        int height = (count + MAX_WIDTH - 1) / MAX_WIDTH;
        return new int[]{width, height};
    }
}
