package city.zqdesigned.mc.authplugin.fabric;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import city.zqdesigned.mc.authplugin.auth.AuthService;
import city.zqdesigned.mc.authplugin.auth.LoginResult;
import city.zqdesigned.mc.authplugin.auth.LoginResultType;
import city.zqdesigned.mc.authplugin.message.AuthPromptMessages;
import city.zqdesigned.mc.authplugin.profile.PlayerProfileService;
import city.zqdesigned.mc.authplugin.restriction.AuthRestrictionService;
import city.zqdesigned.mc.authplugin.restriction.PlayerActionType;
import city.zqdesigned.mc.authplugin.server.ServerControlService;
import city.zqdesigned.mc.authplugin.web.OnlinePlayerRegistry;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class FabricAuthRuntime {
    private static final int FREEZE_CORRECTION_INTERVAL_TICKS = 5;
    private static final double FREEZE_TELEPORT_DISTANCE_SQR = 0.09D;
    private final AuthService authService = AuthPlugin.bootstrap().authService();
    private final PlayerProfileService playerProfileService = AuthPlugin.bootstrap().playerProfileService();
    private final AuthRestrictionService restrictionService = AuthPlugin.bootstrap().restrictionService();
    private final OnlinePlayerRegistry onlinePlayerRegistry = AuthPlugin.bootstrap().onlinePlayerRegistry();
    private final ServerControlService serverControlService = AuthPlugin.bootstrap().serverControlService();
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
        ServerLifecycleEvents.SERVER_STARTED.register(this.serverControlService::attachServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            this.serverControlService.detachServer(server);
            AuthPlugin.stop();
        });

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
            this.restrictionService.clearDenialCooldown(playerUuid);
            this.authService.onPlayerDisconnect(playerUuid);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                UUID playerUuid = player.getUUID();
                if (this.authService.isLoggedIn(playerUuid)) {
                    this.frozenPositions.remove(playerUuid);
                    this.restrictionService.clearDenialCooldown(playerUuid);
                    continue;
                }

                Vec3 frozen = this.frozenPositions.computeIfAbsent(playerUuid, unused -> player.position());
                if (player.getDeltaMovement().lengthSqr() > 0.000001D) {
                    player.setDeltaMovement(Vec3.ZERO);
                }

                if (player.tickCount % FREEZE_CORRECTION_INTERVAL_TICKS != 0) {
                    continue;
                }

                if (player.position().distanceToSqr(frozen) > FREEZE_TELEPORT_DISTANCE_SQR) {
                    player.connection.teleport(frozen.x, frozen.y, frozen.z, player.getYRot(), player.getXRot());
                }
            }
        });
    }

    private void registerRestrictionEvents() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> this.allowOrFail(player, PlayerActionType.INTERACT));
        UseItemCallback.EVENT.register((player, world, hand) -> this.allowOrFailItemUse(player, hand));
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
            this.sendRestrictionPromptIfNeeded(serverPlayer, PlayerActionType.BUILD_OR_BREAK);
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
        this.sendRestrictionPromptIfNeeded(serverPlayer, actionType);
        return InteractionResult.FAIL;
    }

    private InteractionResultHolder<ItemStack> allowOrFailItemUse(Player player, InteractionHand hand) {
        ItemStack heldItem = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(heldItem);
        }
        if (this.restrictionService.isActionAllowed(serverPlayer.getUUID(), PlayerActionType.INTERACT)) {
            return InteractionResultHolder.pass(heldItem);
        }
        this.sendRestrictionPromptIfNeeded(serverPlayer, PlayerActionType.INTERACT);
        return InteractionResultHolder.fail(heldItem);
    }

    private void sendRestrictionPromptIfNeeded(ServerPlayer player, PlayerActionType actionType) {
        if (this.restrictionService.shouldSendDenialMessage(player.getUUID(), actionType)) {
            player.sendSystemMessage(AuthPromptMessages.restrictionDenied(actionType));
        }
    }

    private void onLoginResult(ServerPlayer player, LoginResult result, Throwable throwable) {
        if (throwable != null) {
            AuthPlugin.LOGGER.error("Login processing failed for {}", player.getUUID(), throwable);
            player.sendSystemMessage(AuthPromptMessages.internalLoginError());
            return;
        }

        if (result.type() == LoginResultType.SUCCESS_NEW_BIND || result.type() == LoginResultType.SUCCESS_ALREADY_BOUND) {
            this.frozenPositions.remove(player.getUUID());
            this.restrictionService.clearDenialCooldown(player.getUUID());
        }

        player.sendSystemMessage(AuthPromptMessages.loginResult(result));
    }
}
