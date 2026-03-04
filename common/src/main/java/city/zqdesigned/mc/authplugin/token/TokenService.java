package city.zqdesigned.mc.authplugin.token;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class TokenService {
    private final TokenDao tokenDao;

    public TokenService(TokenDao tokenDao) {
        this.tokenDao = tokenDao;
    }

    public CompletableFuture<Optional<TokenInfo>> findByToken(String token) {
        return this.tokenDao.findByToken(token);
    }

    public CompletableFuture<Optional<TokenInfo>> findByPlayer(UUID playerUuid) {
        return this.tokenDao.findByBoundUuid(playerUuid);
    }

    public CompletableFuture<List<TokenInfo>> listTokens() {
        return this.tokenDao.findAll();
    }

    public CompletableFuture<Boolean> createToken(String token) {
        return this.tokenDao.insertToken(token, System.currentTimeMillis());
    }

    public CompletableFuture<Boolean> deleteToken(String token) {
        return this.tokenDao.deleteToken(token);
    }

    public CompletableFuture<Boolean> disableToken(String token) {
        return this.tokenDao.setDisabled(token, true);
    }

    public CompletableFuture<Boolean> enableToken(String token) {
        return this.tokenDao.setDisabled(token, false);
    }

    public CompletableFuture<TokenBindResult> bindIfAllowed(String token, UUID playerUuid) {
        return this.tokenDao.bindIfAllowed(token, playerUuid, System.currentTimeMillis());
    }
}
