package dev.alperovi.recipedataexporter.export;

import com.gregtechceu.gtceu.api.GTValues;
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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                Map<String, Object> metadata = computeMetadata(representative);
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
    private Map<String, Object> computeMetadata(ItemStack stack) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        addCoilMetadata(stack, metadata);
        addRotorMetadata(stack, metadata);
        addMachineRecipeModifiers(stack, metadata);
        addParallelHatchMetadata(stack, metadata);
        addRotorHolderMetadata(stack, metadata);
        return metadata;
    }

    /**
     * Adds the maximum parallel count for parallel control hatch items. Non-hatch
     * items contribute nothing.
     *
     * <p>The hatch is identified through the {@link PartAbility#PARALLEL_HATCH}
     * ability registry, and the count is derived from the machine tier the same way
     * the hatch itself computes it ({@code 4^(tier - EV)}).
     */
    private void addParallelHatchMetadata(ItemStack stack, Map<String, Object> metadata) {
        if (!(stack.getItem() instanceof MetaMachineItem machineItem)) {
            return;
        }
        MachineDefinition definition = machineItem.getDefinition();
        if (!PartAbility.PARALLEL_HATCH.isApplicable(definition.getBlock())) {
            return;
        }
        int maxParallel = (int) Math.pow(4, definition.getTier() - GTValues.EV);
        if (maxParallel > 0) {
            metadata.put("maxParallel", maxParallel);
        }
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
     * <p>The runtime (a GregTech fork) exposes {@code RecipeModifier.getId()} and models
     * {@code RecipeModifierList} as a record, neither of which exist in the upstream
     * artifact this is compiled against; both are therefore accessed reflectively so the
     * code compiles upstream yet runs correctly against the fork.
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
     * The {@code modifiers} field is read reflectively because it is exposed via a
     * {@code getModifiers()} method upstream but a record accessor in the fork; the
     * backing field is named {@code modifiers} in both.
     */
    private void flattenModifiers(RecipeModifier modifier, List<RecipeModifier> out) {
        if (modifier instanceof RecipeModifierList list) {
            try {
                Field field = RecipeModifierList.class.getDeclaredField("modifiers");
                field.setAccessible(true);
                Object value = field.get(list);
                if (value instanceof RecipeModifier[] array) {
                    for (RecipeModifier child : array) {
                        flattenModifiers(child, out);
                    }
                    return;
                }
            } catch (ReflectiveOperationException e) {
                LOGGER.warn("Failed to read recipe modifier list contents: {}", e.getMessage());
            }
        }
        out.add(modifier);
    }

    /**
     * Resolves a modifier's stable id via the fork-only {@code getId()} method,
     * falling back to the class simple name when that method is unavailable.
     */
    private String modifierId(RecipeModifier modifier) {
        try {
            Object id = modifier.getClass().getMethod("getId").invoke(modifier);
            if (id != null) {
                return id.toString();
            }
        } catch (ReflectiveOperationException e) {
            // Upstream lacks getId(); fall through to the class name.
        }
        return modifier.getClass().getSimpleName();
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
