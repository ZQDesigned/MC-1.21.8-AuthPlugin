package city.zqdesigned.mc.authplugin.token;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class TokenService {
    public static final int MAX_BATCH_COUNT = 200;
    private static final int MAX_GENERATION_ROUNDS = 20;
    private final TokenDao tokenDao;
    private final TokenGenerator tokenGenerator = new TokenGenerator();

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

    public CompletableFuture<List<String>> generateAndStoreTokens(int count) {
        if (count < 1 || count > MAX_BATCH_COUNT) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("count must be between 1 and " + MAX_BATCH_COUNT)
            );
        }

        Set<String> inserted = ConcurrentHashMap.newKeySet();
        return this.generateAndStoreRecursive(count, 0, inserted);
    }

    private CompletableFuture<List<String>> generateAndStoreRecursive(int targetCount, int round, Set<String> inserted) {
        if (inserted.size() >= targetCount) {
            return CompletableFuture.completedFuture(List.copyOf(inserted).subList(0, targetCount));
        }
        if (round >= MAX_GENERATION_ROUNDS) {
            return CompletableFuture.failedFuture(new IllegalStateException("Unable to generate enough unique tokens"));
        }

        int remaining = targetCount - inserted.size();
        int batchSize = remaining;
        List<String> candidates = this.tokenGenerator.generateBatch(batchSize);
        return this.tokenDao.insertBatchIgnoringDuplicates(candidates, System.currentTimeMillis())
            .thenCompose(stored -> {
                inserted.addAll(stored);
                return this.generateAndStoreRecursive(targetCount, round + 1, inserted);
            });
    }
}
