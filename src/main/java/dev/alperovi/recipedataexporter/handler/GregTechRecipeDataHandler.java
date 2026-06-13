package dev.alperovi.recipedataexporter.handler;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import dev.alperovi.recipedataexporter.export.ExportTables;
import dev.alperovi.recipedataexporter.model.FluidStackExport;
import dev.alperovi.recipedataexporter.model.ItemStackExport;
import dev.alperovi.recipedataexporter.model.RecipeExport;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fluids.FluidStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipe data handler for GregTech (gtceu) machine recipes. Reads the live
 * {@link GTRecipe} object directly: item/fluid contents come from the recipe
 * capability maps, the EU per tick yields the voltage, and the recipe's
 * {@code data} compound is copied verbatim.
 */
public class GregTechRecipeDataHandler extends RecipeDataHandler {

    private static final String NAMESPACE = "gtceu";

    private static final String PROGRAMMED_CIRCUIT_ID = "gtceu:programmed_circuit";
    private static final String CIRCUIT_ITEM_ID = "gtceu:programmed_circuit";

    /** GregTech expresses chances out of 10000 (100%). */
    private static final int MAX_CHANCE = 10000;

    public GregTechRecipeDataHandler(ExportTables tables) {
        super(tables);
    }

    @Override
    public RecipeExport convert(IRecipeCategory<?> category, Object recipe, RegistryAccess registryAccess) {
        Object unwrapped = unwrap(recipe);
        if (!(unwrapped instanceof GTRecipe gtRecipe)) {
            return null;
        }
        String recipeId = recipeId(category, recipe);
        if (recipeId == null) {
            recipeId = gtRecipe.id != null ? gtRecipe.id.toString() : null;
        }
        if (recipeId == null) {
            return null;
        }

        String sourceType = gtRecipe.recipeType.registryName.toString();

        Map<String, ItemStackExport> itemInputs = itemContents(gtRecipe.getInputContents(ItemRecipeCapability.CAP));
        Map<String, FluidStackExport> fluidInputs = fluidContents(gtRecipe.getInputContents(FluidRecipeCapability.CAP));
        Map<String, ItemStackExport> itemOutputs = itemContents(gtRecipe.getOutputContents(ItemRecipeCapability.CAP));
        Map<String, FluidStackExport> fluidOutputs = fluidContents(gtRecipe.getOutputContents(FluidRecipeCapability.CAP));

        long duration = gtRecipe.duration;
        long voltage = RecipeHelper.getRealEUtWithIO(gtRecipe).signedVoltage();
        Map<String, Object> data = data(gtRecipe.data);

        return new RecipeExport(
                recipeId,
                sourceType,
                sourceType,
                data,
                duration,
                voltage,
                itemInputs,
                fluidInputs,
                itemOutputs,
                fluidOutputs
        );
    }

    /**
     * Converts a list of item {@link Content}s into an indexed map, preserving
     * the programmed-circuit marker and any chanced-output probability.
     */
    private Map<String, ItemStackExport> itemContents(List<Content> contents) {
        Map<String, ItemStackExport> items = new LinkedHashMap<>();
        int index = 0;
        for (Content content : contents) {
            ItemStackExport item = itemContent(content);
            if (item != null) {
                items.put(String.valueOf(index), item);
                index++;
            }
        }
        return items;
    }

    /**
     * Converts a single item {@link Content}. Programmed circuits are emitted as
     * "gtceu:programmed_circuit" with the configuration carried in the NBT under
     * the key "configuration"; other items keep their tag (when tag-based) or
     * concrete item id.
     */
    private ItemStackExport itemContent(Content content) {
        Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
        if (ingredient == null || ingredient.isEmpty()) {
            return null;
        }
        Long probability = content.chance < content.maxChance ? (long) content.chance : null;

        ItemStack[] stacks = ingredient.getItems();
        if (stacks.length == 0) {
            return null;
        }
        ItemStack first = stacks[0];

        if (CIRCUIT_ITEM_ID.equals(itemId(first))) {
            String key = tables.internItem(PROGRAMMED_CIRCUIT_ID, null, circuitNbt(first), first);
            return new ItemStackExport(key, first.getCount(), probability);
        }

        String tag = tagFromIngredient(ingredient);
        if (tag != null) {
            String key = tables.internItem(null, tag, null, first);
            return new ItemStackExport(key, first.getCount(), probability);
        }
        String key = tables.internItem(itemId(first), null, nbt(first), first);
        return new ItemStackExport(key, first.getCount(), probability);
    }

    /**
     * Builds the NBT for a programmed circuit, carrying its configuration value
     * under the key "configuration".
     */
    private String circuitNbt(ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("configuration", circuitConfiguration(stack));
        return tag.toString();
    }

    /**
     * Reads the integer configuration of a programmed circuit from its NBT,
     * defaulting to 0 when absent.
     */
    private int circuitConfiguration(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("Configuration", Tag.TAG_INT)) {
            return tag.getInt("Configuration");
        }
        return 0;
    }

    /**
     * Converts a list of fluid {@link Content}s into an indexed map.
     */
    private Map<String, FluidStackExport> fluidContents(List<Content> contents) {
        Map<String, FluidStackExport> fluids = new LinkedHashMap<>();
        int index = 0;
        for (Content content : contents) {
            FluidStackExport fluid = fluidContent(content);
            if (fluid != null) {
                fluids.put(String.valueOf(index), fluid);
                index++;
            }
        }
        return fluids;
    }

    /**
     * Converts a single fluid {@link Content} into a {@link FluidStackExport}.
     * Tag-based ingredients preserve the tag id (with the first matching fluid as
     * representative); otherwise the first concrete fluid stack is used.
     */
    private FluidStackExport fluidContent(Content content) {
        FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
        if (ingredient == null) {
            return null;
        }
        FluidStack[] stacks = ingredient.getStacks();
        FluidStack first = stacks.length > 0 ? stacks[0] : null;

        String tag = fluidTag(ingredient);
        if (tag != null) {
            String key = tables.internFluid(null, tag, first);
            long amount = first != null ? first.getAmount() : ingredient.getAmount();
            return new FluidStackExport(key, amount);
        }
        if (first == null) {
            return null;
        }
        return fluidFromStack(first);
    }

    /**
     * Returns the fluid tag id of a tag-based {@link FluidIngredient}, or null
     * when the ingredient matches concrete fluids. GregTech fluid ingredients
     * carry their matchers in the {@code values} array; a
     * {@link FluidIngredient.TagValue} holds the tag key.
     */
    private static String fluidTag(FluidIngredient ingredient) {
        FluidIngredient.Value[] values = ingredient.values;
        if (values == null) {
            return null;
        }
        for (FluidIngredient.Value value : values) {
            if (value instanceof FluidIngredient.TagValue tagValue) {
                return tagValue.tag().location().toString();
            }
        }
        return null;
    }

    /**
     * Copies a GregTech recipe data compound into a map, preserving the value
     * types so they survive re-serialization.
     */
    private Map<String, Object> data(CompoundTag tag) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (tag == null) {
            return data;
        }
        for (String key : tag.getAllKeys()) {
            Tag value = tag.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof net.minecraft.nbt.NumericTag numeric) {
                data.put(key, numeric.getAsLong());
            } else {
                data.put(key, value.getAsString());
            }
        }
        return data;
    }

    /**
     * Checks whether this handler can convert the recipes of the given category.
     * Returns true for all {@code gtceu:} recipe categories.
     */
    public static boolean canHandle(IRecipeCategory<?> category) {
        return category.getRecipeType().getUid().getNamespace().equals(NAMESPACE);
    }
}
