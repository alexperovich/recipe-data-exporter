package dev.alperovi.recipedataexporter;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

@JeiPlugin
public final class DataExportPlugin implements IModPlugin {
    private static final ResourceLocation PLUGIN_UID = new ResourceLocation(ItemExporterMod.MOD_ID, "data_export");

    @Nullable
    private static volatile IJeiRuntime jeiRuntime;

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        jeiRuntime = runtime;
    }

    @Override
    public void onRuntimeUnavailable() {
        jeiRuntime = null;
    }

    @Nullable
    public static IJeiRuntime getJeiRuntime() {
        return jeiRuntime;
    }
}