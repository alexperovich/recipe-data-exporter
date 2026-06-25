package dev.alperovi.recipedataexporter.handler;

import com.google.gson.JsonObject;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.foundation.fluid.FluidIngredient;
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
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recipe data handler for Create and Create-addon processing recipes. Reads the
 * live {@link ProcessingRecipe} object directly: item inputs come from the
 * vanilla ingredient list, item outputs from the rollable {@link ProcessingOutput}s
 * (carrying a chance), and fluid inputs/outputs from Create's fluid ingredient
 * and result lists.
 *
 * Several source types are mapped to a different exported recipe type id
 * (e.g. "create:compacting" -> "create:packing").
 */
public class CreateRecipeDataHandler extends RecipeDataHandler {

    /** Recipe type id of Create's mechanical crafting recipes. */
    public static final String MECHANICAL_CRAFTING_TYPE_ID = "create:mechanical_crafting";

    /** Fixed duration (in ticks) assigned to every exported Create recipe. */
    private static final long EXPORT_DURATION_TICKS = 20L;

    /** Create-family namespaces whose JEI categories this handler serves. */
    private static final Set<String> NAMESPACES = Set.of(
            "create",
            "create_new_age",
            "createdieselgenerators"
    );

    /**
     * Maps a source recipe type to the recipe type id it is exported under.
     * Types not present in the map keep their own id.
     */
    private static final Map<String, String> TYPE_MAPPINGS = Map.of(
            "create:compacting", "create:packing",
            "create:cutting", "create:sawing",
            "create:haunting", "create:fan_haunting",
            "create:splashing", "create:fan_washing"
    );

    /**
     * Source recipe types that are skipped entirely. These recipes surface in
     * non-ignored Create categories via their serializer id but should not be
     * exported.
     */
    private static final Set<String> IGNORED_TYPES = Set.of(
            "create:filling"
    );

    public CreateRecipeDataHandler(ExportTables tables) {
        super(tables);
    }
    @Override
    public RecipeExport convert(IRecipeCategory<?> category, Object recipe, RegistryAccess registryAccess) {
        Object unwrapped = unwrap(recipe);
        if (MECHANICAL_CRAFTING_TYPE_ID.equals(category.getRecipeType().getUid().toString())) {
            return convertMechanicalCrafting(category, recipe, unwrapped, registryAccess);
        }
        if (!(unwrapped instanceof ProcessingRecipe<?> processingRecipe)) {
            return null;
        }
        String recipeId = recipeId(category, recipe);
        if (recipeId == null) {
            return null;
        }
        String sourceType = serializerId(processingRecipe);
        if (IGNORED_TYPES.contains(sourceType)) {
            return null;
        }
        String recipeTypeId = TYPE_MAPPINGS.getOrDefault(sourceType, sourceType);

        Map<String, ItemStackExport> itemInputs = itemInputs(processingRecipe.getIngredients());
        Map<String, FluidStackExport> fluidInputs = fluidInputs(processingRecipe.getFluidIngredients());
        Map<String, ItemStackExport> itemOutputs = itemOutputs(processingRecipe.getRollableResults());
        Map<String, FluidStackExport> fluidOutputs = fluidOutputs(processingRecipe.getFluidResults());

        return new RecipeExport(
                recipeId,
                recipeTypeId,
                sourceType,
                Map.of(),
                EXPORT_DURATION_TICKS,
                0L,
                itemInputs,
                fluidInputs,
                itemOutputs,
                fluidOutputs
        );
    }

    /**
     * Converts a Create mechanical crafting recipe. Unlike processing recipes,
     * its inputs come from the shaped ingredient grid: identical ingredients are
     * merged into a single stack whose count is the number of occurrences, and
     * the merged stacks are sorted by descending stack size. The single result
     * item is the only output.
     */
    private RecipeExport convertMechanicalCrafting(IRecipeCategory<?> category, Object recipe,
                                                   Object unwrapped, RegistryAccess registryAccess) {
        if (!(unwrapped instanceof Recipe<?> recipeObject)) {
            return null;
        }
        String recipeId = recipeId(category, recipe);
        if (recipeId == null) {
            return null;
        }
        String sourceType = serializerId(recipeObject);

        Map<String, ItemStackExport> itemInputs = mergedSortedInputs(recipeObject.getIngredients());
        Map<String, ItemStackExport> itemOutputs = new LinkedHashMap<>();
        ItemStackExport result = itemFromStack(recipeObject.getResultItem(registryAccess), null);
        if (result != null) {
            itemOutputs.put("0", result);
        }

        return new RecipeExport(
                recipeId,
                MECHANICAL_CRAFTING_TYPE_ID,
                sourceType,
                Map.of(),
                EXPORT_DURATION_TICKS,
                0L,
                itemInputs,
                Map.<String, FluidStackExport>of(),
                itemOutputs,
                Map.<String, FluidStackExport>of()
        );
    }

