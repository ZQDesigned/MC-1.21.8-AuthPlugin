package city.zqdesigned.mc.authplugin.restriction;

import city.zqdesigned.mc.authplugin.auth.AuthService;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AuthRestrictionService {
    private static final long DENIAL_MESSAGE_COOLDOWN_MILLIS = 1500L;
    private final AuthService authService;
    private final ConcurrentMap<DenialMessageKey, Long> denialMessageCooldowns = new ConcurrentHashMap<>();

    public AuthRestrictionService(AuthService authService) {
        this.authService = authService;
    }

    public boolean isActionAllowed(UUID playerUuid, PlayerActionType actionType) {
        if (this.authService.isLoggedIn(playerUuid)) {
            return true;
        }
        return false;
    }

    public boolean isCommandAllowed(UUID playerUuid, String rawCommand) {
        if (this.authService.isLoggedIn(playerUuid)) {
            return true;
        }
        if (rawCommand == null) {
            return false;
        }
        String normalized = rawCommand.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        return normalized.equals("login") || normalized.startsWith("login ");
    }

    public String denialMessage(PlayerActionType actionType) {
        return switch (actionType) {
            case MOVE -> "You must login first. Use /login <token>.";
            case INTERACT -> "You must login before interacting.";
            case ATTACK -> "You must login before attacking.";
            case BUILD_OR_BREAK -> "You must login before building or breaking blocks.";
            case COMMAND -> "Only /login <token> is available before authentication.";
        };
    }

    public boolean shouldSendDenialMessage(UUID playerUuid, PlayerActionType actionType) {
        long now = System.currentTimeMillis();
        DenialMessageKey key = new DenialMessageKey(playerUuid, actionType);
        Long previous = this.denialMessageCooldowns.putIfAbsent(key, now);
        if (previous == null) {
            return true;
        }
        if (now - previous >= DENIAL_MESSAGE_COOLDOWN_MILLIS) {
            this.denialMessageCooldowns.put(key, now);
            return true;
        }
        return false;
    }

    public void clearDenialCooldown(UUID playerUuid) {
        this.denialMessageCooldowns.keySet().removeIf(key -> key.playerUuid().equals(playerUuid));
    }

    private record DenialMessageKey(UUID playerUuid, PlayerActionType actionType) {
    }
}
