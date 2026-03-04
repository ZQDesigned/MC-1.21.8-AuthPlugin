package city.zqdesigned.mc.authplugin.token;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class TokenGenerator {
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String ALL = UPPER + LOWER + DIGITS;
    private static final int TOKEN_LENGTH = 16;
    private final SecureRandom secureRandom = new SecureRandom();

    public String generateOne() {
        char[] chars = new char[TOKEN_LENGTH];
        chars[0] = this.randomChar(UPPER);
        chars[1] = this.randomChar(LOWER);
        chars[2] = this.randomChar(DIGITS);
        for (int i = 3; i < chars.length; i++) {
            chars[i] = this.randomChar(ALL);
        }
        this.shuffle(chars);
        return new String(chars);
    }

    public List<String> generateBatch(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }
        List<String> tokens = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tokens.add(this.generateOne());
        }
        return tokens;
    }

    private char randomChar(String pool) {
        int index = this.secureRandom.nextInt(pool.length());
        return pool.charAt(index);
    }

    private void shuffle(char[] chars) {
        for (int i = chars.length - 1; i > 0; i--) {
            int index = this.secureRandom.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[index];
            chars[index] = tmp;
        }
    }
}
