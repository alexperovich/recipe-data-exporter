package dev.alperovi.recipedataexporter.handler;

import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;

import java.util.Map;

/**
 * Recipe handler for Create (and Create addon) recipe types.
 * Provides fixed item/fluid IO counts per recipe type, converted into [width, height]
 * dimensions capped at a maximum width.
 */
public class CreateRecipeTypeHandler extends RecipeTypeHandler {

    private static final int MAX_WIDTH = 3;

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

    /**
     * Checks if this handler can handle the given recipe category.
     * Returns true for the Create recipe types with explicitly defined IO counts.
     */
    public static boolean canHandle(IRecipeCategory<?> category) {
        return IO_COUNTS.containsKey(category.getRecipeType().getUid().toString());
    }

    @Override
    protected int[] getItemInputDimensions(String recipeTypeUid) {
        return dimensions(counts(recipeTypeUid).itemInput());
    }

    @Override
    protected int[] getItemOutputDimensions(String recipeTypeUid) {
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
