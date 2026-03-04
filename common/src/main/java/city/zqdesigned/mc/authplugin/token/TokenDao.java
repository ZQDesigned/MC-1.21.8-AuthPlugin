package city.zqdesigned.mc.authplugin.token;

import city.zqdesigned.mc.authplugin.db.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class TokenDao {
    private final DatabaseManager databaseManager;

    public TokenDao(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public CompletableFuture<Optional<TokenInfo>> findByToken(String token) {
        return this.databaseManager.supplyAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT token, bound_uuid, disabled, created_at, last_used_at FROM tokens WHERE token = ?"
            )) {
                statement.setString(1, token);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(this.toTokenInfo(resultSet));
                }
            }
        });
    }

    public CompletableFuture<Optional<TokenInfo>> findByBoundUuid(UUID uuid) {
        return this.databaseManager.supplyAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT token, bound_uuid, disabled, created_at, last_used_at FROM tokens WHERE bound_uuid = ?"
            )) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(this.toTokenInfo(resultSet));
                }
            }
        });
    }

    public CompletableFuture<List<TokenInfo>> findAll() {
        return this.databaseManager.supplyAsync(connection -> {
            List<TokenInfo> tokens = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                     "SELECT token, bound_uuid, disabled, created_at, last_used_at FROM tokens ORDER BY created_at DESC"
                 )) {
                while (resultSet.next()) {
                    tokens.add(this.toTokenInfo(resultSet));
                }
            }
            return tokens;
        });
    }

    public CompletableFuture<Boolean> insertToken(String token, long now) {
        return this.databaseManager.supplyAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO tokens(token, bound_uuid, disabled, created_at, last_used_at) VALUES (?, NULL, FALSE, ?, ?)"
            )) {
                statement.setString(1, token);
                statement.setLong(2, now);
                statement.setLong(3, now);
                return statement.executeUpdate() == 1;
            } catch (SQLException exception) {
                if (isDuplicateKey(exception)) {
                    return false;
                }
                throw exception;
            }
        });
    }

    public CompletableFuture<List<String>> insertBatchIgnoringDuplicates(List<String> tokens, long now) {
        return this.databaseManager.transactionAsync(connection -> {
            List<String> insertedTokens = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO tokens(token, bound_uuid, disabled, created_at, last_used_at) VALUES (?, NULL, FALSE, ?, ?)"
            )) {
                for (String token : tokens) {
                    try {
                        statement.setString(1, token);
                        statement.setLong(2, now);
                        statement.setLong(3, now);
                        if (statement.executeUpdate() == 1) {
                            insertedTokens.add(token);
                        }
                    } catch (SQLException exception) {
                        if (!isDuplicateKey(exception)) {
                            throw exception;
                        }
                    }
                }
            }
            return insertedTokens;
        });
    }

    public CompletableFuture<Boolean> deleteToken(String token) {
        return this.databaseManager.supplyAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM tokens WHERE token = ?"
            )) {
                statement.setString(1, token);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Boolean> setDisabled(String token, boolean disabled) {
        return this.databaseManager.supplyAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE tokens SET disabled = ? WHERE token = ?"
            )) {
                statement.setBoolean(1, disabled);
                statement.setString(2, token);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Boolean> touchLastUsed(String token, long now) {
        return this.databaseManager.supplyAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE tokens SET last_used_at = ? WHERE token = ?"
            )) {
                statement.setLong(1, now);
                statement.setString(2, token);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<TokenBindResult> bindIfAllowed(String token, UUID playerUuid, long now) {
        return this.databaseManager.transactionAsync(connection -> this.bindInTransaction(connection, token, playerUuid, now));
    }

    private TokenBindResult bindInTransaction(Connection connection, String token, UUID playerUuid, long now) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
            "SELECT bound_uuid, disabled FROM tokens WHERE token = ? FOR UPDATE"
        )) {
            select.setString(1, token);
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    return TokenBindResult.of(TokenBindOutcome.TOKEN_NOT_FOUND);
                }

                if (resultSet.getBoolean("disabled")) {
                    return TokenBindResult.of(TokenBindOutcome.TOKEN_DISABLED);
                }

                String boundUuid = resultSet.getString("bound_uuid");
                if (boundUuid != null && !boundUuid.equals(playerUuid.toString())) {
                    return TokenBindResult.of(TokenBindOutcome.TOKEN_BOUND_TO_OTHER);
                }

                boolean newlyBound = boundUuid == null;
                try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE tokens SET bound_uuid = ?, last_used_at = ? WHERE token = ?"
                )) {
                    update.setString(1, playerUuid.toString());
                    update.setLong(2, now);
                    update.setString(3, token);
                    update.executeUpdate();
                }

                return TokenBindResult.success(newlyBound);
            }
        }
    }

    private TokenInfo toTokenInfo(ResultSet resultSet) throws SQLException {
        String boundUuid = resultSet.getString("bound_uuid");
        UUID uuid = boundUuid == null ? null : UUID.fromString(boundUuid);
        return new TokenInfo(
            resultSet.getString("token"),
            uuid,
            resultSet.getBoolean("disabled"),
            resultSet.getLong("created_at"),
            resultSet.getLong("last_used_at")
        );
    }

    private boolean isDuplicateKey(SQLException exception) {
        return exception.getSQLState() != null && exception.getSQLState().startsWith("23");
    }
}
