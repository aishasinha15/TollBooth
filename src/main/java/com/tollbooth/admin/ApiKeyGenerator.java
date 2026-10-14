package com.tollbooth.admin;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Makes new API keys like "tb_live_Q3x9...": the prefix says what it is, the rest is
 * 32 random bytes (256 bits) from SecureRandom, so keys can't be guessed.
 * Only the key's SHA-256 hash is stored; the key itself is shown once, when it is created.
 */
public final class ApiKeyGenerator {

    static final String PREFIX = "tb_live_";
    private static final int RANDOM_BYTES = 32;
    private static final int VISIBLE_PREFIX_LENGTH = 12;

    /** SecureRandom (not Random): its numbers are unpredictable, which a secret needs. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyGenerator() {
    }

    public static String newKey() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        // URL-safe Base64 without "=" padding: letters, digits, - and _ only
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The part that is safe to show in lists, e.g. "tb_live_Q3x9". */
    public static String prefixOf(String key) {
        return key.substring(0, Math.min(VISIBLE_PREFIX_LENGTH, key.length()));
    }
}
