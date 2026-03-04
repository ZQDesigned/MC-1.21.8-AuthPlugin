package city.zqdesigned.mc.authplugin.token;

import java.util.Optional;
import java.util.UUID;

public record TokenInfo(
    String token,
    UUID boundPlayer,
    boolean disabled,
    long createdAt,
    long lastUsedAt
) {
    public Optional<UUID> boundPlayerOptional() {
        return Optional.ofNullable(this.boundPlayer);
    }
}
