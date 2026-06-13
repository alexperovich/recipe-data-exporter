package dev.alperovi.recipedataexporter.model;

import java.util.Map;

public record RecipeExport(
        String id,
        String type,
        String fullTypeName,
        Map<String, Object> data,
        long duration,
        long voltage,
        Map<String, ItemStackExport> itemInputs,
        Map<String, FluidStackExport> fluidInputs,
        Map<String, ItemStackExport> itemOutputs,
        Map<String, FluidStackExport> fluidOutputs
) {
}
