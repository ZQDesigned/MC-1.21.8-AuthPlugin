package city.zqdesigned.mc.authplugin.auth;

public record LoginResult(LoginResultType type, String message) {
    public boolean success() {
        return this.type == LoginResultType.SUCCESS_NEW_BIND || this.type == LoginResultType.SUCCESS_ALREADY_BOUND;
    }
}
