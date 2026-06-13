package dev.alperovi.recipedataexporter.handler;

import dev.alperovi.recipedataexporter.export.ExportTables;
import dev.alperovi.recipedataexporter.model.FluidStackExport;
import dev.alperovi.recipedataexporter.model.ItemStackExport;
import dev.alperovi.recipedataexporter.model.RecipeExport;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fallback recipe data handler used for any JEI category not served by a more
 * specific handler. It extracts whatever the vanilla {@link Recipe} interface
 * exposes: the ingredient list as item inputs and the result item as the single
 * output. Fluids, data and the numeric fields are left empty.
 */
public class GenericRecipeDataHandler extends RecipeDataHandler {

    public GenericRecipeDataHandler(ExportTables tables) {
        super(tables);
    }

    @Override
    public RecipeExport convert(IRecipeCategory<?> category, Object recipe, RegistryAccess registryAccess) {
        String recipeId = recipeId(category, recipe);
        if (recipeId == null) {
            return null;
        }
        String recipeTypeId = category.getRecipeType().getUid().toString();

        Object unwrapped = unwrap(recipe);
        Map<String, ItemStackExport> itemInputs = new LinkedHashMap<>();
        Map<String, ItemStackExport> itemOutputs = new LinkedHashMap<>();
        String fullTypeName = recipeTypeId;

        if (unwrapped instanceof Recipe<?> recipeObject) {
            int index = 0;
            for (Ingredient ingredient : recipeObject.getIngredients()) {
                ItemStackExport converted = itemFromIngredient(ingredient, 1, null);
                if (converted != null) {
                    itemInputs.put(String.valueOf(index), converted);
                    index++;
                }
            }
            ItemStackExport result = itemFromStack(recipeObject.getResultItem(registryAccess), null);
            if (result != null) {
                itemOutputs.put("0", result);
            }
            ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipeObject.getType());
            if (typeId != null) {
                fullTypeName = typeId.toString();
            }
        }

        return new RecipeExport(
                recipeId,
                recipeTypeId,
                fullTypeName,
                Map.of(),
                0L,
                0L,
                itemInputs,
                Map.<String, FluidStackExport>of(),
                itemOutputs,
                Map.<String, FluidStackExport>of()
        );
    }
}
