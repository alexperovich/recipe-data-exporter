package dev.alperovi.recipedataexporter.model;

import java.util.List;

/** Tooltip text for one GTCEu recipe modifier section. */
public record RecipeModifierTooltipExport(
        String id,
        String name,
        List<String> description
) {
}