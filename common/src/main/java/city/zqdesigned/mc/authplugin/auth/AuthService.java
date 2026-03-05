package city.zqdesigned.mc.authplugin.auth;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import city.zqdesigned.mc.authplugin.session.SessionManager;
import city.zqdesigned.mc.authplugin.token.TokenBindOutcome;
import city.zqdesigned.mc.authplugin.token.TokenBindResult;
import city.zqdesigned.mc.authplugin.token.TokenInfo;
import city.zqdesigned.mc.authplugin.token.TokenService;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public final class AuthService {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9]{1,128}$");
    private final TokenService tokenService;
    private final SessionManager sessionManager;

    public AuthService(TokenService tokenService, SessionManager sessionManager) {
        this.tokenService = tokenService;
        this.sessionManager = sessionManager;
    }

    public CompletableFuture<LoginResult> login(UUID playerUuid, String token) {
        String normalizedToken = token == null ? "" : token.trim();
        if (!TOKEN_PATTERN.matcher(normalizedToken).matches()) {
            return CompletableFuture.completedFuture(
                new LoginResult(
                    LoginResultType.INVALID_TOKEN_FORMAT,
                    "Login failed: invalid token format. Use only letters and numbers, then run /login <token> again."
                )
            );
        }

        return this.tokenService.bindIfAllowed(normalizedToken, playerUuid)
            .thenApply(result -> this.mapResult(playerUuid, normalizedToken, result));
    }

    public CompletableFuture<Boolean> tryAutoLogin(UUID playerUuid) {
        return this.tokenService.findByPlayer(playerUuid)
            .thenCompose(optionalToken -> this.tryAutoLoginFromToken(playerUuid, optionalToken));
    }

    public boolean isLoggedIn(UUID playerUuid) {
        return this.sessionManager.isLoggedIn(playerUuid);
    }

    public void onPlayerDisconnect(UUID playerUuid) {
        this.sessionManager.markLoggedOut(playerUuid);
    }

    public void resetSessions() {
        this.sessionManager.clear();
    }

    private CompletableFuture<Boolean> tryAutoLoginFromToken(UUID playerUuid, Optional<TokenInfo> optionalToken) {
        if (optionalToken.isEmpty() || optionalToken.get().disabled()) {
            return CompletableFuture.completedFuture(false);
        }

        TokenInfo tokenInfo = optionalToken.get();
        return this.tokenService.bindIfAllowed(tokenInfo.token(), playerUuid).thenApply(result -> {
            if (result.outcome() == TokenBindOutcome.SUCCESS) {
                this.sessionManager.markLoggedIn(playerUuid);
                return true;
            }
            return false;
        });
    }

    private LoginResult mapResult(UUID playerUuid, String token, TokenBindResult bindResult) {
        return switch (bindResult.outcome()) {
            case SUCCESS -> {
                this.sessionManager.markLoggedIn(playerUuid);
                if (bindResult.newlyBound()) {
                    AuthPlugin.LOGGER.info("Token {} bound to player {}", token, playerUuid);
                    yield new LoginResult(
                        LoginResultType.SUCCESS_NEW_BIND,
                        "Login successful. This token is now bound to your account. Rejoin later to use auto-login."
                    );
                }
                yield new LoginResult(
                    LoginResultType.SUCCESS_ALREADY_BOUND,
                    "Login successful. Authentication is complete."
                );
            }
            case TOKEN_NOT_FOUND -> {
                AuthPlugin.LOGGER.info("Login failed for {}: token not found", playerUuid);
                yield new LoginResult(
                    LoginResultType.TOKEN_NOT_FOUND,
                    "Login failed: token not found. Check the token text and try again. "
                        + "If you do not have a valid token, contact the server administrator."
                );
            }
            case TOKEN_DISABLED -> {
                AuthPlugin.LOGGER.info("Login failed for {}: token disabled", playerUuid);
                yield new LoginResult(
                    LoginResultType.TOKEN_DISABLED,
                    "Login failed: this token is disabled. Contact the server administrator to enable it or issue a new token."
                );
            }
            case TOKEN_BOUND_TO_OTHER -> {
                AuthPlugin.LOGGER.info("Login failed for {}: token bound to another player", playerUuid);
                yield new LoginResult(
                    LoginResultType.TOKEN_BOUND_TO_OTHER,
                    "Login failed: this token is already bound to another account. "
                        + "Use your own token, or contact the server administrator to reset the token binding."
                );
            }
        };
    }
}
