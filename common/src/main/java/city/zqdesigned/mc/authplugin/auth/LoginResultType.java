package city.zqdesigned.mc.authplugin.auth;

public enum LoginResultType {
    SUCCESS_NEW_BIND,
    SUCCESS_ALREADY_BOUND,
    TOKEN_NOT_FOUND,
    TOKEN_DISABLED,
    TOKEN_BOUND_TO_OTHER,
    INVALID_TOKEN_FORMAT
}
