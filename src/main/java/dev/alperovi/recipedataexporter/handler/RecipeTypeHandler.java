package dev.alperovi.recipedataexporter.handler;

import dev.alperovi.recipedataexporter.model.RecipeTypeExport;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract handler for converting IRecipeCategory objects to RecipeTypeExport objects.
 * Subclasses can override dimension methods to customize item/fluid IO dimensions for specific recipe types.
 */

public abstract class RecipeTypeHandler {

    protected final IRecipeManager recipeManager;

    public RecipeTypeHandler(IRecipeManager recipeManager) {
        this.recipeManager = recipeManager;
    }

    /**
     * Converts an IRecipeCategory to a RecipeTypeExport.
     * Uses dimension methods (overridable by subclasses) to determine item/fluid IO dimensions.
     */
    public RecipeTypeExport convert(IRecipeCategory<?> category) {
        String id = getId(category);
        String name = getName(category);
        String fullTypeName = getFullTypeName(category);
        List<String> crafters = getCrafters(category);

        int[] itemInputDimensions = getItemInputDimensions(id);
        int[] itemOutputDimensions = getItemOutputDimensions(id);
        int[] fluidInputDimensions = getFluidInputDimensions(id);
        int[] fluidOutputDimensions = getFluidOutputDimensions(id);

        return new RecipeTypeExport(
                id,
                name,
                fullTypeName,
                crafters,
                itemInputDimensions,
                itemOutputDimensions,
                fluidInputDimensions,
                fluidOutputDimensions
        );
    }

    /**
     * Returns item input dimensions for a recipe type.
     * Override in subclasses to customize per recipe type.
     *
     * @param recipeTypeUid the recipe type UID (e.g., "minecraft:crafting")
     * @return array [width, height] for item input grid
     */
    protected int[] getItemInputDimensions(String recipeTypeUid) {
        return new int[]{0, 0};
    }

    /**
     * Returns item output dimensions for a recipe type.
     * Override in subclasses to customize per recipe type.
     *
     * @param recipeTypeUid the recipe type UID (e.g., "minecraft:crafting")
     * @return array [width, height] for item output grid
     */
    protected int[] getItemOutputDimensions(String recipeTypeUid) {
        return new int[]{0, 0};
    }

    /**
     * Returns fluid input dimensions for a recipe type.
     * Override in subclasses to customize per recipe type.
     *
     * @param recipeTypeUid the recipe type UID (e.g., "minecraft:crafting")
     * @return array [width, height] for fluid input grid
     */
    protected int[] getFluidInputDimensions(String recipeTypeUid) {
        return new int[]{0, 0};
    }

    /**
     * Returns fluid output dimensions for a recipe type.
     * Override in subclasses to customize per recipe type.
     *
     * @param recipeTypeUid the recipe type UID (e.g., "minecraft:crafting")
     * @return array [width, height] for fluid output grid
     */
    protected int[] getFluidOutputDimensions(String recipeTypeUid) {
        return new int[]{0, 0};
    }

    /**
     * Extracts the unique ID from a recipe category.
     */
    protected String getId(IRecipeCategory<?> category) {
        return category.getRecipeType().getUid().toString();
    }

    /**
     * Extracts the display name from a recipe category.
     */
    protected String getName(IRecipeCategory<?> category) {
        return category.getTitle().getString();
    }

    /**
     * Extracts the full type name (class name) from a recipe category's recipe type.
     */
    protected String getFullTypeName(IRecipeCategory<?> category) {
        return category.getRecipeType().getRecipeClass().getName();
    }

    /**
     * Retrieves the list of crafters (catalyst items) for a recipe type.
     * Uses IRecipeManager to query catalyst lookup for the recipe type.
     * Returns item IDs (e.g., "minecraft:crafting_table") for each catalyst.
     */
    protected List<String> getCrafters(IRecipeCategory<?> category) {
        List<String> crafters = new ArrayList<>();
        try {
            var catalystLookup = recipeManager.createRecipeCatalystLookup(category.getRecipeType());
            catalystLookup.getItemStack().forEach(itemStack -> {
                var resourceLocation = ForgeRegistries.ITEMS.getKey(itemStack.getItem());
                if (resourceLocation != null) {
                    crafters.add(resourceLocation.toString());
                }
            });
        } catch (Exception e) {
            // If catalyst lookup fails, return empty list
        }
        return filterCrafters(getId(category), crafters);
    }

    /**
     * Applies recipe type-specific filtering to the raw crafter list.
     * <ul>
     *     <li>{@code minecraft:crafting} and {@code minecraft:campfire}: keep only the first crafter.</li>
     *     <li>{@code minecraft:blasting}, {@code minecraft:furnace} and {@code minecraft:smoking}:
     *     keep only {@code gtceu:*} crafters.</li>
     *     <li>{@code create:*}: keep only {@code create:basin} if present, otherwise keep only the first.</li>
     * </ul>
     */
    protected List<String> filterCrafters(String recipeTypeUid, List<String> crafters) {
        if (crafters.isEmpty()) {
            return crafters;
        }

        switch (recipeTypeUid) {
            case "minecraft:crafting", "minecraft:campfire" ->
                    crafters = new ArrayList<>(crafters.subList(0, 1));
            case "minecraft:blasting", "minecraft:furnace", "minecraft:smoking" -> {
                List<String> filtered = new ArrayList<>();
                for (String crafter : crafters) {
                    if (crafter.startsWith("gtceu:")) {
                        filtered.add(crafter);
                    }
                }
                crafters = filtered;
            }
            default -> {
                if (recipeTypeUid.startsWith("create:")) {
                    if (crafters.contains("create:basin")) {
                        crafters = new ArrayList<>(List.of("create:basin"));
                    } else {
                        crafters = new ArrayList<>(crafters.subList(0, 1));
                    }
                }
            }
        }

        return crafters;
    }
}
