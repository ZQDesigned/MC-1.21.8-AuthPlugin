package city.zqdesigned.mc.authplugin.mixin;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import city.zqdesigned.mc.authplugin.message.AuthPromptMessages;
import city.zqdesigned.mc.authplugin.restriction.AuthRestrictionService;
import city.zqdesigned.mc.authplugin.restriction.PlayerActionType;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public final class CommandsMixin {
    @Inject(
        method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void authplugin$restrictCommands(ParseResults<CommandSourceStack> parseResults, String rawCommand, CallbackInfo ci) {
        CommandSourceStack source = parseResults.getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        AuthRestrictionService restrictionService = AuthPlugin.bootstrap().restrictionService();
        if (restrictionService.isCommandAllowed(player.getUUID(), rawCommand)) {
            return;
        }

        if (restrictionService.shouldSendDenialMessage(player.getUUID(), PlayerActionType.COMMAND)) {
            player.sendSystemMessage(AuthPromptMessages.restrictionDenied(PlayerActionType.COMMAND));
        }
        ci.cancel();
    }
}
