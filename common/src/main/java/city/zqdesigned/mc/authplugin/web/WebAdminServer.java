package city.zqdesigned.mc.authplugin.web;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import city.zqdesigned.mc.authplugin.auth.AuthService;
import city.zqdesigned.mc.authplugin.bot.BotApiKeyService;
import city.zqdesigned.mc.authplugin.config.WebConfig;
import city.zqdesigned.mc.authplugin.profile.PlayerProfileService;
import city.zqdesigned.mc.authplugin.server.ServerControlService;
import city.zqdesigned.mc.authplugin.token.TokenInfo;
import city.zqdesigned.mc.authplugin.token.TokenService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.sse.SseClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class WebAdminServer implements WebAdminLifecycle {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9]{1,128}$");
    private static final String INDEX_PAGE_RESOURCE = "/web/admin-panel-zh.html";
    private static final String LOGIN_API_PATH = "/api/auth/login";
    private static final String BOT_API_PREFIX = "/api/bot/";
    private static final String BOT_API_KEY_HEADER = "X-API-Key";
    private static final String BOT_API_KEY_QUERY = "api_key";
    private static final String LOG_STREAM_PATH = "/api/server/logs/stream";
    private static final String AUTH_HEADER_PREFIX = "Bearer ";
    private static final String ATTR_ACCESS_TOKEN = "auth.accessToken";
    private static final String ATTR_ACCESS_USER = "auth.accessUser";
    private static final String ATTR_ACCESS_EXPIRES_AT = "auth.accessExpiresAt";
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(90);

    private final TokenService tokenService;
    private final AuthService authService;
    private final OnlinePlayerRegistry onlinePlayerRegistry;
    private final PlayerProfileService playerProfileService;
    private final ServerControlService serverControlService;
    private final BotApiKeyService botApiKeyService;
    private final WebConfig webConfig;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentMap<String, AccessTokenSession> accessTokens = new ConcurrentHashMap<>();
    private final Set<SseClient> logStreamClients = new CopyOnWriteArraySet<>();

    private Javalin app;
    private WatchService logWatchService;
    private Thread logWatchThread;
    private volatile long logReadCursor = 0L;
    private volatile String indexPageCache;

    public WebAdminServer(
        TokenService tokenService,
        AuthService authService,
        OnlinePlayerRegistry onlinePlayerRegistry,
        PlayerProfileService playerProfileService,
        ServerControlService serverControlService,
        BotApiKeyService botApiKeyService,
        WebConfig webConfig
    ) {
        this.tokenService = tokenService;
        this.authService = authService;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
        this.playerProfileService = playerProfileService;
        this.serverControlService = serverControlService;
        this.botApiKeyService = botApiKeyService;
        this.webConfig = webConfig;
    }

    @Override
    public void start() {
        if (this.app != null) {
            return;
        }

        this.app = Javalin.create(config -> config.showJavalinBanner = false);

        this.app.before("/api/*", this::requireAccessToken);
        this.app.before("/api/bot/*", this::requireBotApiKey);
        this.app.get("/", ctx -> ctx.contentType("text/html; charset=utf-8").result(this.indexPage()));
        this.app.post(LOGIN_API_PATH, this::handleLogin);
        this.app.post("/api/auth/logout", this::handleLogout);
        this.app.get("/api/auth/session", this::handleSession);
        this.app.get("/api/bot/players", this::handleBotPlayers);
        this.app.post("/api/bot/tokens", this::handleBotCreateToken);
        this.app.get("/api/players", this::handlePlayers);
        this.app.get("/api/tokens", this::handleListTokens);
        this.app.get("/api/server/status", this::handleServerStatus);
        this.app.get("/api/server/logs", this::handleServerLogs);
        this.app.sse(LOG_STREAM_PATH, this::handleServerLogStream);
        this.app.post("/api/server/command", this::handleServerCommand);
        this.app.post("/api/server/shutdown", this::handleServerShutdown);
        this.app.post("/api/tokens", this::handleAddToken);
        this.app.delete("/api/tokens/{token}", this::handleDeleteToken);
        this.app.patch("/api/tokens/{token}/disable", this::handleDisableToken);
        this.app.patch("/api/tokens/{token}/enable", this::handleEnableToken);
        this.app.post("/api/tokens/generate", this::handleGenerateTokens);
        this.app.exception(Exception.class, (exception, ctx) -> {
            AuthPlugin.LOGGER.error("Web API error", exception);
            if (!ctx.res().isCommitted()) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", "Internal server error"));
            }
        });

        this.app.start(this.webConfig.port());
        this.startLogWatcher();
        AuthPlugin.LOGGER.info(
            "Web admin server started on port {} with access token auth (ttl={} minutes)",
            this.webConfig.port(),
            ACCESS_TOKEN_TTL.toMinutes()
        );
    }

    @Override
    public void stop() {
        Javalin runningApp = this.app;
        if (runningApp == null) {
            return;
        }

        try {
            runningApp.stop();
        } finally {
            this.stopLogWatcher();
            this.logStreamClients.clear();
            try {
                runningApp.jettyServer().server().destroy();
            } catch (Exception exception) {
                AuthPlugin.LOGGER.warn("Failed to destroy Jetty server cleanly", exception);
            }
            this.accessTokens.clear();
            this.app = null;
        }
    }

    private void requireAccessToken(Context ctx) {
        if (ctx.path().startsWith(BOT_API_PREFIX)) {
            return;
        }
        if (LOGIN_API_PATH.equals(ctx.path())) {
            return;
        }

        long now = System.currentTimeMillis();
        this.cleanupExpiredSessions(now);

        String accessToken = this.extractAccessToken(ctx.header("Authorization"));
        if (accessToken == null && LOG_STREAM_PATH.equals(ctx.path())) {
            String queryToken = ctx.queryParam("access_token");
            if (queryToken != null && !queryToken.isBlank()) {
                accessToken = queryToken.trim();
            }
        }
        if (accessToken == null) {
            this.rejectUnauthorized(ctx);
            return;
        }

        AccessTokenSession session = this.accessTokens.get(accessToken);
        if (session == null || session.expiresAtMillis() <= now) {
            this.accessTokens.remove(accessToken);
            this.rejectUnauthorized(ctx);
            return;
        }

        ctx.attribute(ATTR_ACCESS_TOKEN, accessToken);
        ctx.attribute(ATTR_ACCESS_USER, session.username());
        ctx.attribute(ATTR_ACCESS_EXPIRES_AT, session.expiresAtMillis());
    }

    private void requireBotApiKey(Context ctx) {
        String apiKey = ctx.header(BOT_API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = ctx.queryParam(BOT_API_KEY_QUERY);
        }

        String resolved = apiKey == null ? "" : apiKey.trim();
        boolean allowed = this.botApiKeyService.authenticate(resolved).join();
        if (!allowed) {
            ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "Invalid API key"));
        }
    }

    private void handleLogin(Context ctx) {
        LoginRequest request;
        try {
            request = ctx.bodyAsClass(LoginRequest.class);
        } catch (RuntimeException exception) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid login payload"));
            return;
        }

        String username = request.username() == null ? "" : request.username().trim();
        String password = request.password() == null ? "" : request.password();
        if (!this.credentialsMatch(username, password)) {
            ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "Invalid credentials"));
            return;
        }

        long now = System.currentTimeMillis();
        this.cleanupExpiredSessions(now);

        String accessToken = this.createAccessToken();
        long expiresAt = now + ACCESS_TOKEN_TTL.toMillis();
        this.accessTokens.put(accessToken, new AccessTokenSession(username, expiresAt));

        ctx.json(Map.of(
            "accessToken", accessToken,
            "tokenType", "Bearer",
            "expiresIn", ACCESS_TOKEN_TTL.toSeconds(),
            "expiresAt", expiresAt
        ));
    }

    private void handleLogout(Context ctx) {
        String accessToken = ctx.attribute(ATTR_ACCESS_TOKEN);
        if (accessToken != null) {
            this.accessTokens.remove(accessToken);
        }
        ctx.status(HttpStatus.NO_CONTENT);
    }

    private void handleSession(Context ctx) {
        String username = ctx.attribute(ATTR_ACCESS_USER);
        Long expiresAt = ctx.attribute(ATTR_ACCESS_EXPIRES_AT);
        ctx.json(Map.of(
            "username", username,
            "expiresAt", expiresAt,
            "expiresIn", Math.max(0L, (expiresAt - System.currentTimeMillis()) / 1000L)
        ));
    }

    private void handleBotPlayers(Context ctx) {
        List<String> players = this.onlinePlayerRegistry.snapshot().entrySet().stream()
            .sorted(Map.Entry.comparingByValue(String::compareToIgnoreCase))
            .map(entry -> {
                boolean loggedIn = this.authService.isLoggedIn(entry.getKey());
                String loginState = loggedIn ? "已登录" : "未登录";
                return entry.getValue() + "（" + loginState + "）";
            })
            .toList();
        ctx.json(Map.of("count", players.size(), "players", players));
    }

    private void handleBotCreateToken(Context ctx) {
        List<String> created = this.tokenService.generateAndStoreTokens(1).join();
        String token = created.isEmpty() ? "" : created.get(0);
        if (token.isEmpty()) {
            throw new IllegalStateException("Failed to create token");
        }
        ctx.status(HttpStatus.CREATED).json(Map.of("token", token));
    }

    private void handlePlayers(Context ctx) {
        List<OnlinePlayerInfo> players = this.onlinePlayerRegistry.snapshot().entrySet().stream()
            .map(entry -> {
                UUID uuid = entry.getKey();
                String boundToken = this.tokenService.findByPlayer(uuid)
                    .join()
                    .map(TokenInfo::token)
                    .orElse(null);
                return new OnlinePlayerInfo(
                    uuid,
                    entry.getValue(),
                    this.authService.isLoggedIn(uuid),
                    boundToken
                );
            })
            .toList();
        ctx.json(Map.of("players", players));
    }

    private void handleListTokens(Context ctx) {
        List<TokenInfo> tokens = this.tokenService.listTokens().join();
        Set<UUID> boundUuids = tokens.stream()
            .map(TokenInfo::boundPlayer)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<UUID, String> playerNames = this.playerProfileService.findNames(boundUuids).join();

        List<TokenAdminView> tokenViews = tokens.stream().map(token -> {
            UUID boundUuid = token.boundPlayer();
            String boundUuidText = boundUuid == null ? null : boundUuid.toString();
            String boundPlayerName = boundUuid == null ? null : playerNames.get(boundUuid);
            return new TokenAdminView(
                token.token(),
                boundUuidText,
                boundPlayerName,
                token.disabled(),
                token.createdAt(),
                token.lastUsedAt()
            );
        }).toList();

        ctx.json(Map.of("tokens", tokenViews));
    }

    private void handleAddToken(Context ctx) {
        AddTokenRequest request = ctx.bodyAsClass(AddTokenRequest.class);
        String token = request.token() == null ? "" : request.token().trim();
        if (!TOKEN_PATTERN.matcher(token).matches()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid token format"));
            return;
        }
        boolean inserted = this.tokenService.createToken(token).join();
        if (!inserted) {
            ctx.status(HttpStatus.CONFLICT).json(Map.of("error", "Token already exists"));
            return;
        }
        ctx.status(HttpStatus.CREATED).json(Map.of("token", token));
    }

    private void handleDeleteToken(Context ctx) {
        String token = ctx.pathParam("token");
        boolean deleted = this.tokenService.deleteToken(token).join();
        if (!deleted) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Token not found"));
            return;
        }
        ctx.status(HttpStatus.NO_CONTENT);
    }

    private void handleDisableToken(Context ctx) {
        String token = ctx.pathParam("token");
        boolean disabled = this.tokenService.disableToken(token).join();
        if (!disabled) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Token not found"));
            return;
        }
        ctx.json(Map.of("token", token, "disabled", true));
    }

    private void handleEnableToken(Context ctx) {
        String token = ctx.pathParam("token");
        boolean enabled = this.tokenService.enableToken(token).join();
        if (!enabled) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Token not found"));
            return;
        }
        ctx.json(Map.of("token", token, "disabled", false));
    }

    private void handleGenerateTokens(Context ctx) {
        GenerateTokenRequest request = ctx.bodyAsClass(GenerateTokenRequest.class);
        int count = request.count() == null ? 0 : request.count();
        if (count < 1 || count > TokenService.MAX_BATCH_COUNT) {
            ctx.status(HttpStatus.BAD_REQUEST)
                .json(Map.of("error", "count must be between 1 and " + TokenService.MAX_BATCH_COUNT));
            return;
        }
        List<String> tokens = this.tokenService.generateAndStoreTokens(count).join();
        ctx.json(Map.of("count", tokens.size(), "tokens", tokens));
    }

    private void handleServerStatus(Context ctx) {
        ctx.json(this.serverControlService.snapshotStatus());
    }

    private void handleServerLogs(Context ctx) {
        int requestedLimit = ServerControlService.DEFAULT_LOG_LIMIT;
        String limitQuery = ctx.queryParam("limit");
        if (limitQuery != null) {
            try {
                requestedLimit = Integer.parseInt(limitQuery);
            } catch (NumberFormatException exception) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "limit must be an integer"));
                return;
            }
        }

        if (requestedLimit < 1 || requestedLimit > ServerControlService.MAX_LOG_LIMIT) {
            ctx.status(HttpStatus.BAD_REQUEST).json(
                Map.of("error", "limit must be between 1 and " + ServerControlService.MAX_LOG_LIMIT)
            );
            return;
        }

        List<String> lines = this.serverControlService.readRecentLogs(requestedLimit);
        ctx.json(Map.of("count", lines.size(), "lines", lines));
    }

    private void handleServerLogStream(SseClient client) {
        int limit = ServerControlService.DEFAULT_LOG_LIMIT;
        String limitQuery = client.ctx().queryParam("limit");
        if (limitQuery != null) {
            try {
                limit = Integer.parseInt(limitQuery);
            } catch (NumberFormatException ignored) {
                limit = ServerControlService.DEFAULT_LOG_LIMIT;
            }
        }
        limit = Math.max(1, Math.min(limit, ServerControlService.MAX_LOG_LIMIT));

        this.logStreamClients.add(client);
        client.onClose(() -> this.logStreamClients.remove(client));

        List<String> snapshot = this.serverControlService.readRecentLogs(limit);
        for (String line : snapshot) {
            this.safeSendEvent(client, "line", line);
        }
    }

    private void handleServerCommand(Context ctx) {
        ExecuteServerCommandRequest request;
        try {
            request = ctx.bodyAsClass(ExecuteServerCommandRequest.class);
        } catch (RuntimeException exception) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid command payload"));
            return;
        }
        String command = request.command() == null ? "" : request.command().trim();
        if (command.isEmpty()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "command cannot be empty"));
            return;
        }

        try {
            ServerControlService.CommandExecutionResult result = this.serverControlService
                .executeConsoleCommand(command)
                .join();
            ctx.json(Map.of("command", result.command(), "executedAt", result.executedAt()));
        } catch (CompletionException completionException) {
            this.handleServerControlException(ctx, completionException.getCause());
        }
    }

    private void handleServerShutdown(Context ctx) {
        RequestServerShutdown request;
        try {
            request = ctx.bodyAsClass(RequestServerShutdown.class);
        } catch (RuntimeException exception) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid shutdown payload"));
            return;
        }
        String message = request.message() == null ? "" : request.message().trim();

        try {
            ServerControlService.ShutdownRequestResult result = this.serverControlService.requestShutdown(message).join();
            ctx.json(Map.of(
                "initiated", result.initiated(),
                "stopMsgUsed", result.stopMsgUsed(),
                "message", result.message(),
                "requestedAt", result.requestedAt()
            ));
        } catch (CompletionException completionException) {
            this.handleServerControlException(ctx, completionException.getCause());
        }
    }

    private void handleServerControlException(Context ctx, Throwable cause) {
        if (cause instanceof IllegalArgumentException illegalArgumentException) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", illegalArgumentException.getMessage()));
            return;
        }
        if (cause instanceof IllegalStateException illegalStateException) {
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(Map.of("error", illegalStateException.getMessage()));
            return;
        }

        if (cause != null) {
            throw new RuntimeException(cause);
        }
        throw new RuntimeException("Unknown server control error");
    }

    private void startLogWatcher() {
        Path logFile = this.resolveLatestLogFile();
        try {
            Files.createDirectories(logFile.getParent());
            this.logReadCursor = Files.exists(logFile) ? Files.size(logFile) : 0L;
            this.logWatchService = logFile.getParent().getFileSystem().newWatchService();
            logFile.getParent().register(
                this.logWatchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize server log watcher", exception);
        }

        this.logWatchThread = new Thread(this::watchLogLoop, "authplugin-log-stream");
        this.logWatchThread.setDaemon(true);
        this.logWatchThread.start();
    }

    private void stopLogWatcher() {
        Thread thread = this.logWatchThread;
        this.logWatchThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        WatchService watchService = this.logWatchService;
        this.logWatchService = null;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException exception) {
                AuthPlugin.LOGGER.warn("Failed to close log watch service cleanly", exception);
            }
        }
    }

    private void watchLogLoop() {
        while (Thread.currentThread() == this.logWatchThread) {
            WatchService watchService = this.logWatchService;
            if (watchService == null) {
                return;
            }

            WatchKey watchKey;
            try {
                watchKey = watchService.take();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException ignored) {
                return;
            }

            boolean latestLogUpdated = false;
            for (WatchEvent<?> event : watchKey.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                Object context = event.context();
                if (context instanceof Path changed && "latest.log".equals(changed.getFileName().toString())) {
                    latestLogUpdated = true;
                }
            }

            watchKey.reset();
            if (latestLogUpdated) {
                this.broadcastAppendedLogLines();
            }
        }
    }

    private synchronized void broadcastAppendedLogLines() {
        if (this.logStreamClients.isEmpty()) {
            return;
        }

        List<String> appended = this.readAppendedLogLines();
        if (appended.isEmpty()) {
            return;
        }

        for (String line : appended) {
            for (SseClient client : this.logStreamClients) {
                this.safeSendEvent(client, "line", line);
            }
        }
    }

    private void safeSendEvent(SseClient client, String event, String data) {
        try {
            client.sendEvent(event, data);
        } catch (Exception ignored) {
            this.logStreamClients.remove(client);
            try {
                client.close();
            } catch (Exception closeIgnored) {
                // Ignore close failure, client is already removed.
            }
        }
    }

    private List<String> readAppendedLogLines() {
        Path logFile = this.resolveLatestLogFile();
        if (!Files.exists(logFile)) {
            this.logReadCursor = 0L;
            return List.of();
        }

        try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
            long length = file.length();
            if (this.logReadCursor > length) {
                this.logReadCursor = 0L;
            }
            file.seek(this.logReadCursor);

            List<String> lines = new ArrayList<>();
            String line;
            while ((line = file.readLine()) != null) {
                byte[] source = line.getBytes(StandardCharsets.ISO_8859_1);
                lines.add(new String(source, StandardCharsets.UTF_8));
            }
            this.logReadCursor = file.getFilePointer();
            return lines;
        } catch (IOException exception) {
            AuthPlugin.LOGGER.warn("Failed to tail server latest.log", exception);
            return List.of();
        }
    }

    private Path resolveLatestLogFile() {
        return Path.of("logs", "latest.log");
    }

    private boolean credentialsMatch(String username, String password) {
        return this.secureEquals(this.webConfig.username(), username)
            && this.secureEquals(this.webConfig.password(), password);
    }

    private boolean secureEquals(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(leftBytes, rightBytes);
    }

    private void cleanupExpiredSessions(long nowMillis) {
        this.accessTokens.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= nowMillis);
    }

    private String createAccessToken() {
        byte[] random = new byte[32];
        this.secureRandom.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private String extractAccessToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(AUTH_HEADER_PREFIX)) {
            return null;
        }
        String token = authorizationHeader.substring(AUTH_HEADER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void rejectUnauthorized(Context ctx) {
        ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "Unauthorized"));
    }

    private String indexPage() {
        if (this.indexPageCache != null) {
            return this.indexPageCache;
        }

        synchronized (this) {
            if (this.indexPageCache == null) {
                this.indexPageCache = this.loadIndexPageFromResource();
            }
            return this.indexPageCache;
        }
    }

    private String loadIndexPageFromResource() {
        InputStream inputStream = WebAdminServer.class.getResourceAsStream(INDEX_PAGE_RESOURCE);
        if (inputStream == null) {
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            if (contextClassLoader != null) {
                inputStream = contextClassLoader.getResourceAsStream("web/admin-panel-zh.html");
            }
        }
        if (inputStream == null) {
            throw new IllegalStateException("Missing web resource: " + INDEX_PAGE_RESOURCE);
        }

        try (InputStream resource = inputStream) {
            String html = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            return html
                .replace("__MAX_BATCH_COUNT__", Integer.toString(TokenService.MAX_BATCH_COUNT))
                .replace("__ACCESS_TOKEN_TTL_MINUTES__", Long.toString(ACCESS_TOKEN_TTL.toMinutes()));
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load web admin page", exception);
        }
    }

    public record LoginRequest(String username, String password) {
    }

    public record AddTokenRequest(String token) {
    }

    public record GenerateTokenRequest(Integer count) {
    }

    public record ExecuteServerCommandRequest(String command) {
    }

    public record RequestServerShutdown(String message) {
    }

    public record TokenAdminView(
        String token,
        String boundUuid,
        String boundPlayerName,
        boolean disabled,
        long createdAt,
        long lastUsedAt
    ) {
    }

    private record AccessTokenSession(String username, long expiresAtMillis) {
    }
}
