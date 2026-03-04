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

        this.app.before("/", this::requireBasicAuth);
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
                :root {
                  --bg: #f6f8fb;
                  --panel: #ffffff;
                  --text: #152132;
                  --muted: #647089;
                  --accent: #1f6feb;
                  --accent-dark: #1656b3;
                  --danger: #cc3d3d;
                  --danger-dark: #a72c2c;
                  --border: #d7dfeb;
                  --chip: #e8f0ff;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  font-family: "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
                  background: radial-gradient(circle at top right, #dce8ff 0%, var(--bg) 36%);
                  color: var(--text);
                }
                .container {
                  max-width: 1100px;
                  margin: 28px auto;
                  padding: 0 16px;
                }
                .card {
                  background: var(--panel);
                  border: 1px solid var(--border);
                  border-radius: 14px;
                  box-shadow: 0 8px 20px rgba(20, 34, 63, 0.06);
                  padding: 16px;
                  margin-bottom: 14px;
                }
                h1 {
                  margin: 0 0 8px 0;
                  font-size: 28px;
                }
                .sub {
                  color: var(--muted);
                  margin-bottom: 8px;
                }
                .row {
                  display: flex;
                  gap: 10px;
                  flex-wrap: wrap;
                  align-items: center;
                }
                input[type="number"] {
                  width: 120px;
                  padding: 8px 10px;
                  border: 1px solid var(--border);
                  border-radius: 8px;
                  font-size: 14px;
                }
                button {
                  border: 0;
                  border-radius: 9px;
                  padding: 8px 12px;
                  font-weight: 600;
                  cursor: pointer;
                }
                button.primary {
                  background: var(--accent);
                  color: #fff;
                }
                button.primary:hover { background: var(--accent-dark); }
                button.ghost {
                  background: #edf3ff;
                  color: #2b4979;
                }
                button.ghost:hover { background: #dde9ff; }
                button.danger {
                  background: var(--danger);
                  color: #fff;
                }
                button.danger:hover { background: var(--danger-dark); }
                button:disabled {
                  opacity: 0.5;
                  cursor: not-allowed;
                }
                #status {
                  min-height: 22px;
                  margin-top: 10px;
                  font-weight: 600;
                }
                #status.ok { color: #16803f; }
                #status.err { color: #b02a2a; }
                .chips {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 8px;
                  margin-top: 10px;
                }
                .chip {
                  background: var(--chip);
                  border: 1px solid #c7daff;
                  border-radius: 999px;
                  padding: 4px 10px;
                  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                  font-size: 12px;
                }
                table {
                  width: 100%;
                  border-collapse: collapse;
                  font-size: 14px;
                }
                th, td {
                  border-bottom: 1px solid var(--border);
                  padding: 10px 8px;
                  vertical-align: top;
                }
                th {
                  text-align: left;
                  color: var(--muted);
                  font-weight: 600;
                }
                td code {
                  font-size: 12px;
                  white-space: nowrap;
                }
                .token {
                  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                  font-weight: 600;
                }
                .tag {
                  display: inline-block;
                  border-radius: 999px;
                  padding: 2px 8px;
                  font-size: 12px;
                  font-weight: 700;
                }
                .tag.active { background: #e4f8eb; color: #1a7d45; }
                .tag.disabled { background: #ffe9e9; color: #a33030; }
                .actions {
                  display: flex;
                  gap: 6px;
                }
                .muted { color: var(--muted); }
                @media (max-width: 900px) {
                  table, thead, tbody, th, td, tr { display: block; }
                  thead { display: none; }
                  tr {
                    border: 1px solid var(--border);
                    border-radius: 10px;
                    padding: 8px;
                    margin-bottom: 10px;
                    background: #fff;
                  }
                  td {
                    border-bottom: none;
                    padding: 4px 0;
                  }
                  td::before {
                    content: attr(data-label) ": ";
                    color: var(--muted);
                    font-weight: 600;
                  }
                }
              </style>
            </head>
            <body>
              <div class="container">
                <div class="card">
                  <h1>AuthPlugin Admin</h1>
                  <div class="sub">Token dashboard: batch generate, disable, and delete.</div>
                  <div class="row">
                    <label for="generateCount">Generate count (1-200)</label>
                    <input id="generateCount" type="number" min="1" max="200" value="10" />
                    <button class="primary" id="generateBtn">Generate</button>
                    <button class="ghost" id="refreshBtn">Refresh List</button>
                  </div>
                  <div id="status"></div>
                  <div id="generatedTokens" class="chips"></div>
                </div>

                <div class="card">
                  <div class="row" style="justify-content: space-between;">
                    <strong>Token List</strong>
                    <span class="muted" id="tokenCount"></span>
                  </div>
                  <div id="tokenTableWrap"></div>
                </div>
              </div>

              <script>
                const statusEl = document.getElementById('status');
                const generatedTokensEl = document.getElementById('generatedTokens');
                const tokenTableWrapEl = document.getElementById('tokenTableWrap');
                const tokenCountEl = document.getElementById('tokenCount');

                function setStatus(message, isError = false) {
                  statusEl.textContent = message || '';
                  statusEl.className = isError ? 'err' : 'ok';
                }

                function escapeHtml(value) {
                  return String(value)
                    .replaceAll('&', '&amp;')
                    .replaceAll('<', '&lt;')
                    .replaceAll('>', '&gt;')
                    .replaceAll('"', '&quot;')
                    .replaceAll("'", '&#39;');
                }

                async function apiRequest(url, options = {}) {
                  const response = await fetch(url, {
                    headers: { 'Content-Type': 'application/json' },
                    ...options
                  });

                  if (!response.ok) {
                    let message = 'Request failed';
                    try {
                      const data = await response.json();
                      message = data.error || message;
                    } catch (ignored) {
                    }
                    throw new Error(message);
                  }

                  if (response.status === 204) {
                    return {};
                  }
                  return response.json();
                }

                function formatTime(timestamp) {
                  if (!timestamp) {
                    return '-';
                  }
                  const date = new Date(timestamp);
                  if (Number.isNaN(date.getTime())) {
                    return '-';
                  }
                  return date.toLocaleString();
                }

                function renderGeneratedTokens(tokens) {
                  if (!tokens || tokens.length === 0) {
                    generatedTokensEl.innerHTML = '';
                    return;
                  }
                  generatedTokensEl.innerHTML = tokens
                    .map(token => '<span class="chip">' + escapeHtml(token) + '</span>')
                    .join('');
                }

                function renderTokenTable(tokens) {
                  tokenCountEl.textContent = tokens.length + ' tokens';
                  if (tokens.length === 0) {
                    tokenTableWrapEl.innerHTML = '<p class="muted">No tokens found.</p>';
                    return;
                  }

                  const rows = tokens.map(token => {
                    const bound = token.boundPlayer || '-';
                    const statusTag = token.disabled
                      ? '<span class="tag disabled">DISABLED</span>'
                      : '<span class="tag active">ACTIVE</span>';
                    const disableDisabled = token.disabled ? 'disabled' : '';
                    const escapedToken = encodeURIComponent(token.token);

                    return `
                      <tr>
                        <td data-label="Token"><span class="token">${escapeHtml(token.token)}</span></td>
                        <td data-label="Bound UUID"><code>${escapeHtml(bound)}</code></td>
                        <td data-label="Status">${statusTag}</td>
                        <td data-label="Created">${escapeHtml(formatTime(token.createdAt))}</td>
                        <td data-label="Last Used">${escapeHtml(formatTime(token.lastUsedAt))}</td>
                        <td data-label="Actions">
                          <div class="actions">
                            <button class="ghost" ${disableDisabled} onclick="disableToken('${escapedToken}')">Disable</button>
                            <button class="danger" onclick="deleteToken('${escapedToken}')">Delete</button>
                          </div>
                        </td>
                      </tr>
                    `;
                  }).join('');

                  tokenTableWrapEl.innerHTML = `
                    <table>
                      <thead>
                        <tr>
                          <th>Token</th>
                          <th>Bound UUID</th>
                          <th>Status</th>
                          <th>Created</th>
                          <th>Last Used</th>
                          <th>Actions</th>
                        </tr>
                      </thead>
                      <tbody>${rows}</tbody>
                    </table>
                  `;
                }

                async function loadTokens(showStatus = false) {
                  try {
                    const data = await apiRequest('/api/tokens');
                    renderTokenTable(data.tokens || []);
                    if (showStatus) {
                      setStatus('Token list refreshed.');
                    }
                  } catch (error) {
                    setStatus(error.message, true);
                  }
                }

                async function generateTokens() {
                  const countInput = document.getElementById('generateCount');
                  const count = Number.parseInt(countInput.value, 10);
                  if (!Number.isInteger(count) || count < 1 || count > 200) {
                    setStatus('Count must be an integer between 1 and 200.', true);
                    return;
                  }

                  try {
                    const data = await apiRequest('/api/tokens/generate', {
                      method: 'POST',
                      body: JSON.stringify({ count })
                    });
                    renderGeneratedTokens(data.tokens || []);
                    setStatus('Generated ' + (data.count || 0) + ' token(s).');
                    await loadTokens(false);
                  } catch (error) {
                    setStatus(error.message, true);
                  }
                }

                async function disableToken(encodedToken) {
                  try {
                    await apiRequest('/api/tokens/' + encodedToken + '/disable', { method: 'PATCH' });
                    setStatus('Token disabled.');
                    await loadTokens(false);
                  } catch (error) {
                    setStatus(error.message, true);
                  }
                }

                async function deleteToken(encodedToken) {
                  try {
                    await apiRequest('/api/tokens/' + encodedToken, { method: 'DELETE' });
                    setStatus('Token deleted.');
                    await loadTokens(false);
                  } catch (error) {
                    setStatus(error.message, true);
                  }
                }

                document.getElementById('generateBtn').addEventListener('click', generateTokens);
                document.getElementById('refreshBtn').addEventListener('click', () => loadTokens(true));
                loadTokens(false);
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
