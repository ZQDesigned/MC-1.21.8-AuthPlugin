package city.zqdesigned.mc.authplugin.token;

public record TokenBindResult(TokenBindOutcome outcome, boolean newlyBound) {
    public static TokenBindResult success(boolean newlyBound) {
        return new TokenBindResult(TokenBindOutcome.SUCCESS, newlyBound);
    }

    public static TokenBindResult of(TokenBindOutcome outcome) {
        return new TokenBindResult(outcome, false);
    }
}