    /**
     * Builds merged, sorted item inputs from a shaped ingredient list. Each
     * non-empty ingredient contributes a count of 1; ingredients resolving to the
     * same item or tag are merged into one stack whose count is the sum. The
     * resulting stacks are sorted by descending stack size and assigned
     * sequential slot indices starting at 0.
     */
    private Map<String, ItemStackExport> mergedSortedInputs(List<Ingredient> ingredients) {
        List<ItemStackExport> converted = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            ItemStackExport item = itemFromIngredient(ingredient, 1, null);
            if (item != null) {
                converted.add(item);
            }
        }
        return mergeAndSortByCount(converted);
    }

    /**
     * Builds item inputs from the recipe's ingredient list, assigning sequential
     * slot indices starting at 0 and skipping empty ingredients.
     */
    private Map<String, ItemStackExport> itemInputs(List<Ingredient> ingredients) {
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
     * Builds item outputs from the rollable results. A result chance below 1.0 is
     * stored as a probability where 10000 represents 100%.
     */
    private Map<String, ItemStackExport> itemOutputs(List<ProcessingOutput> results) {
        Map<String, ItemStackExport> outputs = new LinkedHashMap<>();
        int index = 0;
        for (ProcessingOutput output : results) {
            float chance = output.getChance();
            Long probability = chance < 1.0F ? Math.round((double) chance * 10000.0) : null;
            ItemStackExport converted = itemFromStack(output.getStack(), probability);
            if (converted != null) {
                outputs.put(String.valueOf(index), converted);
                index++;
            }
        }
        return outputs;
    }

    /**
     * Builds fluid inputs from Create's fluid ingredients, using the first
     * matching fluid stack and the required amount.
     */
    private Map<String, FluidStackExport> fluidInputs(List<FluidIngredient> ingredients) {
        Map<String, FluidStackExport> inputs = new LinkedHashMap<>();
        int index = 0;
        for (FluidIngredient ingredient : ingredients) {
            FluidStackExport converted = fluidFromIngredient(ingredient);
            if (converted != null) {
                inputs.put(String.valueOf(index), converted);
                index++;
            }
        }
        return inputs;
    }

    /**
     * Builds fluid outputs from the recipe's fluid results.
     */
    private Map<String, FluidStackExport> fluidOutputs(List<FluidStack> results) {
        Map<String, FluidStackExport> outputs = new LinkedHashMap<>();
        int index = 0;
        for (FluidStack stack : results) {
            FluidStackExport converted = fluidFromStack(stack);
            if (converted != null) {
                outputs.put(String.valueOf(index), converted);
                index++;
            }
        }
        return outputs;
    }

    /**
     * Converts a Create {@link FluidIngredient} into a {@link FluidStackExport},
     * interning the fluid data. Tag-based ingredients preserve the tag id (with
     * the first matching fluid as representative); otherwise the first matching
     * fluid stack provides the fluid id. The amount comes from the ingredient's
     * required amount.
     */
    private FluidStackExport fluidFromIngredient(FluidIngredient ingredient) {
        if (ingredient == null) {
            return null;
        }
        List<FluidStack> stacks = ingredient.getMatchingFluidStacks();
        FluidStack first = stacks.isEmpty() ? null : stacks.get(0);

        String tag = fluidTag(ingredient);
        if (tag != null) {
            FluidStack representative = first != null && !first.isEmpty() ? first : null;
            String key = tables.internFluid(null, tag, representative);
            return new FluidStackExport(key, ingredient.getRequiredAmount());
        }
        if (first == null || first.isEmpty()) {
            return null;
        }
        ResourceLocation location = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(first.getFluid());
        String key = tables.internFluid(location != null ? location.toString() : null, null, first);
        return new FluidStackExport(key, ingredient.getRequiredAmount());
    }

    /**
     * Returns the fluid tag id of a tag-based Create {@link FluidIngredient}, or
     * null when the ingredient matches concrete fluids. Create's tag ingredient
     * serializes the tag under the {@code fluidTag} key.
     */
    private static String fluidTag(FluidIngredient ingredient) {
        try {
            JsonObject json = ingredient.serialize();
            if (json != null && json.has("fluidTag") && !json.get("fluidTag").isJsonNull()) {
                return json.get("fluidTag").getAsString();
            }
        } catch (Exception ignored) {
            // fall through: treat as a concrete-fluid ingredient
        }
        return null;
    }

    /**
     * Returns the registry id of the recipe serializer (e.g. "create:mixing").
     */
    private String serializerId(Recipe<?> recipe) {
        ResourceLocation location = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        return location != null ? location.toString() : null;
    }

    /**
     * Checks whether this handler can convert the recipes of the given category.
     * Returns true for Create and supported Create-addon categories.
     */
    public static boolean canHandle(IRecipeCategory<?> category) {
        return NAMESPACES.contains(category.getRecipeType().getUid().getNamespace());
    }
}
