package dev.alperovi.recipedataexporter.handler;

import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;

import dev.alperovi.recipedataexporter.model.RecipeTypeExport;

import java.util.List;

/**
 * Recipe handler for recipe types that should be ignored and not exported.
 * Matches recipe type UIDs against a list of patterns supporting exact matches
 * and trailing wildcards (e.g., "ae2:*" or "create:automatic_*").
 */
public class IgnoreRecipeTypeHandler extends RecipeTypeHandler {

    private static final List<String> IGNORED_PATTERNS = List.of(
            "minecraft:anvil",
            "minecraft:brewing",
            "minecraft:compostable",
            "minecraft:fuel",
            "ae2:*",
            "architects_palette:warping",
            "gtceu:bacterial_runic_mutator",
            "gtceu:bedrock_fluid_diagram",
            "gtceu:programmed_circuit",
            "gtceu:ore_processing_diagram",
            "gtceu:multiblock_info",
            "gtceu:research_station",
            "create:automatic_*",
            "create:block_cutting",
            "create:deploying",
            "create:draining",
            "create:item_application",
            "create:mystery_conversion",
            "create:sandpaper_polishing",
            "create:sequenced_assembly",
            "createdieselgenerators:distillation",
            "vintage:*",
            "farmersdelight:*",
            "ftbquests:*",
            "jei:*",
            "mysticalagriculture:*",
            "rechiseled:*",
            "systeams:*",
            "thermal:*",
            "exnihilosequentia:*",
            "chipped:*",
            "framedblocks:*"
    );

    public IgnoreRecipeTypeHandler(IRecipeManager recipeManager) {
        super(recipeManager);
    }

    /**
     * Ignored recipe types are not exported, so conversion always returns null.
     */
    @Override
    public RecipeTypeExport convert(IRecipeCategory<?> category) {
        return null;
    }

    /**
     * Checks if the given recipe category should be ignored.
     * Returns true when the recipe type UID matches one of the ignored patterns.
     */
    public static boolean canHandle(IRecipeCategory<?> category) {
        String uid = category.getRecipeType().getUid().toString();
        return IGNORED_PATTERNS.stream().anyMatch(pattern -> matches(uid, pattern));
    }

    /**
     * Checks whether a recipe type UID matches one of the ignored patterns.
     * Exposed so other handlers can reuse the same ignore list.
     */
    public static boolean isIgnoredType(String uid) {
        return IGNORED_PATTERNS.stream().anyMatch(pattern -> matches(uid, pattern));
    }

    private static boolean matches(String uid, String pattern) {
        if (pattern.endsWith("*")) {
            return uid.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return uid.equals(pattern);
    }
}
