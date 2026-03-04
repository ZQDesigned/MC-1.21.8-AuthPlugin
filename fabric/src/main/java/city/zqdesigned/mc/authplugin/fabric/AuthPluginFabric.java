package city.zqdesigned.mc.authplugin.fabric;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import net.fabricmc.api.ModInitializer;

public final class AuthPluginFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        AuthPlugin.init();
        new FabricAuthRuntime().register();
    }
}
