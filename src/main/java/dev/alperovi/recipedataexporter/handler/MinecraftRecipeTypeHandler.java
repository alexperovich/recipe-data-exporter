package dev.alperovi.recipedataexporter.handler;

import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;

/**
 * Recipe handler for Minecraft-specific recipe types.
 * Applies recipe type-specific item IO dimensions for common Minecraft recipes.
 */
public class MinecraftRecipeTypeHandler extends RecipeTypeHandler {

    public MinecraftRecipeTypeHandler(IRecipeManager recipeManager) {
        super(recipeManager);
    }

    @Override
    protected int[] getItemInputDimensions(String recipeTypeUid) {
        if ("minecraft:crafting".equals(recipeTypeUid)) {
            return new int[]{3, 3};
        }
        if ("minecraft:smithing".equals(recipeTypeUid)) {
            return new int[]{3, 1};
        }
        return new int[]{1, 1};
    }

    @Override
    protected int[] getItemOutputDimensions(String recipeTypeUid) {
        return new int[]{1, 1};
    }

    /**
     * Checks if this handler can handle the given recipe category.
     * Returns true for all minecraft: recipe types.
     */
    public static boolean canHandle(IRecipeCategory<?> category) {
        return category.getRecipeType().getUid().getNamespace().equals("minecraft");
    }
}
