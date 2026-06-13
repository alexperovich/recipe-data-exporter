package dev.alperovi.recipedataexporter;

import dev.alperovi.recipedataexporter.command.ExportItemsCommand;
import dev.alperovi.recipedataexporter.command.ExportDataCommand;
import dev.alperovi.recipedataexporter.export.DataExportService;
import dev.alperovi.recipedataexporter.export.ItemExportService;
import dev.alperovi.recipedataexporter.render.FramebufferRenderer;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(RecipeDataExporterMod.MOD_ID)
public final class RecipeDataExporterMod {
    public static final String MOD_ID = "recipe_data_exporter";
    public static final String MOD_NAME = "Recipe Data Exporter";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RecipeDataExporterMod() {
        LOGGER.info("{} loaded", MOD_NAME);
    }
}
