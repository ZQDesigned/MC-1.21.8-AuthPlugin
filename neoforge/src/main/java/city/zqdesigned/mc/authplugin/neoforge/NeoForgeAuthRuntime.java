package city.zqdesigned.mc.authplugin.neoforge;

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
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class NeoForgeAuthRuntime {
    private final AuthService authService = AuthPlugin.bootstrap().authService();
    private final PlayerProfileService playerProfileService = AuthPlugin.bootstrap().playerProfileService();
    private final AuthRestrictionService restrictionService = AuthPlugin.bootstrap().restrictionService();
    private final OnlinePlayerRegistry onlinePlayerRegistry = AuthPlugin.bootstrap().onlinePlayerRegistry();
    private final Map<UUID, Vec3> frozenPositions = new ConcurrentHashMap<>();

    public void register() {
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(this::onCommand);
        NeoForge.EVENT_BUS.addListener(this::onAttackEntity);
        NeoForge.EVENT_BUS.addListener(this::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(this::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(this::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(this::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(this::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(this::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("login")
                .then(Commands.argument("token", StringArgumentType.string())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String token = StringArgumentType.getString(context, "token");
                        this.authService.login(player.getUUID(), token).whenComplete((result, throwable) -> {
                            context.getSource().getServer().execute(() -> this.onLoginResult(player, result, throwable));
                        });
                        return 1;
                    })
                )
        );
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID playerUuid = player.getUUID();
        this.frozenPositions.put(playerUuid, player.position());
        String playerName = player.getGameProfile().getName();
        this.onlinePlayerRegistry.playerJoined(playerUuid, playerName);
        this.playerProfileService.updatePlayerName(playerUuid, playerName).exceptionally(throwable -> {
            AuthPlugin.LOGGER.warn("Failed to update player profile for {}", playerUuid, throwable);
            return null;
        });
        this.authService.onPlayerDisconnect(playerUuid);
        this.authService.tryAutoLogin(playerUuid).whenComplete((loggedIn, throwable) -> {
            if (player.getServer() == null) {
                return;
            }
            player.getServer().execute(() -> {
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
            });
        });
    }

    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID playerUuid = player.getUUID();
        this.onlinePlayerRegistry.playerLeft(playerUuid);
        this.frozenPositions.remove(playerUuid);
        this.authService.onPlayerDisconnect(playerUuid);
    }

    private void onCommand(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }

        String rawCommand = event.getParseResults().getReader().getString();
        if (this.restrictionService.isCommandAllowed(player.getUUID(), rawCommand)) {
            return;
        }

        event.setCanceled(true);
        player.sendSystemMessage(AuthPromptMessages.restrictionDenied(PlayerActionType.COMMAND));
    }

    private void onAttackEntity(AttackEntityEvent event) {
        if (this.denyIfRequired(event.getEntity(), PlayerActionType.ATTACK)) {
            event.setCanceled(true);
        }
    }

    private void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (this.denyIfRequired(event.getEntity(), PlayerActionType.INTERACT)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    private void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (this.denyIfRequired(event.getEntity(), PlayerActionType.INTERACT)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    private void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (this.denyIfRequired(event.getEntity(), PlayerActionType.INTERACT)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    private void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (this.denyIfRequired(event.getEntity(), PlayerActionType.BUILD_OR_BREAK)) {
            event.setCanceled(true);
        }
    }

    private void onBlockBreak(BlockEvent.BreakEvent event) {
        if (this.denyIfRequired(event.getPlayer(), PlayerActionType.BUILD_OR_BREAK)) {
            event.setCanceled(true);
        }
    }

    private void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (this.denyIfRequired(player, PlayerActionType.BUILD_OR_BREAK)) {
            event.setCanceled(true);
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            UUID playerUuid = player.getUUID();
            if (this.authService.isLoggedIn(playerUuid)) {
                this.frozenPositions.put(playerUuid, player.position());
                continue;
            }

            Vec3 frozen = this.frozenPositions.computeIfAbsent(playerUuid, ignored -> player.position());
            if (player.position().distanceToSqr(frozen) > 0.0001D) {
                player.connection.teleport(frozen.x, frozen.y, frozen.z, player.getYRot(), player.getXRot());
            }
            player.setDeltaMovement(Vec3.ZERO);
        }
    }

    private boolean denyIfRequired(Player player, PlayerActionType actionType) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        if (this.restrictionService.isActionAllowed(serverPlayer.getUUID(), actionType)) {
            return false;
        }
        serverPlayer.sendSystemMessage(AuthPromptMessages.restrictionDenied(actionType));
        return true;
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
