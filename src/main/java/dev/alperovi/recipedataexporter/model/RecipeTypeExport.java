package dev.alperovi.recipedataexporter.model;

import java.util.List;

public record RecipeTypeExport(
        String id,
        String name,
        String fullTypeName,
        List<String> crafters,
        int[] itemInputDimensions,
        int[] itemOutputDimensions,
        int[] fluidInputDimensions,
        int[] fluidOutputDimensions
) {
}
