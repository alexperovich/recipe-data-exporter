package dev.alperovi.recipedataexporter.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import dev.alperovi.recipedataexporter.handler.CreateRecipeTypeHandler;
import dev.alperovi.recipedataexporter.handler.GregTechRecipeTypeHandler;
import dev.alperovi.recipedataexporter.handler.IgnoreRecipeTypeHandler;
import dev.alperovi.recipedataexporter.handler.MinecraftRecipeTypeHandler;
import dev.alperovi.recipedataexporter.handler.RecipeTypeHandler;
import dev.alperovi.recipedataexporter.model.RecipeTypeExport;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Service for exporting recipe types to JSON format.
 * Coordinates between JEI recipe categories and RecipeTypeExport objects,
 * leveraging pluggable handlers for recipe type-specific conversion logic.
 */
public class RecipeTypeExportService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Registration pairing a predicate that matches recipe categories with a factory
     * that builds the corresponding handler.
     */
    private record HandlerRegistration(
            Predicate<IRecipeCategory<?>> canHandle,
            Function<IRecipeManager, RecipeTypeHandler> factory) {
    }

    /**
     * Ordered list of handlers. The first registration whose predicate matches
     * is used to convert the recipe category.
     */
    private static final List<HandlerRegistration> HANDLERS = List.of(
            new HandlerRegistration(IgnoreRecipeTypeHandler::canHandle, IgnoreRecipeTypeHandler::new),
            new HandlerRegistration(MinecraftRecipeTypeHandler::canHandle, MinecraftRecipeTypeHandler::new),
            new HandlerRegistration(GregTechRecipeTypeHandler::canHandle, GregTechRecipeTypeHandler::new),
            new HandlerRegistration(CreateRecipeTypeHandler::canHandle, CreateRecipeTypeHandler::new)
    );

    private final IRecipeManager recipeManager;
    private final Gson gson;

    public RecipeTypeExportService(IRecipeManager recipeManager) {
        this.recipeManager = recipeManager;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * Exports all available recipe types to RecipeTypeExport objects.
     * Routes each recipe category to the appropriate handler based on recipe type.
     *
     * @return a collection of RecipeTypeExport objects representing all available recipe types
     */
    public Collection<RecipeTypeExport> exportRecipeTypes() {
        List<RecipeTypeExport> exports = new ArrayList<>();

        try {
            // Get all recipe categories from JEI
            var recipeCategoryLookup = recipeManager.createRecipeCategoryLookup();
            recipeCategoryLookup.get().forEach(category -> {
                RecipeTypeExport export = convertCategory(category);
                if (export != null) {
                    exports.add(export);
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to export recipe types: {}", e.getMessage(), e);
        }

        return exports;
    }

    /**
     * Converts a single recipe category to RecipeTypeExport using the appropriate handler.
     *
     * @param category the IRecipeCategory to convert
     * @return a RecipeTypeExport object, or null if conversion fails
     */
    private RecipeTypeExport convertCategory(IRecipeCategory<?> category) {
        try {
            RecipeTypeHandler handler = getHandlerForCategory(category);
            return handler.convert(category);
        } catch (Exception e) {
            LOGGER.warn("Failed to convert recipe category: {}", category.getRecipeType().getUid(), e);
            return null;
        }
    }

    /**
     * Selects the appropriate handler for a recipe category.
     * Enumerates the registered handlers and returns the first whose predicate matches;
     * falls back to a default handler when none match.
     *
     * @param category the recipe category to find a handler for
     * @return a RecipeTypeHandler for the given category
     */
    private RecipeTypeHandler getHandlerForCategory(IRecipeCategory<?> category) {
        return HANDLERS.stream()
                .filter(registration -> registration.canHandle().test(category))
                .findFirst()
                .map(registration -> registration.factory().apply(recipeManager))
                .orElseGet(() -> new RecipeTypeHandler(recipeManager) {
                    @Override
                    protected String getName(IRecipeCategory<?> category) {
                        return super.getName(category) + " (Defaulted)";
                    }
                });
    }

    /**
     * Serializes a collection of RecipeTypeExport objects to JSON and writes to file.
     * Creates the output directory if it does not exist.
     *
     * @param recipeTypes the collection of recipe types to serialize
     * @param outputPath the file path where the JSON should be written
     * @throws IOException if file writing fails
     */
    public void serializeToJson(Collection<RecipeTypeExport> recipeTypes, Path outputPath) throws IOException {
        // Ensure output directory exists
        Path directory = outputPath.getParent();
        if (directory != null && !Files.exists(directory)) {
            Files.createDirectories(directory);
        }

        // Serialize to JSON and write to file
        String json = gson.toJson(recipeTypes);
        Files.writeString(outputPath, json);
    }
}
