package dev.alperovi.recipedataexporter.model;

import java.util.List;
import java.util.Map;

/**
 * The unique, deduplicated data describing an item or item tag referenced by a
 * recipe. Stored once per distinct (id/tag, nbt) combination in the global item
 * table and referenced from recipes by its lookup key (see {@link ItemStackExport}).
 *
 * <p>The {@code displayName}, {@code modName}, {@code tooltip} and {@code image}
 * fields are enriched after recipe conversion from the JEI ingredient helpers and
 * renderer; they are null/absent when the entry could not be resolved (e.g. a
 * tag with no matching items, or when the icon failed to render).
 *
 * <p>The {@code metadata} map carries extra, source-specific properties of the
 * item (for example GregTech heating-coil heat capacity or turbine rotor stats);
 * it is null when the item has no such properties.
 */
public record ItemExport(
        String id,
        String tag,
        String nbt,
        String displayName,
        String modName,
        List<String> tooltip,
        String image,
        Map<String, Object> metadata
) {
}
