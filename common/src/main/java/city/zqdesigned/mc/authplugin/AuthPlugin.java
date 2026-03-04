package city.zqdesigned.mc.authplugin;

import city.zqdesigned.mc.authplugin.bootstrap.AuthPluginBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AuthPlugin {
    public static final String MOD_ID = "authplugin";
    public static final Logger LOGGER = LoggerFactory.getLogger("AuthPlugin");
    private static final AuthPluginBootstrap BOOTSTRAP = new AuthPluginBootstrap();

    public static void init() {
        BOOTSTRAP.start();
    }

    public static AuthPluginBootstrap bootstrap() {
        return BOOTSTRAP;
    }
}
