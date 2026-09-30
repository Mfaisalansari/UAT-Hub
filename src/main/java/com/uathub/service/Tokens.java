package com.uathub.service;

import java.security.SecureRandom;
import java.util.Base64;

public final class Tokens {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {}

    /** 32 URL-safe characters, ~192 bits of randomness. */
    public static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
