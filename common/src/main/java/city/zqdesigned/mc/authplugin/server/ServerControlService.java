package city.zqdesigned.mc.authplugin.server;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;

public final class ServerControlService {
    public static final int DEFAULT_LOG_LIMIT = 200;
    public static final int MAX_LOG_LIMIT = 2000;

    private final AtomicReference<MinecraftServer> serverRef = new AtomicReference<>();
    private final AtomicLong serverAttachedAt = new AtomicLong(0L);

    public void attachServer(MinecraftServer server) {
        this.serverRef.set(server);
        this.serverAttachedAt.set(System.currentTimeMillis());
    }

    public void detachServer(MinecraftServer server) {
        this.serverRef.compareAndSet(server, null);
        this.serverAttachedAt.set(0L);
    }

    public ServerStatus snapshotStatus() {
        MinecraftServer server = this.serverRef.get();
        if (server == null) {
            return new ServerStatus(false, false, null, null, 0, 0, 0L, false);
        }

        int onlinePlayers = server.getPlayerList() == null ? 0 : server.getPlayerList().getPlayerCount();
        int maxPlayers = server.getPlayerList() == null ? 0 : server.getPlayerList().getMaxPlayers();
        long attachedAt = this.serverAttachedAt.get();
        long uptimeMillis = attachedAt <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - attachedAt);

        return new ServerStatus(
            true,
            server.isRunning(),
            server.getMotd(),
            server.getServerVersion(),
            onlinePlayers,
            maxPlayers,
            uptimeMillis,
            this.isStopMsgAvailable(server)
        );
    }

    public CompletableFuture<CommandExecutionResult> executeConsoleCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) {
            return failed(new IllegalArgumentException("Command cannot be empty"));
        }
        return this.runOnServerThread(server -> {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), normalized);
            return new CommandExecutionResult(normalized, System.currentTimeMillis());
        });
    }

    public CompletableFuture<ShutdownRequestResult> requestShutdown(String message) {
        String normalized = message == null ? "" : message.trim();
        return this.runOnServerThread(server -> {
            boolean stopMsgUsed = false;
            if (!normalized.isEmpty()) {
                stopMsgUsed = this.tryExecuteStopMsg(server, normalized);
                if (!stopMsgUsed) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "say " + normalized);
                }
            }

            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "stop");
            return new ShutdownRequestResult(true, stopMsgUsed, normalized, System.currentTimeMillis());
        });
    }

    public List<String> readRecentLogs(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, MAX_LOG_LIMIT));
        Path logFile = this.resolveLatestLogPath();
        if (!Files.exists(logFile)) {
            return List.of();
        }

        Deque<String> ringBuffer = new ArrayDeque<>(limit);
        try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (ringBuffer.size() == limit) {
                    ringBuffer.removeFirst();
                }
                ringBuffer.addLast(line);
            }
        } catch (IOException exception) {
            AuthPlugin.LOGGER.warn("Failed to read server log file {}", logFile, exception);
            return List.of();
        }

        return List.copyOf(new ArrayList<>(ringBuffer));
    }

    private boolean tryExecuteStopMsg(MinecraftServer server, String message) {
        if (!this.isStopMsgAvailable(server)) {
            return false;
        }
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "stopmsg " + message);
            return true;
        } catch (Exception exception) {
            AuthPlugin.LOGGER.warn("Failed to execute stopmsg command", exception);
            return false;
        }
    }

    private boolean isStopMsgAvailable(MinecraftServer server) {
        return server.getCommands()
            .getDispatcher()
            .getRoot()
            .getChild("stopmsg") != null;
    }

    private Path resolveLatestLogPath() {
        MinecraftServer server = this.serverRef.get();
        if (server != null) {
            return server.getFile("logs/latest.log");
        }
        return Path.of("logs", "latest.log");
    }

    private <T> CompletableFuture<T> runOnServerThread(Function<MinecraftServer, T> action) {
        MinecraftServer server = this.serverRef.get();
        if (server == null) {
            return failed(new IllegalStateException("Minecraft server is not available"));
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(action.apply(server));
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private static <T> CompletableFuture<T> failed(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    public record ServerStatus(
        boolean available,
        boolean running,
        String motd,
        String version,
        int onlinePlayers,
        int maxPlayers,
        long uptimeMillis,
        boolean stopMsgAvailable
    ) {
    }

    public record ShutdownRequestResult(
        boolean initiated,
        boolean stopMsgUsed,
        String message,
        long requestedAt
    ) {
    }

    public record CommandExecutionResult(String command, long executedAt) {
    }
}
