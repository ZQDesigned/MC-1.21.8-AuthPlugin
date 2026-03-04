package city.zqdesigned.mc.authplugin;

import city.zqdesigned.mc.authplugin.bootstrap.AuthPluginBootstrap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AuthPlugin {
    public static final String MOD_ID = "authplugin";
    public static final Logger LOGGER = LoggerFactory.getLogger("AuthPlugin");
    private static final AuthPluginBootstrap BOOTSTRAP = new AuthPluginBootstrap();
    private static final AtomicBoolean SHUTDOWN_HOOK_ADDED = new AtomicBoolean(false);

    public static void init() {
        if (SHUTDOWN_HOOK_ADDED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(BOOTSTRAP::stop, "authplugin-shutdown"));
        }
        BOOTSTRAP.start();
    }

    public static AuthPluginBootstrap bootstrap() {
        return BOOTSTRAP;
    }
}
