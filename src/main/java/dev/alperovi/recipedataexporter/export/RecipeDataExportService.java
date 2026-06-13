package dev.alperovi.recipedataexporter.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import dev.alperovi.recipedataexporter.handler.CreateRecipeDataHandler;
import dev.alperovi.recipedataexporter.handler.GenericRecipeDataHandler;
import dev.alperovi.recipedataexporter.handler.GregTechRecipeDataHandler;
import dev.alperovi.recipedataexporter.handler.IgnoreRecipeDataHandler;
import dev.alperovi.recipedataexporter.handler.MinecraftRecipeDataHandler;
import dev.alperovi.recipedataexporter.handler.RecipeDataHandler;
import dev.alperovi.recipedataexporter.model.RecipeExport;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.tags.ITag;
import net.minecraftforge.registries.tags.ITagManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Service for exporting recipe data sourced live from JEI. Iterates over every
 * JEI recipe category, converts each recipe via pluggable
 * {@link RecipeDataHandler}s, groups the results by recipe type id, and writes
 * one file per recipe type into a namespaced output directory.
 */
public class RecipeDataExportService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Registration pairing a predicate that matches a recipe category with a
     * factory that builds the corresponding handler from the shared export tables.
     */
    private record HandlerRegistration(
            Predicate<IRecipeCategory<?>> canHandle,
            Function<ExportTables, RecipeDataHandler> factory) {
    }

    /**
     * Ordered list of handlers. The first registration whose predicate matches
     * the recipe's category is used to convert it.
     */
    private static final List<HandlerRegistration> HANDLERS = List.of(
            new HandlerRegistration(IgnoreRecipeDataHandler::canHandle, IgnoreRecipeDataHandler::new),
            new HandlerRegistration(MinecraftRecipeDataHandler::canHandle, MinecraftRecipeDataHandler::new),
            new HandlerRegistration(CreateRecipeDataHandler::canHandle, CreateRecipeDataHandler::new),
            new HandlerRegistration(GregTechRecipeDataHandler::canHandle, GregTechRecipeDataHandler::new)
    );

    private final IRecipeManager recipeManager;
    private final Gson gson;
    private ExportTables tables = new ExportTables();

    public RecipeDataExportService(IRecipeManager recipeManager) {
        this.recipeManager = recipeManager;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * Converts every recipe in every non-ignored JEI category into a
     * {@link RecipeExport}, grouped by the exported recipe type id.
     *
     * @param registryAccess the registry access used to resolve recipe results
     * @return a map of recipe type id to the list of recipes of that type
     */
    public Map<String, List<RecipeExport>> exportRecipes(RegistryAccess registryAccess) {
        Map<String, List<RecipeExport>> grouped = new LinkedHashMap<>();
        this.tables = new ExportTables();

        try {
            recipeManager.createRecipeCategoryLookup().get().forEach(category -> {
                if (IgnoreRecipeDataHandler.canHandle(category)) {
                    return;
                }
                RecipeDataHandler handler = getHandlerForCategory(category);
                exportCategory(category, handler, registryAccess, grouped);
            });
        } catch (Exception e) {
            LOGGER.error("Failed to export recipes: {}", e.getMessage(), e);
        }

        return grouped;
    }

    /**
     * Converts every recipe of a single category and adds the results to the
     * grouped map. The type parameter captures the category's recipe type so the
     * recipe lookup is correctly typed. Individual conversion failures are logged
     * and skipped.
     */
    private <T> void exportCategory(IRecipeCategory<T> category, RecipeDataHandler handler,
                                    RegistryAccess registryAccess, Map<String, List<RecipeExport>> grouped) {
        try {
            recipeManager.createRecipeLookup(category.getRecipeType())
                    .includeHidden()
                    .get()
                    .forEach(recipe -> {
                        try {
                            RecipeExport export = handler.convert(category, recipe, registryAccess);
                            if (export != null) {
                                grouped.computeIfAbsent(export.type(), key -> new ArrayList<>()).add(export);
                            }
                        } catch (Exception e) {
                            LOGGER.warn("Failed to convert recipe in category {}",
                                    category.getRecipeType().getUid(), e);
                        }
                    });
        } catch (Exception e) {
            LOGGER.warn("Failed to look up recipes for category {}",
                    category.getRecipeType().getUid(), e);
        }
    }

    /**
     * Selects the first registered handler that can handle the given category,
     * falling back to {@link GenericRecipeDataHandler} when none match.
     */
    private RecipeDataHandler getHandlerForCategory(IRecipeCategory<?> category) {
        return HANDLERS.stream()
                .filter(registration -> registration.canHandle().test(category))
                .findFirst()
                .map(registration -> registration.factory().apply(tables))
                .orElseGet(() -> new GenericRecipeDataHandler(tables));
    }

    /**
     * Writes each recipe type group to its own JSON file under the output
     * directory. The recipe type id {@code <namespace>:<path>} maps to the file
     * {@code <outputDir>/<namespace>/<path>.json}.
     *
     * @param grouped   the grouped recipe exports
     * @param outputDir the base output directory (e.g. local/export/recipes)
     * @throws IOException if a file cannot be written
     */
    public void serializeToJson(Map<String, List<RecipeExport>> grouped, Path outputDir) throws IOException {
        for (Map.Entry<String, List<RecipeExport>> entry : grouped.entrySet()) {
            Path outputPath = resolveOutputPath(outputDir, entry.getKey());

            Path directory = outputPath.getParent();
            if (directory != null && !Files.exists(directory)) {
                Files.createDirectories(directory);
            }

            String json = gson.toJson(entry.getValue());
            Files.writeString(outputPath, json);
        }
    }

    /**
     * Writes the deduplicated item and fluid lookup tables collected during the
     * last {@link #exportRecipes} run to {@code items.json} and {@code fluids.json}
     * under the given directory. Recipes reference these tables by their keys.
     *
     * @param outputDir the directory to write the table files into
     * @throws IOException if a file cannot be written
     */
    public void serializeTables(Path outputDir) throws IOException {
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
        Files.writeString(outputDir.resolve("items.json"), gson.toJson(tables.items()));
        Files.writeString(outputDir.resolve("fluids.json"), gson.toJson(tables.fluids()));
    }

    /**
     * Enriches the deduplicated item and fluid tables collected during the last
     * {@link #exportRecipes} run with display name, mod name, tooltip and a 64x64
     * PNG icon (written under {@code <outputDir>/images}). Must be called before
     * {@link #serializeTables} so the enriched data is included in the output.
     *
     * @param ingredientManager the JEI ingredient manager used to resolve metadata and renderers
     * @param outputDir         the export base directory (icons go under {@code images/})
     * @return the number of icons successfully written
     */
    public int exportIngredientMetadata(IIngredientManager ingredientManager, Path outputDir) {
        return new IngredientMetadataExporter(ingredientManager).enrich(tables, outputDir);
    }

    /**
     * Attaches GregTech-specific properties (e.g. heating-coil heat capacity and
     * turbine rotor stats) to the item entries collected during the last
     * {@link #exportRecipes} run. Must be called before {@link #serializeTables}
     * so the metadata is included in the output.
     *
     * @return the number of item entries given a non-empty metadata map
     */
    public int extractItemMetadata() {
        return new GregTechItemMetadataExporter(tables).enrich();
    }

    /**
     * Resolves every item and fluid tag referenced by the converted recipes and
     * interns each of their members as a concrete item or fluid entry in the
     * tables. This ensures that all members of every referenced tag appear in the
     * exported item and fluid lists (and are enriched), in addition to the tag
     * entry itself. Must be called before {@link #exportIngredientMetadata} so
     * the newly added members are enriched. Unknown or unparseable tags are
     * logged and skipped.
     *
     * @return the number of member entries interned (including duplicates already present)
     */
    public int expandReferencedTags() {
        return expandItemTags() + expandFluidTags();
    }

    /**
     * Interns every member of each referenced item tag as a concrete item entry.
     * Returns the number of members processed.
     */
    private int expandItemTags() {
        ITagManager<Item> tagManager = ForgeRegistries.ITEMS.tags();
        if (tagManager == null) {
            LOGGER.warn("Item tag manager unavailable; skipping item tag expansion");
            return 0;
        }
        int count = 0;
        for (String tag : tables.itemTags()) {
            ResourceLocation location = ResourceLocation.tryParse(tag);
            if (location == null) {
                continue;
            }
            TagKey<Item> key = tagManager.createTagKey(location);
            if (!tagManager.isKnownTagName(key)) {
                continue;
            }
            for (Item item : tagManager.getTag(key)) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id != null) {
                    tables.internItem(id.toString(), null, null, new ItemStack(item));
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Interns every member of each referenced fluid tag as a concrete fluid entry.
     * Returns the number of members processed.
     */
    private int expandFluidTags() {
        ITagManager<Fluid> tagManager = ForgeRegistries.FLUIDS.tags();
        if (tagManager == null) {
            LOGGER.warn("Fluid tag manager unavailable; skipping fluid tag expansion");
            return 0;
        }
        int count = 0;
        for (String tag : tables.fluidTags()) {
            ResourceLocation location = ResourceLocation.tryParse(tag);
            if (location == null) {
                continue;
            }
            TagKey<Fluid> key = tagManager.createTagKey(location);
            if (!tagManager.isKnownTagName(key)) {
                continue;
            }
            for (Fluid fluid : tagManager.getTag(key)) {
                ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
                if (id != null) {
                    tables.internFluid(id.toString(), null, new FluidStack(fluid, FluidType.BUCKET_VOLUME));
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Resolves every item and fluid tag referenced by the converted recipes to
     * the concrete registry ids it contains and writes each as a JSON array. A
     * tag {@code <namespace>:<path>} is written to
     * {@code <outputDir>/tags/items/<namespace>/<path>.json} (or {@code fluids}
     * for fluid tags). Unknown or unparseable tags are logged and skipped.
     *
     * @param outputDir the export base directory (tags go under {@code tags/})
     * @return the number of tag files written
     * @throws IOException if a file cannot be written
     */
    public int serializeTags(Path outputDir) throws IOException {
        Path tagsDir = outputDir.resolve("tags");
        int written = 0;
        written += serializeItemTags(tagsDir.resolve("items"));
        written += serializeFluidTags(tagsDir.resolve("fluids"));
        return written;
    }

    /**
     * Resolves and writes every referenced item tag. Returns the number written.
     */
    private int serializeItemTags(Path itemsDir) throws IOException {
        ITagManager<Item> tagManager = ForgeRegistries.ITEMS.tags();
        if (tagManager == null) {
            LOGGER.warn("Item tag manager unavailable; skipping item tag export");
            return 0;
        }
        int written = 0;
        for (String tag : tables.itemTags()) {
            ResourceLocation location = ResourceLocation.tryParse(tag);
            if (location == null) {
                LOGGER.warn("Skipping unparseable item tag id: {}", tag);
                continue;
            }
            TagKey<Item> key = tagManager.createTagKey(location);
            if (!tagManager.isKnownTagName(key)) {
                LOGGER.warn("Skipping unknown item tag: {}", tag);
                continue;
            }
            ITag<Item> resolved = tagManager.getTag(key);
            List<String> contents = new ArrayList<>();
            for (Item item : resolved) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id != null) {
                    contents.add(id.toString());
                }
            }
            writeTagFile(itemsDir, location, contents);
            written++;
        }
        return written;
    }

    /**
     * Resolves and writes every referenced fluid tag. Returns the number written.
     */
    private int serializeFluidTags(Path fluidsDir) throws IOException {
        ITagManager<Fluid> tagManager = ForgeRegistries.FLUIDS.tags();
        if (tagManager == null) {
            LOGGER.warn("Fluid tag manager unavailable; skipping fluid tag export");
            return 0;
        }
        int written = 0;
        for (String tag : tables.fluidTags()) {
            ResourceLocation location = ResourceLocation.tryParse(tag);
            if (location == null) {
                LOGGER.warn("Skipping unparseable fluid tag id: {}", tag);
                continue;
            }
            TagKey<Fluid> key = tagManager.createTagKey(location);
            if (!tagManager.isKnownTagName(key)) {
                LOGGER.warn("Skipping unknown fluid tag: {}", tag);
                continue;
            }
            ITag<Fluid> resolved = tagManager.getTag(key);
            List<String> contents = new ArrayList<>();
            for (Fluid fluid : resolved) {
                ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
                if (id != null) {
                    contents.add(id.toString());
                }
            }
            writeTagFile(fluidsDir, location, contents);
            written++;
        }
        return written;
    }

    /**
     * Writes the resolved contents of a tag to
     * {@code <baseDir>/<namespace>/<path>.json}, creating parent directories as
     * needed.
     */
    private void writeTagFile(Path baseDir, ResourceLocation tag, List<String> contents) throws IOException {
        Path outputPath = baseDir.resolve(tag.getNamespace()).resolve(tag.getPath() + ".json");
        Path directory = outputPath.getParent();
        if (directory != null && !Files.exists(directory)) {
            Files.createDirectories(directory);
        }
        Files.writeString(outputPath, gson.toJson(contents));
    }

    /**
     * Resolves the output file path for a recipe type id, splitting the namespace
     * into a subdirectory: {@code minecraft:crafting} -> {@code minecraft/crafting.json}.
     */
    private Path resolveOutputPath(Path outputDir, String recipeTypeId) {
        String relative = recipeTypeId.replace(':', '/');
        return outputDir.resolve(relative + ".json");
    }
}
