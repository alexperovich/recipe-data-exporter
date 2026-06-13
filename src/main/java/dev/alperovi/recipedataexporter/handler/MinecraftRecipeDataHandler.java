package dev.alperovi.recipedataexporter.handler;

import dev.alperovi.recipedataexporter.export.ExportTables;
import dev.alperovi.recipedataexporter.model.FluidStackExport;
import dev.alperovi.recipedataexporter.model.ItemStackExport;
import dev.alperovi.recipedataexporter.model.RecipeExport;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipe data handler for vanilla Minecraft recipes (and any mod recipe that
 * reuses the vanilla crafting/cooking/stonecutting/smithing classes and is shown
 * in a {@code minecraft:*} JEI category).
 *
 * Crafting recipes (shaped and shapeless) are grouped under "minecraft:crafting".
 * Cooking recipes keep a type derived from their {@link RecipeType}; campfire
 * cooking is grouped under "minecraft:campfire". Stonecutting and smithing keep
 * their own grouped ids.
 */
public class MinecraftRecipeDataHandler extends RecipeDataHandler {

    public static final String CRAFTING_RECIPE_TYPE_ID = "minecraft:crafting";
    public static final String CAMPFIRE_RECIPE_TYPE_ID = "minecraft:campfire";
    public static final String SMELTING_RECIPE_TYPE_ID = "minecraft:furnace";
    public static final String BLASTING_RECIPE_TYPE_ID = "minecraft:blasting";
    public static final String SMOKING_RECIPE_TYPE_ID = "minecraft:smoking";
    public static final String STONECUTTING_RECIPE_TYPE_ID = "minecraft:stonecutting";
    public static final String SMITHING_RECIPE_TYPE_ID = "minecraft:smithing";

    private static final String NAMESPACE = "minecraft";

    public MinecraftRecipeDataHandler(ExportTables tables) {
        super(tables);
    }

    @Override
    public RecipeExport convert(IRecipeCategory<?> category, Object recipe, RegistryAccess registryAccess) {
        Object unwrapped = unwrap(recipe);
        if (!(unwrapped instanceof Recipe<?> recipeObject)) {
            return null;
        }
        String recipeId = recipeId(category, recipe);
        if (recipeId == null) {
            return null;
        }
        String sourceType = serializerId(recipeObject);

        if (recipeObject instanceof ShapedRecipe shaped) {
            return build(recipeId, CRAFTING_RECIPE_TYPE_ID, sourceType, 0L,
                    shapedInputs(shaped), outputs(recipeObject, registryAccess));
        }
        if (recipeObject instanceof CraftingRecipe crafting) {
            return build(recipeId, CRAFTING_RECIPE_TYPE_ID, sourceType, 0L,
                    sequentialInputs(crafting.getIngredients()), outputs(recipeObject, registryAccess));
        }
        if (recipeObject instanceof AbstractCookingRecipe cooking) {
            return build(recipeId, cookingTypeId(cooking), sourceType, cooking.getCookingTime(),
                    sequentialInputs(cooking.getIngredients()), outputs(recipeObject, registryAccess));
        }
        if (recipeObject instanceof StonecutterRecipe stonecutter) {
            return build(recipeId, STONECUTTING_RECIPE_TYPE_ID, sourceType, 0L,
                    sequentialInputs(stonecutter.getIngredients()), outputs(recipeObject, registryAccess));
        }
        if (recipeObject instanceof SmithingRecipe smithing) {
            Map<String, ItemStackExport> inputs = sequentialInputs(smithing.getIngredients());
            if (inputs.isEmpty()) {
                return null;
            }
            return build(recipeId, SMITHING_RECIPE_TYPE_ID, sourceType, 0L,
                    inputs, outputs(recipeObject, registryAccess));
        }
        return null;
    }

    /**
     * Assembles a RecipeExport with empty fluid maps and zero voltage.
     */
    private RecipeExport build(String recipeId, String recipeTypeId, String sourceType, long duration,
                               Map<String, ItemStackExport> itemInputs,
                               Map<String, ItemStackExport> itemOutputs) {
        return new RecipeExport(
                recipeId,
                recipeTypeId,
                sourceType,
                Map.of(),
                duration,
                0L,
                itemInputs,
                Map.<String, FluidStackExport>of(),
                itemOutputs,
                Map.<String, FluidStackExport>of()
        );
    }

    /**
     * Builds item inputs for a shaped recipe. The recipe's ingredient list is
     * stored in row-major order over a width x height grid; each non-empty
     * ingredient is keyed by its {@code row * width + col} slot index.
     */
    private Map<String, ItemStackExport> shapedInputs(ShapedRecipe shaped) {
        Map<String, ItemStackExport> inputs = new LinkedHashMap<>();
        List<Ingredient> ingredients = shaped.getIngredients();
        for (int index = 0; index < ingredients.size(); index++) {
            ItemStackExport ingredient = itemFromIngredient(ingredients.get(index), 1, null);
            if (ingredient != null) {
                inputs.put(String.valueOf(index), ingredient);
            }
        }
        return inputs;
    }

    /**
     * Builds item inputs from an ingredient list, assigning sequential slot
     * indices starting at 0 and skipping empty ingredients.
     */
    private Map<String, ItemStackExport> sequentialInputs(List<Ingredient> ingredients) {
        Map<String, ItemStackExport> inputs = new LinkedHashMap<>();
        int index = 0;
        for (Ingredient ingredient : ingredients) {
            ItemStackExport converted = itemFromIngredient(ingredient, 1, null);
            if (converted != null) {
                inputs.put(String.valueOf(index), converted);
                index++;
            }
        }
        return inputs;
    }

    /**
     * Builds the single item output (slot "0") from the recipe result.
     */
    private Map<String, ItemStackExport> outputs(Recipe<?> recipe, RegistryAccess registryAccess) {
        Map<String, ItemStackExport> outputs = new LinkedHashMap<>();
        ItemStackExport result = itemFromStack(recipe.getResultItem(registryAccess), null);
        if (result != null) {
            outputs.put("0", result);
        }
        return outputs;
    }

    /**
     * Maps a cooking recipe to its grouped recipe type id. Campfire cooking maps
     * to "minecraft:campfire"; smelting maps to the "minecraft:furnace" JEI
     * category id; the others keep their conventional ids.
     */
    private String cookingTypeId(AbstractCookingRecipe cooking) {
        RecipeType<?> type = cooking.getType();
        if (type == RecipeType.BLASTING) {
            return BLASTING_RECIPE_TYPE_ID;
        }
        if (type == RecipeType.SMOKING) {
            return SMOKING_RECIPE_TYPE_ID;
        }
        if (type == RecipeType.CAMPFIRE_COOKING) {
            return CAMPFIRE_RECIPE_TYPE_ID;
        }
        return SMELTING_RECIPE_TYPE_ID;
    }

    /**
     * Returns the registry id of the recipe serializer (e.g. "minecraft:crafting_shaped").
     */
    private String serializerId(Recipe<?> recipe) {
        ResourceLocation location = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        return location != null ? location.toString() : null;
    }

    /**
     * Checks whether this handler can convert the recipes of the given category.
     * Returns true for all {@code minecraft:} recipe categories.
     */
    public static boolean canHandle(IRecipeCategory<?> category) {
        return category.getRecipeType().getUid().getNamespace().equals(NAMESPACE);
    }
}
