package dev.alperovi.recipedataexporter.model;

public record ItemIngredientExport(
        String item,
        String tag,
        int count,
        Long probability
) {
}
