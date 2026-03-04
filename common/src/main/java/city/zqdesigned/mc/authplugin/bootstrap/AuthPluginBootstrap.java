package city.zqdesigned.mc.authplugin.bootstrap;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import city.zqdesigned.mc.authplugin.auth.AuthService;
import city.zqdesigned.mc.authplugin.config.AuthPluginConfig;
import city.zqdesigned.mc.authplugin.config.ConfigManager;
import city.zqdesigned.mc.authplugin.db.DatabaseManager;
import city.zqdesigned.mc.authplugin.restriction.AuthRestrictionService;
import city.zqdesigned.mc.authplugin.session.SessionManager;
import city.zqdesigned.mc.authplugin.token.TokenDao;
import city.zqdesigned.mc.authplugin.token.TokenService;
import city.zqdesigned.mc.authplugin.web.OnlinePlayerRegistry;
import city.zqdesigned.mc.authplugin.web.WebAdminServer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AuthPluginBootstrap {
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final ConfigManager configManager = new ConfigManager();
    private final DatabaseManager databaseManager = new DatabaseManager();
    private final TokenDao tokenDao = new TokenDao(this.databaseManager);
    private final TokenService tokenService = new TokenService(this.tokenDao);
    private final SessionManager sessionManager = new SessionManager();
    private final AuthService authService = new AuthService(this.tokenService, this.sessionManager);
    private final AuthRestrictionService restrictionService = new AuthRestrictionService(this.authService);
    private final OnlinePlayerRegistry onlinePlayerRegistry = new OnlinePlayerRegistry();
    private volatile AuthPluginConfig config;
    private volatile WebAdminServer webAdminServer;

    public void start() {
        if (!this.started.compareAndSet(false, true)) {
            return;
        }

        try {
            this.config = this.configManager.loadOrCreate();
            this.databaseManager.initialize();
            this.webAdminServer = new WebAdminServer(
                this.tokenService,
                this.authService,
                this.onlinePlayerRegistry,
                this.config.web()
            );
            this.webAdminServer.start();
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
        this.authService.resetSessions();
        if (this.webAdminServer != null) {
            this.webAdminServer.stop();
        }
        this.databaseManager.shutdown();
    }

    public TokenService tokenService() {
        return this.tokenService;
    }

    public AuthService authService() {
        return this.authService;
    }

    public OnlinePlayerRegistry onlinePlayerRegistry() {
        return this.onlinePlayerRegistry;
    }

    public AuthRestrictionService restrictionService() {
        return this.restrictionService;
    }
}
