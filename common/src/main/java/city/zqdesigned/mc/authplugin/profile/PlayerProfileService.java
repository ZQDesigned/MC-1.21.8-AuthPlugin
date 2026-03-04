package city.zqdesigned.mc.authplugin.profile;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PlayerProfileService {
    private final PlayerProfileDao playerProfileDao;

    public PlayerProfileService(PlayerProfileDao playerProfileDao) {
        this.playerProfileDao = playerProfileDao;
    }

    public CompletableFuture<Void> updatePlayerName(UUID uuid, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return this.playerProfileDao.upsert(uuid, playerName, System.currentTimeMillis());
    }

    public CompletableFuture<Map<UUID, String>> findNames(Set<UUID> uuids) {
        return this.playerProfileDao.findNames(uuids);
    }
}
