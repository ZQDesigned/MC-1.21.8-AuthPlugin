package city.zqdesigned.mc.authplugin.message;

import city.zqdesigned.mc.authplugin.auth.LoginResult;
import city.zqdesigned.mc.authplugin.auth.LoginResultType;
import city.zqdesigned.mc.authplugin.restriction.PlayerActionType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

public final class AuthPromptMessages {
    private static final int COLOR_INFO = 0x66D9EF;
    private static final int COLOR_HINT = 0x8BE9FD;
    private static final int COLOR_SUCCESS = 0x50FA7B;
    private static final int COLOR_WARNING = 0xF1FA8C;
    private static final int COLOR_ERROR = 0xFF5555;

    private AuthPromptMessages() {
    }

    public static Component unauthorizedWelcome() {
        return colored("Welcome to AHFDU MC Server.", COLOR_INFO);
    }

    public static Component unauthorizedLoginHint() {
        return colored("Please log in before performing any actions.", COLOR_HINT);
    }

    public static Component unauthorizedTokenRequestHint() {
        return colored(
            "If you do not have a token, join group chat 1087396720 and contact the group owner to request one.",
            COLOR_WARNING
        );
    }

    public static Component autoLoginSuccessful() {
        return colored("Auto-login successful.", COLOR_SUCCESS);
    }

    public static Component welcomeHome(String playerName) {
        return colored("Welcome home, " + playerName + ".", COLOR_SUCCESS);
    }

    public static Component autoLoginFailed() {
        return colored("Auto-login failed. Please use /login <token>.", COLOR_ERROR);
    }

    public static Component internalLoginError() {
        return colored("Login failed due to an internal error.", COLOR_ERROR);
    }

    public static Component loginResult(LoginResult result) {
        int color = switch (result.type()) {
            case SUCCESS_NEW_BIND, SUCCESS_ALREADY_BOUND -> COLOR_SUCCESS;
            case INVALID_TOKEN_FORMAT -> COLOR_WARNING;
            case TOKEN_NOT_FOUND, TOKEN_DISABLED, TOKEN_BOUND_TO_OTHER -> COLOR_ERROR;
        };
        return colored(result.message(), color);
    }

    public static Component restrictionDenied(PlayerActionType actionType) {
        String message = switch (actionType) {
            case MOVE -> "You must log in first. Use /login <token>.";
            case INTERACT -> "You must log in before interacting.";
            case ATTACK -> "You must log in before attacking.";
            case BUILD_OR_BREAK -> "You must log in before building or breaking blocks.";
            case COMMAND -> "Only /login <token> is available before authentication.";
        };
        return colored(message, COLOR_WARNING);
    }

    private static MutableComponent colored(String text, int rgb) {
        return Component.literal(text).withStyle(style -> style.withColor(TextColor.fromRgb(rgb)));
    }
}
