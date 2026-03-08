package city.zqdesigned.mc.authplugin.forge;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(AuthPlugin.MOD_ID)
public final class AuthPluginForge {
    public AuthPluginForge() {
        EventBuses.registerModEventBus(AuthPlugin.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());
        AuthPlugin.init();
        new ForgeAuthRuntime().register();
    }
}
