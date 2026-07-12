package dev.alperovi.recipedataexporter.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.alperovi.recipedataexporter.export.ExportTables;
import dev.alperovi.recipedataexporter.model.FluidStackExport;
import dev.alperovi.recipedataexporter.model.ItemStackExport;
import dev.alperovi.recipedataexporter.model.RecipeExport;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract handler for converting live recipe objects (obtained from the JEI
 * recipe manager) into {@link RecipeExport} objects. Subclasses handle the
 * recipes of one or more JEI recipe categories and map them to a target recipe
 * type id (e.g. "minecraft:crafting").
 *
 * Mirrors the style of {@link RecipeTypeHandler}: subclasses expose a static
 * {@code canHandle(IRecipeCategory)} method used for registration, and implement
 * the instance {@link #convert} method for the actual conversion logic.
 */
public abstract class RecipeDataHandler {

    /**
     * Shared, deduplicating tables of the unique item and fluid data referenced
     * by the converted recipes. Handlers intern items/fluids here and reference
     * them from recipes by the returned lookup key.
     */
    protected final ExportTables tables;

    protected RecipeDataHandler(ExportTables tables) {
        this.tables = tables;
    }

    /**
     * Converts a single recipe object into a {@link RecipeExport}.
     *
     * @param category       the JEI recipe category the recipe belongs to
     * @param recipe         the recipe object (e.g. a vanilla {@code Recipe},
     *                       a GregTech {@code GTRecipe} or a Create
     *                       {@code ProcessingRecipe})
     * @param registryAccess the registry access used to resolve recipe results
     * @return the converted RecipeExport, or null when the recipe should be skipped
     */
    public abstract RecipeExport convert(IRecipeCategory<?> category, Object recipe, RegistryAccess registryAccess);

    /**
     * Resolves the recipe id from the JEI category. Falls back to the id carried
     * by the recipe object itself when the category cannot provide one.
     */
    @SuppressWarnings("unchecked")
    protected String recipeId(IRecipeCategory<?> category, Object recipe) {
        try {
            ResourceLocation location = ((IRecipeCategory<Object>) category).getRegistryName(recipe);
            if (location != null) {
                return location.toString();
            }
        } catch (Exception ignored) {
            // fall through to the recipe's own id
        }
        if (recipe instanceof Recipe<?> recipeObject) {
            return recipeObject.getId().toString();
        }
        return null;
    }

    /**
     * Returns the recipe object as-is. Kept as a single extension point for
     * unwrapping recipe wrappers should that ever become necessary.
     */
    protected static Object unwrap(Object recipe) {
        return recipe;
    }

    /**
     * Builds an {@link ItemStackExport} from a concrete {@link ItemStack}
     * (typically a recipe result), interning the item data. Returns null for
     * empty stacks.
     */
    protected ItemStackExport itemFromStack(ItemStack stack, Long probability) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String key = tables.internItem(itemId(stack), null, nbt(stack), stack);
        return new ItemStackExport(key, stack.getCount(), probability, null);
    }

    /**
     * Builds an {@link ItemStackExport} from a vanilla {@link Ingredient},
     * interning the item data. When the ingredient is tag-based the tag id is
     * preserved; otherwise the first matching item is used. The count is supplied
     * by the caller because vanilla ingredients do not carry one.
     */
    protected ItemStackExport itemFromIngredient(Ingredient ingredient, int count, Long probability) {
        if (ingredient == null || ingredient.isEmpty()) {
            return null;
        }
        String tag = tagFromIngredient(ingredient);
        ItemStack[] stacks = ingredient.getItems();
        if (tag != null) {
            ItemStack representative = stacks.length > 0 ? stacks[0] : null;
            String key = tables.internItem(null, tag, null, representative);
            return new ItemStackExport(key, count, probability, null);
        }
        if (stacks.length == 0) {
            return null;
        }
        ItemStack first = stacks[0];
        String key = tables.internItem(itemId(first), null, nbt(first), first);
        return new ItemStackExport(key, count, probability, null);
    }

    /**
     * Builds a {@link FluidStackExport} from a Forge {@link FluidStack},
     * interning the fluid data. Returns null for empty stacks.
     */
    protected FluidStackExport fluidFromStack(FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation location = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        String key = tables.internFluid(location != null ? location.toString() : null, null, stack);
        return new FluidStackExport(key, stack.getAmount());
    }

    /**
     * Returns a stable identity key for a vanilla {@link Ingredient} that groups
     * equal ingredients together: tag-based ingredients are keyed by their tag id
     * and item-based ingredients by their first matching item's id (plus nbt when
     * present). Returns null for empty or unresolvable ingredients. Used to merge
     * identical inputs that vanilla represents as separate single-count slots.
     */
    protected static String ingredientKey(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) {
            return null;
        }
        String tag = tagFromIngredient(ingredient);
        if (tag != null) {
            return "#" + tag;
        }
        ItemStack[] stacks = ingredient.getItems();
        if (stacks.length == 0) {
            return null;
        }
        ItemStack first = stacks[0];
        String id = itemId(first);
        if (id == null) {
            return null;
        }
        String nbt = nbt(first);
        return nbt != null ? id + "#" + nbt : id;
    }

    /**
     * Merges item stacks that share the same table key into a single stack whose
     * count is the sum of the merged counts, sorts the merged stacks by descending
     * count, and assigns sequential slot indices starting at 0. The probability of
     * the first occurrence of each key is preserved. Null entries are skipped.
     */
    protected static Map<String, ItemStackExport> mergeAndSortByCount(Collection<ItemStackExport> items) {
        Map<String, ItemStackExport> mergedByKey = new LinkedHashMap<>();
        for (ItemStackExport item : items) {
            if (item == null) {
                continue;
            }
            ItemStackExport existing = mergedByKey.get(item.key());
            if (existing == null) {
                mergedByKey.put(item.key(), item);
            } else {
                mergedByKey.put(item.key(), new ItemStackExport(
                        existing.key(), existing.count() + item.count(), existing.probability(),
                        existing.tierChanceBoost()));
            }
        }

        List<ItemStackExport> sorted = new ArrayList<>(mergedByKey.values());
        sorted.sort(Comparator.comparingInt(ItemStackExport::count).reversed());

        Map<String, ItemStackExport> result = new LinkedHashMap<>();
        int index = 0;
        for (ItemStackExport item : sorted) {
            result.put(String.valueOf(index), item);
            index++;
        }
        return result;
    }

    /**
     * Merges fluid stacks that share the same table key into a single stack whose
     * amount is the sum of the merged amounts, sorts the merged stacks by
     * descending amount, and assigns sequential slot indices starting at 0. Null
     * entries are skipped.
     */
    protected static Map<String, FluidStackExport> mergeFluidsAndSortByAmount(Collection<FluidStackExport> fluids) {
        Map<String, FluidStackExport> mergedByKey = new LinkedHashMap<>();
        for (FluidStackExport fluid : fluids) {
            if (fluid == null) {
                continue;
            }
            FluidStackExport existing = mergedByKey.get(fluid.key());
            if (existing == null) {
                mergedByKey.put(fluid.key(), fluid);
            } else {
                mergedByKey.put(fluid.key(), new FluidStackExport(
                        existing.key(), existing.amount() + fluid.amount()));
            }
        }

        List<FluidStackExport> sorted = new ArrayList<>(mergedByKey.values());
        sorted.sort(Comparator.comparingLong(FluidStackExport::amount).reversed());

        Map<String, FluidStackExport> result = new LinkedHashMap<>();
        int index = 0;
        for (FluidStackExport fluid : sorted) {
            result.put(String.valueOf(index), fluid);
            index++;
        }
        return result;
    }

    /**
     * Returns the registry id of an item stack's item, or null when unregistered.
     */
    protected static String itemId(ItemStack stack) {
        ResourceLocation location = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return location != null ? location.toString() : null;
    }

    /**
     * Returns the string form of an item stack's NBT tag, or null when absent.
     */
    protected static String nbt(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null ? tag.toString() : null;
    }

    /**
     * Recovers the tag id of a tag-based vanilla {@link Ingredient} via its JSON
     * representation, or null when the ingredient is item-based or unparseable.
     */
    protected static String tagFromIngredient(Ingredient ingredient) {
        try {
            return tagFromJson(ingredient.toJson());
        } catch (Exception e) {
            return null;
        }
    }

    private static String tagFromJson(JsonElement json) {
        if (json == null || json.isJsonNull()) {
            return null;
        }
        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            return array.isEmpty() ? null : tagFromJson(array.get(0));
        }
        if (json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            if (object.has("tag") && !object.get("tag").isJsonNull()) {
                return object.get("tag").getAsString();
            }
            // GregTech (and other mods) wrap the real ingredient in a sized
            // ingredient that nests it under "ingredient" (e.g. {"type":
            // "gtceu:sized", "count": 1, "ingredient": {"tag": "..."}}); descend
            // into the wrapper so the nested tag is still recovered.
            if (object.has("ingredient") && !object.get("ingredient").isJsonNull()) {
                return tagFromJson(object.get("ingredient"));
            }
        }
        return null;
    }
}
