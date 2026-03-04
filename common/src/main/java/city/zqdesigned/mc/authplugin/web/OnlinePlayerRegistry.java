package city.zqdesigned.mc.authplugin.web;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OnlinePlayerRegistry {
    private final Map<UUID, String> players = new ConcurrentHashMap<>();

    public void playerJoined(UUID uuid, String name) {
        this.players.put(uuid, name);
    }

    public void playerLeft(UUID uuid) {
        this.players.remove(uuid);
    }

    public Map<UUID, String> snapshot() {
        return Map.copyOf(this.players);
    }
}
