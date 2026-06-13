package dev.alperovi.recipedataexporter.handler;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.category.GTRecipeCategory;
import com.gregtechceu.gtceu.common.data.GTRecipeCategories;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.resources.ResourceLocation;

/**
 * Recipe handler for GregTech (gtceu) recipe types.
 * Derives item/fluid IO dimensions from the GregTech {@link GTRecipeType} max input/output counts.
 */
public class GregTechRecipeTypeHandler extends RecipeTypeHandler {

    private static final String NAMESPACE = "gtceu";
    private static final int MAX_WIDTH = 3;

    public GregTechRecipeTypeHandler(IRecipeManager recipeManager) {
        super(recipeManager);
    }

    /**
     * Checks if this handler can handle the given recipe category.
     * Returns true for all gtceu: recipe types.
     */
    public static boolean canHandle(IRecipeCategory<?> category) {
        return category.getRecipeType().getUid().getNamespace().equals(NAMESPACE);
    }

    @Override
    protected int[] getItemInputDimensions(String recipeTypeUid) {
        return dimensions(maxCount(recipeTypeUid, ItemRecipeCapability.CAP, true));
    }

    @Override
    protected int[] getItemOutputDimensions(String recipeTypeUid) {
        return dimensions(maxCount(recipeTypeUid, ItemRecipeCapability.CAP, false));
    }

    @Override
    protected int[] getFluidInputDimensions(String recipeTypeUid) {
        return dimensions(maxCount(recipeTypeUid, FluidRecipeCapability.CAP, true));
    }

    @Override
    protected int[] getFluidOutputDimensions(String recipeTypeUid) {
        return dimensions(maxCount(recipeTypeUid, FluidRecipeCapability.CAP, false));
    }

    /**
     * Resolves the GregTech recipe type for the given UID and returns its max input or output
     * count for the given capability. Returns 0 if the recipe type cannot be resolved.
     */
    private int maxCount(String recipeTypeUid, RecipeCapability<?> capability, boolean input) {
        GTRecipeType recipeType = resolveRecipeType(recipeTypeUid);
        if (recipeType == null) {
            return 0;
        }
        return input ? recipeType.getMaxInputs(capability) : recipeType.getMaxOutputs(capability);
    }

    /**
     * Resolves the {@link GTRecipeType} from a recipe type UID such as "gtceu:alloy_smelter".
     * The JEI category UID maps to a {@link GTRecipeCategory}, which may be an extra category
     * (e.g. "gtceu:extractor_recycling") attached to a different underlying recipe type. Looking
     * up the category and using its recipe type handles both the 1:1 and the extra-category cases.
     */
    private GTRecipeType resolveRecipeType(String recipeTypeUid) {
        ResourceLocation location = ResourceLocation.tryParse(recipeTypeUid);
        if (location == null) {
            return null;
        }
        GTRecipeCategory category = GTRecipeCategories.get(location.getPath());
        if (category == null) {
            return null;
        }
        return category.getRecipeType();
    }

    /**
     * Converts a max IO count into [width, height] dimensions, capping the width at {@link #MAX_WIDTH}
     * and wrapping the remainder into additional rows.
     */
    private static int[] dimensions(int count) {
        if (count <= 0) {
            return new int[]{0, 0};
        }
        int width = Math.min(count, MAX_WIDTH);
        int height = (count + MAX_WIDTH - 1) / MAX_WIDTH;
        return new int[]{width, height};
    }
}
