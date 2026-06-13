package dev.alperovi.recipedataexporter.model;

public record FluidIngredientExport(
        String fluid,
        String tag,
        long amount
) {
}
