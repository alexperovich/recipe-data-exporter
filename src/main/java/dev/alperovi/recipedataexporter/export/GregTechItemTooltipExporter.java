package dev.alperovi.recipedataexporter.export;

import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.modifier.RecipeModifier;
import com.gregtechceu.gtceu.api.recipe.modifier.RecipeModifierList;
import com.gregtechceu.gtceu.common.data.GTRecipeModifiers;
import com.gregtechceu.gtceu.data.lang.LangHandler;
import com.mojang.logging.LogUtils;
import dev.alperovi.recipedataexporter.model.ItemTooltipExport;
import dev.alperovi.recipedataexporter.model.RecipeModifierTooltipExport;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** Extracts GTCEu machine tooltip sections that are not visible in the default item tooltip. */
public class GregTechItemTooltipExporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    public ItemTooltipExport enrich(ItemStack stack, ItemTooltipExport base, String key) {
        if (!(stack.getItem() instanceof MetaMachineItem machineItem)) {
            return base;
        }
        try {
            MachineDefinition definition = machineItem.getDefinition();
            List<RecipeModifierTooltipExport> modifierSections = modifierSections(definition);
            List<List<String>> pages = pages(definition);
            if (modifierSections == null && pages == null) {
                return base;
            }
            return new ItemTooltipExport(defaultLines(base.defaultLines(), pages), modifierSections, pages);
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve GTCEu machine tooltip data for {}: {}", key, e.getMessage());
            return base;
        }
    }

    private List<String> defaultLines(List<String> lines, List<List<String>> pages) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        String breakerLine = Component.translatable("gtceu.universal.tooltip.breaker").getString();
        String showCapabilities = Component.translatable("gtceu.tooltip.show_capabilities").getString();

        List<String> filtered = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.equals(breakerLine)) {
                int pageLength = matchingPageLength(lines, i + 1, pages);
                if (pageLength > 0) {
                    i += pageLength;
                    if (i + 1 < lines.size() && isPaginatedInfo(lines.get(i + 1))) {
                        i++;
                    }
                    continue;
                }
                if (i + 1 < lines.size() && lines.get(i + 1).equals(showCapabilities)) {
                    i++;
                    continue;
                }
            }
            if (line.equals(showCapabilities) || isPaginatedInfo(line)) {
                continue;
            }
            filtered.add(line);
        }
        return filtered;
    }

    private static int matchingPageLength(List<String> lines, int start, List<List<String>> pages) {
        if (pages == null || pages.isEmpty()) {
            return 0;
        }
        for (List<String> page : pages) {
            if (matchesAt(lines, start, page)) {
                return page.size();
            }
        }
        return 0;
    }

    private static boolean matchesAt(List<String> lines, int start, List<String> expected) {
        if (expected.isEmpty() || start + expected.size() > lines.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(lines.get(start + i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPaginatedInfo(String line) {
        return line.matches(".*Page \\d+/\\d+.*");
    }

    private List<RecipeModifierTooltipExport> modifierSections(MachineDefinition definition) {
        RecipeModifierList modifierList = definition.getRecipeModifier();
        if (modifierList == null) {
            return null;
        }
        List<RecipeModifier> modifiers = new ArrayList<>();
        flattenModifiers(modifierList, modifiers);
        List<RecipeModifierTooltipExport> sections = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RecipeModifier modifier : modifiers) {
            String id = modifier.getId();
            if (!includeModifier(id) || !seen.add(id)) {
                continue;
            }
            sections.add(new RecipeModifierTooltipExport(id,
                    Component.translatable("gtceu.modifier.%s.name".formatted(id), "").getString(),
                    strings(LangHandler.getSingleOrMultiLang("gtceu.modifier.%s.description".formatted(id)))));
        }
        return sections.isEmpty() ? null : sections;
    }

    private static boolean includeModifier(String id) {
        return id != null
                && !GTRecipeModifiers.ignoreModifiers.contains(id)
                && !id.contains("lambda")
                && !id.contains("proxy");
    }

    private List<List<String>> pages(MachineDefinition definition) {
        List<List<Component>> paginatedTooltips = definition.getPaginatedTooltips();
        if (paginatedTooltips == null || paginatedTooltips.isEmpty()) {
            paginatedTooltips = capturedPaginatedTooltips(definition.getTooltipBuilder());
        }
        if (paginatedTooltips == null || paginatedTooltips.isEmpty()) {
            return null;
        }
        List<List<String>> pages = new ArrayList<>();
        for (List<Component> page : paginatedTooltips) {
            List<String> lines = strings(page);
            if (!lines.isEmpty()) {
                pages.add(lines);
            }
        }
        return pages.isEmpty() ? null : pages;
    }

    private void flattenModifiers(RecipeModifier modifier, List<RecipeModifier> out) {
        if (modifier instanceof RecipeModifierList list) {
            for (RecipeModifier child : list.modifiers()) {
                flattenModifiers(child, out);
            }
            return;
        }
        out.add(modifier);
    }

    private List<List<Component>> capturedPaginatedTooltips(BiConsumer<ItemStack, List<Component>> tooltipBuilder) {
        if (tooltipBuilder == null) {
            return null;
        }
        return capturedPaginatedTooltips(tooltipBuilder, new IdentityHashMap<>(), 0);
    }

    @SuppressWarnings("unchecked")
    private List<List<Component>> capturedPaginatedTooltips(Object value, Map<Object, Boolean> seen, int depth) {
        if (value == null || depth > 4 || seen.put(value, Boolean.TRUE) != null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return isComponentPageList(list) ? (List<List<Component>>) value : null;
        }
        Class<?> type = value.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    List<List<Component>> found = capturedPaginatedTooltips(field.get(value), seen, depth + 1);
                    if (found != null) {
                        return found;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Lambdas use private synthetic fields; inaccessible fields just mean no captured pages here.
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static boolean isComponentPageList(List<?> list) {
        if (list.isEmpty()) {
            return false;
        }
        for (Object page : list) {
            if (!(page instanceof List<?> pageLines)) {
                return false;
            }
            for (Object line : pageLines) {
                if (!(line instanceof Component)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<String> strings(List<? extends Component> components) {
        List<String> lines = new ArrayList<>(components.size());
        for (Component component : components) {
            lines.add(component.getString());
        }
        return lines;
    }
}