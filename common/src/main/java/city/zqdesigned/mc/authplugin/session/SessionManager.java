package city.zqdesigned.mc.authplugin.session;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager {
    private final Set<UUID> loggedInPlayers = ConcurrentHashMap.newKeySet();

    public void markLoggedIn(UUID playerUuid) {
        this.loggedInPlayers.add(playerUuid);
    }

    public void markLoggedOut(UUID playerUuid) {
        this.loggedInPlayers.remove(playerUuid);
    }

    public boolean isLoggedIn(UUID playerUuid) {
        return this.loggedInPlayers.contains(playerUuid);
    }

    public void clear() {
        this.loggedInPlayers.clear();
    }
}
