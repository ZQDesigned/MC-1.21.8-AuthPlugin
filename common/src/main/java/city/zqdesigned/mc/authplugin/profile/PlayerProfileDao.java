package city.zqdesigned.mc.authplugin.profile;

import city.zqdesigned.mc.authplugin.db.DatabaseManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class PlayerProfileDao {
    private final DatabaseManager databaseManager;

    public PlayerProfileDao(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public CompletableFuture<Void> upsert(UUID uuid, String playerName, long lastSeenAt) {
        return this.databaseManager.runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "MERGE INTO player_profiles(uuid, player_name, last_seen_at) KEY(uuid) VALUES (?, ?, ?)"
            )) {
                statement.setString(1, uuid.toString());
                statement.setString(2, playerName);
                statement.setLong(3, lastSeenAt);
                statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Map<UUID, String>> findNames(Set<UUID> uuids) {
        if (uuids.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        return this.databaseManager.supplyAsync(connection -> {
            String placeholders = uuids.stream().map(uuid -> "?").collect(Collectors.joining(","));
            String sql = "SELECT uuid, player_name FROM player_profiles WHERE uuid IN (" + placeholders + ")";
            Map<UUID, String> names = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                for (UUID uuid : uuids) {
                    statement.setString(index++, uuid.toString());
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        names.put(
                            UUID.fromString(resultSet.getString("uuid")),
                            resultSet.getString("player_name")
                        );
                    }
                }
            }
            return names;
        });
    }
}
