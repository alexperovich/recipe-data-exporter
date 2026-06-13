package dev.alperovi.recipedataexporter.model;

import java.util.Map;

public record RecipeExport(
        String id,
        String type,
        String fullTypeName,
        Map<String, Object> data,
        long duration,
        long voltage,
        Map<String, ItemIngredientExport> itemInputs,
        Map<String, FluidIngredientExport> fluidInputs,
        Map<String, ItemIngredientExport> itemOutputs,
        Map<String, FluidIngredientExport> fluidOutputs
) {
}
