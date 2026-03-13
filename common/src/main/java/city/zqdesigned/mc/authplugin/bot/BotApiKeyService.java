package city.zqdesigned.mc.authplugin.bot;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public final class BotApiKeyService {
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,48}$");
    private final BotApiKeyDao dao;
    private final SecureRandom secureRandom = new SecureRandom();

    public BotApiKeyService(BotApiKeyDao dao) {
        this.dao = dao;
    }

    public CompletableFuture<CreateApiKeyResult> createApiKey(String rawName) {
        String normalizedName = this.normalizeName(rawName);
        if (!NAME_PATTERN.matcher(normalizedName).matches()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("name must match [A-Za-z0-9_-]{3,48}")
            );
        }

        String key = this.generateApiKey();
        long now = System.currentTimeMillis();
        return this.dao.insert(normalizedName, key, now).thenCompose(inserted -> {
            if (!inserted) {
                return CompletableFuture.failedFuture(new IllegalStateException("API key name already exists"));
            }
            return CompletableFuture.completedFuture(new CreateApiKeyResult(normalizedName, key, now));
        });
    }

    public CompletableFuture<List<BotApiKeyView>> listApiKeys() {
        return this.dao.findAll().thenApply(items -> items.stream()
            .map(item -> new BotApiKeyView(
                item.name(),
                this.mask(item.apiKey()),
                item.disabled(),
                item.createdAt(),
                item.lastUsedAt()
            ))
            .toList());
    }

    public CompletableFuture<Boolean> disableApiKey(String rawName) {
        String normalizedName = this.normalizeName(rawName);
        return this.dao.setDisabledByName(normalizedName, true);
    }

    public CompletableFuture<Boolean> enableApiKey(String rawName) {
        String normalizedName = this.normalizeName(rawName);
        return this.dao.setDisabledByName(normalizedName, false);
    }

    public CompletableFuture<Boolean> authenticate(String apiKey) {
        String normalized = apiKey == null ? "" : apiKey.trim();
        if (normalized.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        return this.dao.findByApiKey(normalized).thenCompose(optional -> {
            if (optional.isEmpty() || optional.get().disabled()) {
                return CompletableFuture.completedFuture(false);
            }
            return this.dao.touchLastUsedByApiKey(normalized, System.currentTimeMillis()).thenApply(ignored -> true);
        });
    }

    private String generateApiKey() {
        byte[] random = new byte[24];
        this.secureRandom.nextBytes(random);
        return "apk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private String normalizeName(String rawName) {
        return rawName == null ? "" : rawName.trim().toLowerCase(Locale.ROOT);
    }

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.length() <= 10) {
            return "********";
        }
        return apiKey.substring(0, 6) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    public record CreateApiKeyResult(String name, String apiKey, long createdAt) {
    }

    public record BotApiKeyView(
        String name,
        String maskedApiKey,
        boolean disabled,
        long createdAt,
        long lastUsedAt
    ) {
    }
}
