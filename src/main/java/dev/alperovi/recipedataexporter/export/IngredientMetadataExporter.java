package dev.alperovi.recipedataexporter.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import dev.alperovi.recipedataexporter.model.FluidExport;
import dev.alperovi.recipedataexporter.model.ItemExport;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Enriches the deduplicated item and fluid tables with display name, mod name,
 * full tooltip (en_us, as displayed to the running client) and a 64x64 PNG icon
 * rendered exactly as JEI draws the ingredient.
 *
 * <p>Names and tooltips are obtained from the JEI {@link IIngredientHelper} and
 * {@link IIngredientRenderer} for the ingredient type. Icons are rendered into an
 * offscreen framebuffer on the client render thread and written to
 * {@code <outputDir>/images/<sanitizedKey>.png}; the relative path is stored on
 * the enriched record. Individual failures are logged and skipped so the export
 * still completes.
 */
public class IngredientMetadataExporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Side length, in pixels, of each rendered icon. */
    private static final int ICON_SIZE = 64;

    /**
     * NBT key under which some mods (e.g. the StarT-Core GregTech fork) write
     * recipe-specific display tooltips onto a recipe's ingredient stacks; their
     * tooltip handler then replaces the item/fluid's standard tooltip with those
     * lines. The key is stripped before tooltip extraction so the export records
     * the item's standard tooltip rather than a recipe-specific one.
     */
    private static final String CUSTOM_TOOLTIPS_NBT_KEY = "custom_tooltips";

    /** GUI-space size (in units) that JEI renders an ingredient at. */
    private static final float GUI_UNITS = 16.0F;

    private final IIngredientManager ingredientManager;

    public IngredientMetadataExporter(IIngredientManager ingredientManager) {
        this.ingredientManager = ingredientManager;
    }

    /**
     * Enriches every item and fluid entry in the given tables and writes their
     * icons into {@code <outputDir>/images}. All rendering runs on the client
     * render thread; this method blocks until it completes.
     *
     * @param tables    the tables whose entries are enriched in place
     * @param outputDir the export base directory (icons go under {@code images/})
     * @return the number of icons successfully written
     */
    public int enrich(ExportTables tables, Path outputDir) {
        Path imagesDir = outputDir.resolve("images");
        try {
            Files.createDirectories(imagesDir);
        } catch (Exception e) {
            LOGGER.error("Failed to create images directory {}: {}", imagesDir, e.getMessage(), e);
            return 0;
        }

        Minecraft minecraft = Minecraft.getInstance();
        try {
            return minecraft.submit(() -> enrichOnRenderThread(tables, imagesDir)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted while exporting ingredient metadata", e);
            return 0;
        } catch (ExecutionException e) {
            LOGGER.error("Failed to export ingredient metadata", e.getCause());
            return 0;
        }
    }

    /**
     * Runs the full enrichment loop on the render thread, reusing a single
     * offscreen framebuffer for every icon. Returns the number of icons written.
     */
    private int enrichOnRenderThread(ExportTables tables, Path imagesDir) {
        RenderTarget framebuffer = new TextureTarget(ICON_SIZE, ICON_SIZE, true, Minecraft.ON_OSX);
        int written = 0;
        try {
            IIngredientHelper<ItemStack> itemHelper = ingredientManager.getIngredientHelper(VanillaTypes.ITEM_STACK);
            IIngredientRenderer<ItemStack> itemRenderer = ingredientManager.getIngredientRenderer(VanillaTypes.ITEM_STACK);
            for (Map.Entry<String, ItemExport> entry : Map.copyOf(tables.items()).entrySet()) {
                String key = entry.getKey();
                // Tag entries are not enriched; their members are exported as concrete entries instead.
                if (entry.getValue().tag() != null) {
                    continue;
                }
                ItemStack representative = tables.itemRepresentative(key);
                if (representative == null || representative.isEmpty()) {
                    continue;
                }
                // Render a single-count copy so the JEI count badge is not drawn, and
                // drop any recipe-specific tooltip NBT so the standard tooltip is used.
                ItemStack icon = withoutCustomTooltips(representative.copyWithCount(1));
                Meta meta = computeMeta(itemHelper, itemRenderer,
                        icon, icon, key, framebuffer, imagesDir);
                ItemExport base = entry.getValue();
                tables.replaceItem(key, new ItemExport(base.id(), base.tag(), base.nbt(),
                        meta.displayName, meta.modName, meta.tooltip, meta.image, base.metadata()));
                if (meta.image != null) {
                    written++;
                }
            }

            IIngredientHelper<FluidStack> fluidHelper = ingredientManager.getIngredientHelper(ForgeTypes.FLUID_STACK);
            IIngredientRenderer<FluidStack> fluidRenderer = ingredientManager.getIngredientRenderer(ForgeTypes.FLUID_STACK);
            for (Map.Entry<String, FluidExport> entry : Map.copyOf(tables.fluids()).entrySet()) {
                String key = entry.getKey();
                // Tag entries are not enriched; their members are exported as concrete entries instead.
                if (entry.getValue().tag() != null) {
                    continue;
                }
                FluidStack representative = tables.fluidRepresentative(key);
                if (representative == null || representative.isEmpty()) {
                    continue;
                }
                // JEI's fluid renderer fills height proportional to amount/capacity
                // (capacity is one bucket), so render the icon from a full-bucket copy
                // to fill the whole icon. Name and tooltip come from a copy stripped of
                // any recipe-specific tooltip NBT so the standard tooltip is used.
                FluidStack tooltipFluid = withoutCustomTooltips(representative);
                FluidStack icon = tooltipFluid.copy();
                icon.setAmount(FluidType.BUCKET_VOLUME);
                Meta meta = computeMeta(fluidHelper, fluidRenderer,
                        tooltipFluid, icon, key, framebuffer, imagesDir);
                FluidExport base = entry.getValue();
                tables.replaceFluid(key, new FluidExport(base.id(), base.tag(),
                        meta.displayName, meta.modName, meta.tooltip, meta.image));
                if (meta.image != null) {
                    written++;
                }
            }
        } finally {
            framebuffer.destroyBuffers();
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }
        return written;
    }

    /**
     * Computes the display name, mod name, tooltip and icon for a single
     * ingredient. Name and tooltip are read from {@code ingredient}; the icon is
     * rendered from {@code renderIngredient} (which may differ, e.g. a full-bucket
     * fluid copy so the icon fills its box). Name and tooltip failures degrade
     * gracefully to null; an icon failure leaves {@link Meta#image} null. Never throws.
     */
    @SuppressWarnings("removal") // IIngredientRenderer#getTooltip(V, TooltipFlag) has no addon-constructible replacement in this JEI version
    private <V> Meta computeMeta(IIngredientHelper<V> helper,
                                 IIngredientRenderer<V> renderer, V ingredient, V renderIngredient, String key,
                                 RenderTarget framebuffer, Path imagesDir) {
        String displayName = null;
        String modName = null;
        List<String> tooltip = null;
        String image = null;

        try {
            displayName = helper.getDisplayName(ingredient);
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve display name for {}: {}", key, e.getMessage());
        }
        try {
            String modId = helper.getDisplayModId(ingredient);
            if (modId != null) {
                modName = ModList.get().getModContainerById(modId)
                        .map(container -> container.getModInfo().getDisplayName())
                        .orElse(modId);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve mod name for {}: {}", key, e.getMessage());
        }
        try {
            List<Component> lines = renderer.getTooltip(ingredient, TooltipFlag.Default.NORMAL);
            if (lines != null) {
                tooltip = new ArrayList<>(lines.size());
                for (Component line : lines) {
                    tooltip.add(line.getString());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve tooltip for {}: {}", key, e.getMessage());
        }

        String fileName = sanitize(key) + ".png";
        try {
            NativeImage rendered = renderIcon(renderer, renderIngredient, framebuffer);
            try {
                rendered.writeToFile(imagesDir.resolve(fileName));
                image = "images/" + fileName;
            } finally {
                rendered.close();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to render icon for {}: {}", key, e.getMessage());
        }

        return new Meta(displayName, modName, tooltip, image);
    }

    /**
     * Renders a single ingredient into the given framebuffer at {@link #ICON_SIZE}
     * pixels and downloads the result into a {@link NativeImage}. Must be called
     * on the render thread.
     */
    private <V> NativeImage renderIcon(IIngredientRenderer<V> renderer, V ingredient, RenderTarget framebuffer) {
        framebuffer.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        framebuffer.clear(Minecraft.ON_OSX);
        framebuffer.bindWrite(true);

        Matrix4f previousProjection = RenderSystem.getProjectionMatrix();
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        // Map 0..16 GUI units onto the full framebuffer, scaling the 16px icon to 64px.
        Matrix4f projection = new Matrix4f().setOrtho(0.0F, GUI_UNITS, GUI_UNITS, 0.0F, -1000.0F, 1000.0F);
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);

        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
        Lighting.setupFor3DItems();
        renderer.render(graphics, ingredient);
        graphics.flush();

        modelView.popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(previousProjection, previousSorting);

        NativeImage image = new NativeImage(ICON_SIZE, ICON_SIZE, false);
        framebuffer.bindRead();
        image.downloadTexture(0, false);
        image.flipY();
        framebuffer.unbindRead();
        return image;
    }

    /**
     * Returns a copy of the given item stack with the recipe-specific
     * {@link #CUSTOM_TOOLTIPS_NBT_KEY} tag removed so its standard tooltip is
     * resolved. The original is returned unchanged when the tag is absent.
     */
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
     * Returns a copy of the given fluid stack with the recipe-specific
     * {@link #CUSTOM_TOOLTIPS_NBT_KEY} tag removed so its standard tooltip is
     * resolved. The original is returned unchanged when the tag is absent.
     */
    private static FluidStack withoutCustomTooltips(FluidStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(CUSTOM_TOOLTIPS_NBT_KEY)) {
            return stack;
        }
        FluidStack copy = stack.copy();
        CompoundTag tag = copy.getTag();
        tag.remove(CUSTOM_TOOLTIPS_NBT_KEY);
        if (tag.isEmpty()) {
            copy.setTag(null);
        }
        return copy;
    }

    /**
     * Converts a table key into a filesystem-safe flat file name by replacing
     * every character outside {@code [A-Za-z0-9._-]} with an underscore.
     */
    private static String sanitize(String key) {
        return key.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Computed metadata for a single ingredient. Any field may be null. */
    private record Meta(String displayName, String modName, List<String> tooltip, String image) {
    }
}
