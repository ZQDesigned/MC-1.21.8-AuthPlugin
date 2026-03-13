package city.zqdesigned.mc.authplugin.bot;

import city.zqdesigned.mc.authplugin.db.DatabaseManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class BotApiKeyDao {
    private final DatabaseManager databaseManager;

    public BotApiKeyDao(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public CompletableFuture<Optional<BotApiKeyInfo>> findByApiKey(String apiKey) {
        return this.databaseManager.supplyAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name, api_key, disabled, created_at, last_used_at FROM api_keys WHERE api_key = ?"
            )) {
                statement.setString(1, apiKey);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(this.toInfo(resultSet));
                }
            }
        });
    }

    public CompletableFuture<List<BotApiKeyInfo>> findAll() {
        return this.databaseManager.supplyAsync(connection -> {
            List<BotApiKeyInfo> items = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                     "SELECT name, api_key, disabled, created_at, last_used_at FROM api_keys ORDER BY created_at DESC"
                 )) {
                while (resultSet.next()) {
                    items.add(this.toInfo(resultSet));
                }
            }
            return items;
        });
    }

    public CompletableFuture<Boolean> insert(String name, String apiKey, long now) {
        return this.databaseManager.supplyAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO api_keys(name, api_key, disabled, created_at, last_used_at) VALUES (?, ?, FALSE, ?, ?)"
            )) {
                statement.setString(1, name);
                statement.setString(2, apiKey);
                statement.setLong(3, now);
                statement.setLong(4, now);
                return statement.executeUpdate() == 1;
            } catch (SQLException exception) {
                if (this.isDuplicateKey(exception)) {
                    return false;
                }
                throw exception;
            }
        });
    }

    public CompletableFuture<Boolean> setDisabledByName(String name, boolean disabled) {
        return this.databaseManager.supplyAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE api_keys SET disabled = ? WHERE name = ?"
            )) {
                statement.setBoolean(1, disabled);
                statement.setString(2, name);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Boolean> touchLastUsedByApiKey(String apiKey, long now) {
        return this.databaseManager.supplyAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE api_keys SET last_used_at = ? WHERE api_key = ?"
            )) {
                statement.setLong(1, now);
                statement.setString(2, apiKey);
                return statement.executeUpdate() == 1;
            }
        });
    }

    private BotApiKeyInfo toInfo(ResultSet resultSet) throws SQLException {
        return new BotApiKeyInfo(
            resultSet.getString("name"),
            resultSet.getString("api_key"),
            resultSet.getBoolean("disabled"),
            resultSet.getLong("created_at"),
            resultSet.getLong("last_used_at")
        );
    }

    private boolean isDuplicateKey(SQLException exception) {
        return exception.getSQLState() != null && exception.getSQLState().startsWith("23");
    }
}
