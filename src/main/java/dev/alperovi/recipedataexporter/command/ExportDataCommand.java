package dev.alperovi.recipedataexporter.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.alperovi.recipedataexporter.DataExportPlugin;
import dev.alperovi.recipedataexporter.export.RecipeDataExportService;
import dev.alperovi.recipedataexporter.export.RecipeTypeExportService;
import dev.alperovi.recipedataexporter.model.RecipeExport;
import dev.alperovi.recipedataexporter.model.RecipeTypeExport;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Command: /export-data
 * Exports recipe types from JEI to a JSON file.
 * Usage: /export-data
 */
public class ExportDataCommand {

    /**
     * Registers the /export-data command.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("export-data")
                .requires(commandSource -> commandSource.hasPermission(2))
                .executes(context -> exportData(context.getSource()));
    }

    /**
     * Executes the export-data command.
     * Fetches recipe types from JEI and serializes them to local/export/recipeTypes.json
     */
    private static int exportData(CommandSourceStack source) {
        try {
            // Get JEI runtime
            var jeiRuntime = DataExportPlugin.getJeiRuntime();
            if (jeiRuntime == null) {
                source.sendFailure(Component.literal("JEI not available yet. Try again after the world has fully loaded."));
                return 0;
            }

            RegistryAccess registryAccess = source.registryAccess();

            source.sendSuccess(() -> Component.literal("Exporting recipe types..."), false);

            // Create export service
            RecipeTypeExportService exportService = new RecipeTypeExportService(jeiRuntime.getRecipeManager());

            // Export recipe types
            Collection<RecipeTypeExport> recipeTypes = exportService.exportRecipeTypes();
            source.sendSuccess(() -> Component.literal("Found " + recipeTypes.size() + " recipe types."), false);

            // Determine output path (relative to game directory)
            Path gameDir = Paths.get("").toAbsolutePath();
            Path outputDir = gameDir.resolve("local/export");
            Path outputPath = outputDir.resolve("recipeTypes.json");

            // Serialize to JSON
            exportService.serializeToJson(recipeTypes, outputPath);

            source.sendSuccess(() -> Component.literal("Successfully exported recipe types to: " + outputPath), true);

            // Export recipe data sourced live from JEI
            source.sendSuccess(() -> Component.literal("Exporting recipe data..."), false);

            RecipeDataExportService recipeDataService = new RecipeDataExportService(jeiRuntime.getRecipeManager());
            Map<String, List<RecipeExport>> recipesByType = recipeDataService.exportRecipes(registryAccess);

            int recipeCount = recipesByType.values().stream().mapToInt(List::size).sum();
            source.sendSuccess(() -> Component.literal(
                    "Converted " + recipeCount + " recipes across " + recipesByType.size() + " recipe types."), false);

            Path recipeOutputDir = outputDir.resolve("recipes");
            recipeDataService.serializeToJson(recipesByType, recipeOutputDir);

            // Expand referenced tags so every member appears as a concrete item/fluid entry
            int memberCount = recipeDataService.expandReferencedTags();
            source.sendSuccess(() -> Component.literal("Expanded tag members: " + memberCount + "."), false);

            // Attach GregTech-specific item properties (coil heat capacity, rotor stats, etc.)
            int metadataCount = recipeDataService.extractItemMetadata();
            source.sendSuccess(() -> Component.literal("Added metadata to " + metadataCount + " items."), false);

            // Enrich item/fluid tables with display name, mod name, tooltip and JEI icons
            source.sendSuccess(() -> Component.literal("Exporting item and fluid metadata and icons..."), false);
            int iconCount = recipeDataService.exportIngredientMetadata(
                    jeiRuntime.getIngredientManager(), outputDir);
            source.sendSuccess(() -> Component.literal("Rendered " + iconCount + " icons."), false);

            recipeDataService.serializeTables(outputDir);

            // Resolve and export referenced item/fluid tags as JSON arrays of their contents
            source.sendSuccess(() -> Component.literal("Exporting referenced tags..."), false);
            int tagCount = recipeDataService.serializeTags(outputDir);
            source.sendSuccess(() -> Component.literal("Exported " + tagCount + " tags."), false);

            source.sendSuccess(() -> Component.literal("Successfully exported recipe data to: " + recipeOutputDir), true);
            return Command.SINGLE_SUCCESS;

        } catch (Exception e) {
            source.sendFailure(Component.literal("Error exporting recipe types: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }
}
