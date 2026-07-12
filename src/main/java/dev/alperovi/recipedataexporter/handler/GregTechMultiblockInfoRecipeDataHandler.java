package dev.alperovi.recipedataexporter.handler;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.MultiblockShapeInfo;
import com.gregtechceu.gtceu.integration.jei.multipage.MultiblockInfoWrapper;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import dev.alperovi.recipedataexporter.export.ExportTables;
import dev.alperovi.recipedataexporter.model.FluidStackExport;
import dev.alperovi.recipedataexporter.model.ItemStackExport;
import dev.alperovi.recipedataexporter.model.RecipeExport;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipe data handler for GregTech (gtceu) multiblock preview entries, exposed by
 * JEI as the {@code gtceu:multiblock_info} recipe type. Each such "recipe" is a
 * {@link MultiblockInfoWrapper} wrapping a {@link MultiblockMachineDefinition};
 * the exported recipe is the set of blocks that make up the multiblock's first
 * matching structure, emitted as item inputs with per-block counts.
 *
 * <p>The block list is read straight from the definition via
 * {@link MultiblockMachineDefinition#getMatchingShapes()} (the same source the
 * client-only pattern preview widget uses) rather than the widget itself, so no
 * client rendering state or reflection is required. Block NBT is ignored: blocks
 * are grouped purely by their item id.</p>
 */
public class GregTechMultiblockInfoRecipeDataHandler extends RecipeDataHandler {

    private static final String RECIPE_TYPE = "gtceu:multiblock_info";

    public GregTechMultiblockInfoRecipeDataHandler(ExportTables tables) {
        super(tables);
    }

    @Override
    public RecipeExport convert(IRecipeCategory<?> category, Object recipe, RegistryAccess registryAccess) {
        Object unwrapped = unwrap(recipe);
        if (!(unwrapped instanceof MultiblockInfoWrapper wrapper)) {
            return null;
        }
        MultiblockMachineDefinition definition = wrapper.definition;
        if (definition == null) {
            return null;
        }

        String recipeId = recipeId(category, recipe);
        if (recipeId == null) {
            recipeId = definition.getId() != null ? definition.getId().toString() : null;
        }
        if (recipeId == null) {
            return null;
        }

        List<MultiblockShapeInfo> shapes;
        try {
            shapes = definition.getMatchingShapes();
        } catch (Exception e) {
            return null;
        }
        if (shapes == null || shapes.isEmpty()) {
            return null;
        }

        Map<String, ItemStackExport> itemInputs = extractBlocks(shapes.get(0));
        if (itemInputs.isEmpty()) {
            return null;
        }

        return new RecipeExport(
                recipeId,
                RECIPE_TYPE,
                RECIPE_TYPE,
                new LinkedHashMap<>(),
                0L,
                0L,
                itemInputs,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>()
        );
    }

    /**
     * Flattens the blocks of a single multiblock shape into an indexed map of item
     * inputs. Every block position contributes one item; positions resolving to the
     * same item are merged and their counts summed, and the merged inputs are sorted
     * by descending count (see {@link #mergeAndSortByCount}).
     */
    private Map<String, ItemStackExport> extractBlocks(MultiblockShapeInfo shape) {
        Map<String, ItemStackExport> byKey = new LinkedHashMap<>();
        BlockInfo[][][] blocks = shape.getBlocks();
        if (blocks == null) {
            return byKey;
        }
        for (BlockInfo[][] aisle : blocks) {
            if (aisle == null) {
                continue;
            }
            for (BlockInfo[] column : aisle) {
                if (column == null) {
                    continue;
                }
                for (BlockInfo info : column) {
                    if (info != null) {
                        accumulate(byKey, info.getBlockState());
                    }
                }
            }
        }
        return mergeAndSortByCount(byKey.values());
    }

    /**
     * Resolves a block state to its item form and adds one to the running count for
     * that item, interning the item data (without NBT). Blocks that resolve to no
     * item are skipped.
     */
    private void accumulate(Map<String, ItemStackExport> byKey, BlockState state) {
        if (state == null) {
            return;
        }
        ItemStack stack = itemForBlock(state);
        if (stack.isEmpty()) {
            return;
        }
        String key = tables.internItem(itemId(stack), null, null, stack);
        ItemStackExport existing = byKey.get(key);
        byKey.put(key, existing == null
                ? new ItemStackExport(key, 1, null, null)
                : new ItemStackExport(key, existing.count() + 1, null, null));
    }

    /**
     * Returns the item that represents a block state, ignoring NBT. GregTech machine
     * blocks and ordinary blocks alike carry a registered block item, so the block's
     * own item is used directly. Fluid blocks (which have no block item) fall back to
     * the fluid's bucket, mirroring the pattern preview widget's block-drop logic.
     * Returns {@link ItemStack#EMPTY} when no item can be resolved.
     */
    private static ItemStack itemForBlock(BlockState state) {
        ItemStack stack = new ItemStack(state.getBlock());
        if (!stack.isEmpty()) {
            return stack;
        }
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            Fluid fluid = fluidState.getType();
            if (fluid.getBucket() != null) {
                return new ItemStack(fluid.getBucket());
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Checks whether this handler can convert the recipes of the given category.
     * Returns true only for the {@code gtceu:multiblock_info} recipe type.
     */
    public static boolean canHandle(IRecipeCategory<?> category) {
        return category.getRecipeType().getUid().toString().equals(RECIPE_TYPE);
    }
}
