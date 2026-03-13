package city.zqdesigned.mc.authplugin.bot;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class BotApiKeyCommands {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final BotApiKeyService botApiKeyService = AuthPlugin.bootstrap().botApiKeyService();

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("authplugin")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("apikey")
                    .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes(context -> {
                                String name = StringArgumentType.getString(context, "name");
                                CommandSourceStack source = context.getSource();
                                MinecraftServer server = source.getServer();
                                this.botApiKeyService.createApiKey(name).whenComplete((result, throwable) ->
                                    server.execute(() -> this.handleCreateResult(source, result, throwable))
                                );
                                return 1;
                            })
                        )
                    )
                    .then(Commands.literal("list")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            MinecraftServer server = source.getServer();
                            this.botApiKeyService.listApiKeys().whenComplete((result, throwable) ->
                                server.execute(() -> this.handleListResult(source, result, throwable))
                            );
                            return 1;
                        })
                    )
                    .then(Commands.literal("disable")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes(context -> {
                                String name = StringArgumentType.getString(context, "name");
                                CommandSourceStack source = context.getSource();
                                MinecraftServer server = source.getServer();
                                this.botApiKeyService.disableApiKey(name).whenComplete((result, throwable) ->
                                    server.execute(() -> this.handleToggleResult(source, name, true, result, throwable))
                                );
                                return 1;
                            })
                        )
                    )
                    .then(Commands.literal("enable")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes(context -> {
                                String name = StringArgumentType.getString(context, "name");
                                CommandSourceStack source = context.getSource();
                                MinecraftServer server = source.getServer();
                                this.botApiKeyService.enableApiKey(name).whenComplete((result, throwable) ->
                                    server.execute(() -> this.handleToggleResult(source, name, false, result, throwable))
                                );
                                return 1;
                            })
                        )
                    )
                )
        );
    }

    private void handleCreateResult(
        CommandSourceStack source,
        BotApiKeyService.CreateApiKeyResult result,
        Throwable throwable
    ) {
        if (throwable != null) {
            source.sendFailure(Component.literal(this.errorMessage(throwable)));
            return;
        }

        source.sendSuccess(() -> Component.literal("Created API key '" + result.name() + "'"), false);
        source.sendSuccess(() -> Component.literal("API key (save it now): " + result.apiKey()), false);
    }

    private void handleListResult(
        CommandSourceStack source,
        java.util.List<BotApiKeyService.BotApiKeyView> result,
        Throwable throwable
    ) {
        if (throwable != null) {
            source.sendFailure(Component.literal(this.errorMessage(throwable)));
            return;
        }
        if (result.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No API keys configured."), false);
            return;
        }

        source.sendSuccess(() -> Component.literal("API keys: " + result.size()), false);
        for (BotApiKeyService.BotApiKeyView item : result) {
            String status = item.disabled() ? "disabled" : "enabled";
            String createdAt = formatTime(item.createdAt());
            String lastUsedAt = formatTime(item.lastUsedAt());
            String line = "- " + item.name() + " | " + item.maskedApiKey()
                + " | " + status
                + " | created: " + createdAt
                + " | last used: " + lastUsedAt;
            source.sendSuccess(() -> Component.literal(line), false);
        }
    }

    private void handleToggleResult(
        CommandSourceStack source,
        String name,
        boolean disable,
        Boolean result,
        Throwable throwable
    ) {
        if (throwable != null) {
            source.sendFailure(Component.literal(this.errorMessage(throwable)));
            return;
        }
        if (!Boolean.TRUE.equals(result)) {
            source.sendFailure(Component.literal("API key name not found: " + name));
            return;
        }
        String action = disable ? "disabled" : "enabled";
        source.sendSuccess(() -> Component.literal("API key '" + name + "' " + action + "."), false);
    }

    private String errorMessage(Throwable throwable) {
        Throwable actual = throwable instanceof CompletionException && throwable.getCause() != null
            ? throwable.getCause()
            : throwable;
        return actual.getMessage() == null ? "Operation failed." : actual.getMessage();
    }

    private static String formatTime(long timestamp) {
        if (timestamp <= 0L) {
            return "-";
        }
        return TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
    }
}
