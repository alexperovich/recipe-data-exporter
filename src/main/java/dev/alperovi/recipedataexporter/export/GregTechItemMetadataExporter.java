package dev.alperovi.recipedataexporter.export;

import com.gregtechceu.gtceu.api.block.ICoilType;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.recipe.modifier.RecipeModifier;
import com.gregtechceu.gtceu.api.recipe.modifier.RecipeModifierList;
import com.gregtechceu.gtceu.common.block.CoilBlock;
import com.gregtechceu.gtceu.common.item.TurbineRotorBehaviour;
import com.mojang.logging.LogUtils;
import dev.alperovi.recipedataexporter.model.ItemExport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enriches the deduplicated item table with extra, GregTech-specific properties
 * read directly from each item's representative {@link ItemStack}. Heating coils
 * contribute their heat capacity and smelter stats; turbine rotors contribute
 * their efficiency and power. Items with no such properties are left unchanged.
 *
 * <p>All reads operate on already-resolved item data and run on the calling
 * (server) thread; no client render thread is required. Individual failures are
 * logged and skipped so the export still completes.
 */
public class GregTechItemMetadataExporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Sentinel returned by the rotor behaviour when a property is unavailable. */
    private static final int MISSING_ROTOR_VALUE = -1;
    private static final String CUSTOM_TOOLTIPS_NBT_KEY = "custom_tooltips";
    private static final String ABSOLUTE_PARALLEL_TOOLTIP = "Without extra energy consumption";
    private static final Pattern MAX_PARALLEL_TOOLTIP = Pattern.compile("Allows to run up to (\\d+) recipes in parallel\\.?");

    private final ExportTables tables;

    public GregTechItemMetadataExporter(ExportTables tables) {
        this.tables = tables;
    }

    /**
     * Computes and attaches GregTech metadata to every item entry that has any.
     *
     * @return the number of item entries given a non-empty metadata map
     */
    public int enrich() {
        int enriched = 0;
        for (Map.Entry<String, ItemExport> entry : Map.copyOf(tables.items()).entrySet()) {
            String key = entry.getKey();
            ItemExport base = entry.getValue();
            // Tag entries have no single concrete representative to read properties from.
            if (base.tag() != null) {
                continue;
            }
            ItemStack representative = tables.itemRepresentative(key);
            if (representative == null || representative.isEmpty()) {
                continue;
            }
            try {
                Map<String, Object> metadata = computeMetadata(representative, key);
                if (!metadata.isEmpty()) {
                    tables.replaceItem(key, new ItemExport(base.id(), base.tag(), base.nbt(),
                            base.displayName(), base.modName(), base.tooltip(), base.image(), metadata));
                    enriched++;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to compute GregTech metadata for {}: {}", key, e.getMessage());
            }
        }
        return enriched;
    }

    /**
     * Collects all known GregTech properties of the given stack into a map,
     * returning an empty map when the item has none.
     */
    private Map<String, Object> computeMetadata(ItemStack stack, String key) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        LOGGER.debug("Computing Coil metadata for {}", key);
        addCoilMetadata(stack, metadata);
        LOGGER.debug("Computing Rotor metadata for {}", key);
        addRotorMetadata(stack, metadata);
        LOGGER.debug("Computing Machine Recipe Modifiers metadata for {}", key);
        addMachineRecipeModifiers(stack, metadata);
        LOGGER.debug("Computing Parallel Hatch metadata for {}", key);
        addParallelHatchMetadata(stack, metadata);
        LOGGER.debug("Computing Rotor Holder metadata for {}", key);
        addRotorHolderMetadata(stack, metadata);
        return metadata;
    }

    /** Adds the maximum parallel count advertised by parallel hatch item tooltips. */
    private void addParallelHatchMetadata(ItemStack stack, Map<String, Object> metadata) {
        List<Component> tooltip = withoutCustomTooltips(stack).getTooltipLines(null, TooltipFlag.Default.NORMAL);
        for (Component line : tooltip) {
            Matcher matcher = MAX_PARALLEL_TOOLTIP.matcher(line.getString().trim());
            if (matcher.find()) {
                metadata.put("maxParallel", Integer.parseInt(matcher.group(1)));
                if (hasTooltipLine(tooltip, ABSOLUTE_PARALLEL_TOOLTIP)) {
                    metadata.put("isAbsolute", true);
                }
                return;
            }
        }
    }

    private static boolean hasTooltipLine(List<Component> tooltip, String expectedLine) {
        for (Component line : tooltip) {
            if (line.getString().trim().contains(expectedLine)) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack withoutCustomTooltips(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(CUSTOM_TOOLTIPS_NBT_KEY)) {
            return stack;
        }
        ItemStack copy = stack.copy();
        CompoundTag tag = copy.getTag();
        tag.remove(CUSTOM_TOOLTIPS_NBT_KEY);
        if (tag.isEmpty()) {
            copy.setTag(null);
        }
        return copy;
    }

    /**
     * Adds rotor holder properties (tier and the tier-derived maximum rotor
     * speed) for rotor holder part items. Non-rotor-holder items contribute
     * nothing.
     *
     * <p>The holder is identified through the {@link PartAbility#ROTOR_HOLDER}
     * ability registry, and the maximum speed is derived from the machine tier the
     * same way the holder itself computes it ({@code 2000 + 1000 * tier}).
     */
    private void addRotorHolderMetadata(ItemStack stack, Map<String, Object> metadata) {
        if (!(stack.getItem() instanceof MetaMachineItem machineItem)) {
            return;
        }
        MachineDefinition definition = machineItem.getDefinition();
        if (!PartAbility.ROTOR_HOLDER.isApplicable(definition.getBlock())) {
            return;
        }
        int tier = definition.getTier();
        metadata.put("rotorHolderTier", tier);
        metadata.put("maxRotorHolderSpeed", 2000 + 1000 * tier);
    }

    /**
     * Adds the list of recipe-modifier ids for multiblock machine items. Single-block
     * machines and non-machine items contribute nothing.
     *
    * <p>The project compiles against the StarT GTCEu fork, so fork APIs such as
    * {@code RecipeModifier.getId()} and {@code RecipeModifierList.modifiers()} are
    * used directly.
     */
    private void addMachineRecipeModifiers(ItemStack stack, Map<String, Object> metadata) {
        if (!(stack.getItem() instanceof MetaMachineItem machineItem)) {
            return;
        }
        MachineDefinition definition = machineItem.getDefinition();
        if (!(definition instanceof MultiblockMachineDefinition)) {
            return;
        }
        RecipeModifier modifier = definition.getRecipeModifier();
        if (modifier == null) {
            return;
        }
        List<RecipeModifier> flattened = new ArrayList<>();
        flattenModifiers(modifier, flattened);
        List<String> ids = new ArrayList<>();
        for (RecipeModifier each : flattened) {
            String id = modifierId(each);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
        if (!ids.isEmpty()) {
            metadata.put("recipeModifiers", ids);
        }
    }

    /**
     * Recursively expands a {@link RecipeModifierList} into its individual modifiers.
     */
    private void flattenModifiers(RecipeModifier modifier, List<RecipeModifier> out) {
        if (modifier instanceof RecipeModifierList list) {
            RecipeModifier[] modifiers = list.modifiers();
            for (RecipeModifier child : modifiers) {
                flattenModifiers(child, out);
            }
            return;
        }
        out.add(modifier);
    }

    /**
     * Resolves a modifier's stable id.
     */
    private String modifierId(RecipeModifier modifier) {
        return modifier.getId();
    }

    /**
     * Adds heating-coil properties (heat capacity, smelter level, energy discount
     * and tier) when the stack is a heating coil block item.
     */
    private void addCoilMetadata(ItemStack stack, Map<String, Object> metadata) {
        if (stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof CoilBlock coilBlock) {
            ICoilType coilType = coilBlock.coilType;
            if (coilType != null) {
                metadata.put("baseHeatCapacity", coilType.getCoilTemperature());
                metadata.put("smelterLevel", coilType.getLevel());
                metadata.put("energyDiscount", coilType.getEnergyDiscount());
                metadata.put("coilTier", coilType.getTier());
            }
        }
    }

    /**
     * Adds turbine rotor properties (efficiency and power) when the stack is a
     * turbine rotor carrying a material with rotor stats.
     */
    private void addRotorMetadata(ItemStack stack, Map<String, Object> metadata) {
        TurbineRotorBehaviour behaviour = TurbineRotorBehaviour.getBehaviour(stack);
        if (behaviour == null) {
            return;
        }
        int efficiency = behaviour.getRotorEfficiency(stack);
        if (efficiency != MISSING_ROTOR_VALUE) {
            metadata.put("turbineEfficiency", efficiency);
        }
        int power = behaviour.getRotorPower(stack);
        if (power != MISSING_ROTOR_VALUE) {
            metadata.put("turbinePower", power);
        }
    }
}
