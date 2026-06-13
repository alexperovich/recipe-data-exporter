package dev.alperovi.recipedataexporter;

import dev.alperovi.recipedataexporter.command.ExportDataCommand;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(RecipeDataExporterMod.MOD_ID)
public final class RecipeDataExporterMod {
    public static final String MOD_ID = "recipe_data_exporter";
    public static final String MOD_NAME = "Recipe Data Exporter";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RecipeDataExporterMod() {
        LOGGER.info("{} loaded", MOD_NAME);
        MinecraftForge.EVENT_BUS.register(CommandEventHandler.class);
    }

    /**
     * Handles command registration events.
     */
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class CommandEventHandler {
        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            event.getDispatcher().register(ExportDataCommand.register());
        }
    }
}
