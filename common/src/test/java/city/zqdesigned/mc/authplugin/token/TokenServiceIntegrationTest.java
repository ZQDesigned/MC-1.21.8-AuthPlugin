package city.zqdesigned.mc.authplugin.token;

import city.zqdesigned.mc.authplugin.db.DatabaseManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenServiceIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldGenerate16CharTokensWithAllRequiredCharacterClasses() throws Exception {
        DatabaseManager databaseManager = this.newDatabaseManager("generator");
        databaseManager.initialize();
        try {
            TokenService tokenService = new TokenService(new TokenDao(databaseManager));
            List<String> tokens = tokenService.generateAndStoreTokens(40).join();

            assertEquals(40, tokens.size());
            assertEquals(40, new HashSet<>(tokens).size());
            assertEquals(40, tokenService.listTokens().join().size());
            for (String token : tokens) {
                assertEquals(16, token.length());
                assertTrue(token.matches("^[A-Za-z0-9]{16}$"));
                assertTrue(token.chars().anyMatch(Character::isUpperCase));
                assertTrue(token.chars().anyMatch(Character::isLowerCase));
                assertTrue(token.chars().anyMatch(Character::isDigit));
            }
        } finally {
            databaseManager.shutdown();
        }
    }

    @Test
    void shouldRejectInvalidBatchCount() throws Exception {
        DatabaseManager databaseManager = this.newDatabaseManager("batch-bounds");
        databaseManager.initialize();
        try {
            TokenService tokenService = new TokenService(new TokenDao(databaseManager));
            assertThrows(CompletionException.class, () -> tokenService.generateAndStoreTokens(0).join());
            assertThrows(CompletionException.class, () -> tokenService.generateAndStoreTokens(201).join());
        } finally {
            databaseManager.shutdown();
        }
    }

    @Test
    void shouldPersistTokensAcrossDatabaseRestart() throws Exception {
        DatabaseManager firstManager = this.newDatabaseManager("persistence");
        firstManager.initialize();
        TokenDao firstDao = new TokenDao(firstManager);
        assertTrue(firstDao.insertToken("PersistToken1234A", System.currentTimeMillis()).join());
        firstManager.shutdown();

        DatabaseManager secondManager = this.newDatabaseManager("persistence");
        secondManager.initialize();
        try {
            TokenDao secondDao = new TokenDao(secondManager);
            assertTrue(secondDao.findByToken("PersistToken1234A").join().isPresent());
        } finally {
            secondManager.shutdown();
        }
    }

    @Test
    void shouldPreventDoubleBindingUnderConcurrency() throws Exception {
        DatabaseManager databaseManager = this.newDatabaseManager("concurrency");
        databaseManager.initialize();
        try {
            TokenDao tokenDao = new TokenDao(databaseManager);
            assertTrue(tokenDao.insertToken("ConcurrentToken12", System.currentTimeMillis()).join());

            UUID playerA = UUID.randomUUID();
            UUID playerB = UUID.randomUUID();
            List<BindingAttempt> attempts = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                UUID player = i % 2 == 0 ? playerA : playerB;
                CompletableFuture<TokenBindResult> future = tokenDao.bindIfAllowed(
                    "ConcurrentToken12",
                    player,
                    System.currentTimeMillis()
                );
                attempts.add(new BindingAttempt(player, future));
            }

            CompletableFuture.allOf(attempts.stream().map(BindingAttempt::future).toArray(CompletableFuture[]::new)).join();
            Set<UUID> successfulPlayers = new HashSet<>();
            long rejectedAsOther = 0L;
            for (BindingAttempt attempt : attempts) {
                TokenBindResult result = attempt.future().join();
                if (result.outcome() == TokenBindOutcome.SUCCESS) {
                    successfulPlayers.add(attempt.playerUuid());
                }
                if (result.outcome() == TokenBindOutcome.TOKEN_BOUND_TO_OTHER) {
                    rejectedAsOther++;
                }
            }

            assertEquals(1, successfulPlayers.size());
            assertTrue(rejectedAsOther > 0);
            assertFalse(tokenDao.findByToken("ConcurrentToken12").join().isEmpty());
        } finally {
            databaseManager.shutdown();
        }
    }

    private DatabaseManager newDatabaseManager(String name) {
        Path databaseDirectory = this.tempDir.resolve(name).resolve("database");
        String jdbcUrl = "jdbc:h2:file:" + databaseDirectory.resolve("authplugin").toAbsolutePath().toString();
        return new DatabaseManager(databaseDirectory, jdbcUrl);
    }

    private record BindingAttempt(UUID playerUuid, CompletableFuture<TokenBindResult> future) {
    }
}
