package city.zqdesigned.mc.authplugin.fabric;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import city.zqdesigned.mc.authplugin.auth.AuthService;
import city.zqdesigned.mc.authplugin.auth.LoginResult;
import city.zqdesigned.mc.authplugin.message.AuthPromptMessages;
import city.zqdesigned.mc.authplugin.profile.PlayerProfileService;
import city.zqdesigned.mc.authplugin.restriction.AuthRestrictionService;
import city.zqdesigned.mc.authplugin.restriction.PlayerActionType;
import city.zqdesigned.mc.authplugin.web.OnlinePlayerRegistry;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class FabricAuthRuntime {
    private final AuthService authService = AuthPlugin.bootstrap().authService();
    private final PlayerProfileService playerProfileService = AuthPlugin.bootstrap().playerProfileService();
    private final AuthRestrictionService restrictionService = AuthPlugin.bootstrap().restrictionService();
    private final OnlinePlayerRegistry onlinePlayerRegistry = AuthPlugin.bootstrap().onlinePlayerRegistry();
    private final Map<UUID, Vec3> frozenPositions = new ConcurrentHashMap<>();

    public void register() {
        this.registerLoginCommand();
        this.registerLifecycleEvents();
        this.registerRestrictionEvents();
    }

    private void registerLoginCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            dispatcher.register(Commands.literal("login")
                .then(Commands.argument("token", StringArgumentType.string())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String token = StringArgumentType.getString(context, "token");
                        this.authService.login(player.getUUID(), token)
                            .whenComplete((result, throwable) -> context.getSource().getServer().execute(
                                () -> this.onLoginResult(player, result, throwable)
                            ));
                        return 1;
                    })
                )
            );
        });
    }

    private void registerLifecycleEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            UUID playerUuid = player.getUUID();
            this.frozenPositions.put(playerUuid, player.position());
            String playerName = player.getGameProfile().getName();
            this.onlinePlayerRegistry.playerJoined(playerUuid, playerName);
            this.playerProfileService.updatePlayerName(playerUuid, playerName).exceptionally(throwable -> {
                AuthPlugin.LOGGER.warn("Failed to update player profile for {}", playerUuid, throwable);
                return null;
            });
            this.authService.onPlayerDisconnect(playerUuid);
            this.authService.tryAutoLogin(playerUuid).whenComplete((loggedIn, throwable) -> server.execute(() -> {
                if (throwable != null) {
                    AuthPlugin.LOGGER.error("Auto-login failed for {}", playerUuid, throwable);
                    player.sendSystemMessage(AuthPromptMessages.autoLoginFailed());
                    return;
                }
                if (Boolean.TRUE.equals(loggedIn)) {
                    player.sendSystemMessage(AuthPromptMessages.autoLoginSuccessful());
                    player.sendSystemMessage(AuthPromptMessages.welcomeHome(playerName));
                } else {
                    player.sendSystemMessage(AuthPromptMessages.unauthorizedWelcome());
                    player.sendSystemMessage(AuthPromptMessages.unauthorizedLoginHint());
                    player.sendSystemMessage(AuthPromptMessages.unauthorizedTokenRequestHint());
                }
            }));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            UUID playerUuid = player.getUUID();
            this.onlinePlayerRegistry.playerLeft(playerUuid);
            this.frozenPositions.remove(playerUuid);
            this.authService.onPlayerDisconnect(playerUuid);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                UUID playerUuid = player.getUUID();
                if (this.authService.isLoggedIn(playerUuid)) {
                    this.frozenPositions.put(playerUuid, player.position());
                    continue;
                }

                Vec3 frozen = this.frozenPositions.computeIfAbsent(playerUuid, unused -> player.position());
                if (player.position().distanceToSqr(frozen) > 0.0001D) {
                    player.connection.teleport(frozen.x, frozen.y, frozen.z, player.getYRot(), player.getXRot());
                }
                player.setDeltaMovement(Vec3.ZERO);
            }
        });
    }

    private void registerRestrictionEvents() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> this.allowOrFail(player, PlayerActionType.INTERACT));
        UseItemCallback.EVENT.register((player, world, hand) -> this.allowOrFail(player, PlayerActionType.INTERACT));
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> this.allowOrFail(player, PlayerActionType.INTERACT));
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> this.allowOrFail(player, PlayerActionType.ATTACK));
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> this.allowOrFail(player, PlayerActionType.BUILD_OR_BREAK));

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return true;
            }
            if (this.restrictionService.isActionAllowed(serverPlayer.getUUID(), PlayerActionType.BUILD_OR_BREAK)) {
                return true;
            }
            serverPlayer.sendSystemMessage(AuthPromptMessages.restrictionDenied(PlayerActionType.BUILD_OR_BREAK));
            return false;
        });
    }

    private InteractionResult allowOrFail(Player player, PlayerActionType actionType) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (this.restrictionService.isActionAllowed(serverPlayer.getUUID(), actionType)) {
            return InteractionResult.PASS;
        }
        serverPlayer.sendSystemMessage(AuthPromptMessages.restrictionDenied(actionType));
        return InteractionResult.FAIL;
    }

    private void onLoginResult(ServerPlayer player, LoginResult result, Throwable throwable) {
        if (throwable != null) {
            AuthPlugin.LOGGER.error("Login processing failed for {}", player.getUUID(), throwable);
            player.sendSystemMessage(AuthPromptMessages.internalLoginError());
            return;
        }

        player.sendSystemMessage(AuthPromptMessages.loginResult(result));
    }
}
