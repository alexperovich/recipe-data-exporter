package dev.alperovi.recipedataexporter.handler;

import dev.alperovi.recipedataexporter.export.ExportTables;
import dev.alperovi.recipedataexporter.model.RecipeExport;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.RegistryAccess;

import java.util.List;

/**
 * Recipe data handler for recipe categories that should be ignored and not
 * exported. Matches a category's recipe type UID against its own list of
 * patterns (supporting exact matches and trailing wildcards, e.g. "ae2:*") as
 * well as every pattern ignored by {@link IgnoreRecipeTypeHandler}.
 */
public class IgnoreRecipeDataHandler extends RecipeDataHandler {

    private static final List<String> IGNORED_PATTERNS = List.of(
            "ae2wtlib:*",
            "ae2:*",
            "almostunified:client_recipe_tracker",
            "architects_palette:warping",
            "bucketlib:bucket_dyeing",
            "cb_microblock:microblock",
            "chipped:*",
            "cofh_core:*",
            "createdieselgenerators:wire_cutting",
            "create:emptying",
            "create:filling",
            "create:item_copying",
            "create:toolbox_dyeing",
            "fantasyfurniture:dyeable",
            "fluxnetworks:nbt_wipe_recipe",
            "functionalstorage:*",
            "laserio:cardclear",
            "modularrouters:*",
            "patchouli:shapeless_book_recipe",
            "shetiphiancore:rgb16_colorize",
            "simplybackpacks:backpack_upgrade"
    );

    public IgnoreRecipeDataHandler(ExportTables tables) {
        super(tables);
    }

    /**
     * Ignored categories are not exported, so conversion always returns null.
     */
    @Override
    public RecipeExport convert(IRecipeCategory<?> category, Object recipe, RegistryAccess registryAccess) {
        return null;
    }

    /**
     * Checks whether the recipes of the given category should be ignored.
     * Returns true when the category's recipe type UID matches one of this
     * handler's patterns or any pattern ignored by {@link IgnoreRecipeTypeHandler}.
     */
    public static boolean canHandle(IRecipeCategory<?> category) {
        String uid = category.getRecipeType().getUid().toString();
        if (IGNORED_PATTERNS.stream().anyMatch(pattern -> matches(uid, pattern))) {
            return true;
        }
        return IgnoreRecipeTypeHandler.isIgnoredType(uid);
    }

    private static boolean matches(String uid, String pattern) {
        if (pattern.endsWith("*")) {
            return uid.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return uid.equals(pattern);
    }
}
