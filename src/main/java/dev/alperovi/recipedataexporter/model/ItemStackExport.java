package dev.alperovi.recipedataexporter.model;

/**
 * A per-recipe reference to an item, carrying the recipe-specific count and,
 * for chanced outputs, the output probability and per-voltage-tier chance
 * boost. Both {@code probability} and {@code tierChanceBoost} are expressed out
 * of 10000 (100%) to match GregTech and are null when not applicable (e.g. for
 * inputs, guaranteed outputs, or outputs with no tier boost). {@code key}
 * resolves the unique item data in the global item table (see {@link ItemExport}).
 */
public record ItemStackExport(
        String key,
        int count,
        Long probability,
        Long tierChanceBoost
) {
}
