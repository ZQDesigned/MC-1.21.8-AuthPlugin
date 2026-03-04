package city.zqdesigned.mc.authplugin.neoforge;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import net.neoforged.fml.common.Mod;

@Mod(AuthPlugin.MOD_ID)
public final class AuthPluginNeoForge {
    public AuthPluginNeoForge() {
        AuthPlugin.init();
        new NeoForgeAuthRuntime().register();
    }
}
