package city.zqdesigned.mc.authplugin.web;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import city.zqdesigned.mc.authplugin.auth.AuthService;
import city.zqdesigned.mc.authplugin.config.WebConfig;
import city.zqdesigned.mc.authplugin.token.TokenInfo;
import city.zqdesigned.mc.authplugin.token.TokenService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class WebAdminServer {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9]{1,128}$");
    private final TokenService tokenService;
    private final AuthService authService;
    private final OnlinePlayerRegistry onlinePlayerRegistry;
    private final WebConfig webConfig;
    private Javalin app;

    public WebAdminServer(
        TokenService tokenService,
        AuthService authService,
        OnlinePlayerRegistry onlinePlayerRegistry,
        WebConfig webConfig
    ) {
        this.tokenService = tokenService;
        this.authService = authService;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
        this.webConfig = webConfig;
    }

    public void start() {
        if (this.app != null) {
            return;
        }

        this.app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        });

        this.app.before("/api/*", this::requireBasicAuth);
        this.app.get("/", ctx -> ctx.contentType("text/html").result(indexPage()));
        this.app.get("/api/players", this::handlePlayers);
        this.app.get("/api/tokens", this::handleListTokens);
        this.app.post("/api/tokens", this::handleAddToken);
        this.app.delete("/api/tokens/{token}", this::handleDeleteToken);
        this.app.patch("/api/tokens/{token}/disable", this::handleDisableToken);
        this.app.post("/api/tokens/generate", this::handleGenerateTokens);
        this.app.exception(Exception.class, (exception, ctx) -> {
            AuthPlugin.LOGGER.error("Web API error", exception);
            if (!ctx.res().isCommitted()) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", "Internal server error"));
            }
        });

        this.app.start(this.webConfig.port());
        AuthPlugin.LOGGER.info("Web admin server started on port {}", this.webConfig.port());
    }

    public void stop() {
        if (this.app == null) {
            return;
        }
        this.app.stop();
        this.app = null;
    }

    private void requireBasicAuth(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.equals(this.expectedAuthHeader())) {
            ctx.header("WWW-Authenticate", "Basic realm=\"AuthPlugin\"");
            ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "Unauthorized"));
            ctx.result("");
        }
    }

    private String expectedAuthHeader() {
        String plain = this.webConfig.username() + ":" + this.webConfig.password();
        String encoded = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
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
        ctx.json(Map.of("tokens", tokens));
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

    private String indexPage() {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1" />
              <title>AuthPlugin Admin</title>
              <style>
                body { font-family: sans-serif; margin: 24px; }
                h1 { margin-bottom: 8px; }
                button { margin-right: 8px; }
                pre { background: #f4f4f4; padding: 12px; overflow: auto; }
              </style>
            </head>
            <body>
              <h1>AuthPlugin Admin</h1>
              <p>Use the buttons below to query basic API data.</p>
              <button onclick="loadPlayers()">Load Players</button>
              <button onclick="loadTokens()">Load Tokens</button>
              <pre id="output"></pre>
              <script>
                async function loadPlayers() {
                  const res = await fetch('/api/players');
                  document.getElementById('output').textContent = await res.text();
                }
                async function loadTokens() {
                  const res = await fetch('/api/tokens');
                  document.getElementById('output').textContent = await res.text();
                }
              </script>
            </body>
            </html>
            """;
    }

    public record AddTokenRequest(String token) {
    }

    public record GenerateTokenRequest(Integer count) {
    }
}
