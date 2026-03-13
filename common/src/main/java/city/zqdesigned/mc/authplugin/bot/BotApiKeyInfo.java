package city.zqdesigned.mc.authplugin.bot;

public record BotApiKeyInfo(
    String name,
    String apiKey,
    boolean disabled,
    long createdAt,
    long lastUsedAt
) {
}
