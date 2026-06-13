package dev.alperovi.recipedataexporter.model;

/**
 * A per-recipe reference to a fluid, carrying the recipe-specific amount. The
 * {@code key} resolves the unique fluid data in the global fluid table (see
 * {@link FluidExport}).
 */
public record FluidStackExport(
        String key,
        long amount
) {
}
