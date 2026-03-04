package city.zqdesigned.mc.authplugin.config;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ConfigManager {
    private static final int PORT_MIN = 18000;
    private static final int PORT_MAX = 30000;
    private static final String ALPHANUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final SecureRandom secureRandom = new SecureRandom();

    private final Path configDirectory = Path.of("config", "authplugin");
    private final Path configFile = this.configDirectory.resolve("config.yml");

    public AuthPluginConfig loadOrCreate() throws IOException {
        Files.createDirectories(this.configDirectory);
        if (!Files.exists(this.configFile)) {
            AuthPluginConfig generated = this.generateConfig();
            this.writeConfig(generated);
            AuthPlugin.LOGGER.info("Generated initial web admin credentials.");
            AuthPlugin.LOGGER.info("Web username: {}", generated.web().username());
            AuthPlugin.LOGGER.info("Web password: {}", generated.web().password());
            AuthPlugin.LOGGER.info("Web port: {}", generated.web().port());
            return generated;
        }

        AuthPluginConfig config = this.readConfig();
        this.validate(config);
        return config;
    }

    private AuthPluginConfig readConfig() throws IOException {
        List<String> lines = Files.readAllLines(this.configFile, StandardCharsets.UTF_8);
        Integer port = null;
        String username = null;
        String password = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals("web:")) {
                continue;
            }
            int separator = trimmed.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(separator + 1).trim();
            if ("port".equals(key)) {
                port = Integer.parseInt(value);
            } else if ("username".equals(key)) {
                username = value;
            } else if ("password".equals(key)) {
                password = value;
            }
        }

        if (port == null || username == null || password == null) {
            throw new IllegalStateException("Missing required web configuration in " + this.configFile);
        }

        return new AuthPluginConfig(new WebConfig(port, username, password));
    }

    private void writeConfig(AuthPluginConfig config) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("web:");
        lines.add("  port: " + config.web().port());
        lines.add("  username: " + config.web().username());
        lines.add("  password: " + config.web().password());
        Files.write(this.configFile, lines, StandardCharsets.UTF_8);
    }

    private AuthPluginConfig generateConfig() {
        int port = this.findAvailablePort();
        String username = "admin_" + this.randomString(8);
        String password = this.randomString(20);
        return new AuthPluginConfig(new WebConfig(port, username, password));
    }

    private String randomString(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = this.secureRandom.nextInt(ALPHANUM.length());
            builder.append(ALPHANUM.charAt(index));
        }
        return builder.toString();
    }

    private int findAvailablePort() {
        for (int i = 0; i < 100; i++) {
            int candidate = PORT_MIN + this.secureRandom.nextInt(PORT_MAX - PORT_MIN);
            if (this.isPortFree(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate an available web port");
    }

    private boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void validate(AuthPluginConfig config) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(config.web(), "web");
        if (config.web().port() < 1 || config.web().port() > 65535) {
            throw new IllegalArgumentException("Web port must be between 1 and 65535");
        }
        if (config.web().username().isBlank()) {
            throw new IllegalArgumentException("Web username cannot be blank");
        }
        if (config.web().password().isBlank()) {
            throw new IllegalArgumentException("Web password cannot be blank");
        }
    }
}
