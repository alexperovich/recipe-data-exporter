package dev.alperovi.recipedataexporter.model;

import java.util.List;

/**
 * Structured item tooltip data. {@code defaultLines} is the normal item tooltip;
 * GTCEu machine entries may also include recipe modifier sections and long
 * tooltip pages for website-side navigation.
 */
public record ItemTooltipExport(
        List<String> defaultLines,
        List<RecipeModifierTooltipExport> modifierSections,
        List<List<String>> pages
) {
}