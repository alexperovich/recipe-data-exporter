package dev.alperovi.recipedataexporter.model;

/**
 * A per-recipe reference to an item, carrying the recipe-specific count and
 * probability. The {@code key} resolves the unique item data in the global item
 * table (see {@link ItemExport}).
 */
public record ItemStackExport(
        String key,
        int count,
        Long probability
) {
}
