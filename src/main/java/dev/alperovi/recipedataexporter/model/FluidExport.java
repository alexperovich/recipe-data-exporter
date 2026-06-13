package dev.alperovi.recipedataexporter.model;

import java.util.List;

/**
 * The unique, deduplicated data describing a fluid or fluid tag referenced by a
 * recipe. Stored once per distinct id/tag in the global fluid table and
 * referenced from recipes by its lookup key (see {@link FluidStackExport}).
 *
 * <p>The {@code displayName}, {@code modName}, {@code tooltip} and {@code image}
 * fields are enriched after recipe conversion from the JEI ingredient helpers and
 * renderer; they are null/absent when the entry could not be resolved or rendered.
 */
public record FluidExport(
        String id,
        String tag,
        String displayName,
        String modName,
        List<String> tooltip,
        String image
) {
}
