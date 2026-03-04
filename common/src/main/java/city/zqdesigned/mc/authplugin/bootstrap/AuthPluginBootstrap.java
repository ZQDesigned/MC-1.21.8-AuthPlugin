package city.zqdesigned.mc.authplugin.bootstrap;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import city.zqdesigned.mc.authplugin.config.AuthPluginConfig;
import city.zqdesigned.mc.authplugin.config.ConfigManager;
import city.zqdesigned.mc.authplugin.db.DatabaseManager;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AuthPluginBootstrap {
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final ConfigManager configManager = new ConfigManager();
    private final DatabaseManager databaseManager = new DatabaseManager();
    private volatile AuthPluginConfig config;

    public void start() {
        if (!this.started.compareAndSet(false, true)) {
            return;
        }

        try {
            this.config = this.configManager.loadOrCreate();
            this.databaseManager.initialize();
            AuthPlugin.LOGGER.info(
                "AuthPlugin bootstrap initialized. Web admin configured on port {}.",
                this.config.web().port()
            );
        } catch (Exception exception) {
            this.started.set(false);
            throw new IllegalStateException("Failed to start AuthPlugin bootstrap", exception);
        }
    }

    public Optional<AuthPluginConfig> config() {
        return Optional.ofNullable(this.config);
    }

    public DatabaseManager databaseManager() {
        return this.databaseManager;
    }

    public void stop() {
        this.databaseManager.shutdown();
    }
}
