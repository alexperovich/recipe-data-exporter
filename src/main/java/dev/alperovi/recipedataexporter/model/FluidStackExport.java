package dev.alperovi.recipedataexporter.model;

/**
 * A per-recipe reference to a fluid, carrying the recipe-specific amount and,
 * for chanced outputs, the output probability and per-voltage-tier chance
 * boost. Both {@code probability} and {@code tierChanceBoost} are expressed out
 * of 10000 (100%) to match GregTech and are null when not applicable (e.g. for
 * inputs, guaranteed outputs, or outputs with no tier boost). {@code key}
 * resolves the unique fluid data in the global fluid table (see {@link FluidExport}).
 */
public record FluidStackExport(
        String key,
        long amount,
        Long probability,
        Long tierChanceBoost
) {
}
