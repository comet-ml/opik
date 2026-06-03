package com.comet.opik.domain.mcpoauth;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.Strings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.BEARER_PREFIX;

@UtilityClass
public class McpOAuthTokens {

    public static final String ACCESS_PREFIX = "opik_at_";
    public static final String REFRESH_PREFIX = "opik_rt_";
    public static final int RANDOM_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public static String generateAccessToken() {
        return ACCESS_PREFIX + randomSuffix();
    }

    public static String generateRefreshToken() {
        return REFRESH_PREFIX + randomSuffix();
    }

    public static String generateCode() {
        return randomSuffix();
    }

    // Token prefixes are fixed lowercase literals minted by us — match case-sensitively.
    // Only the Bearer scheme name is case-insensitive (RFC 6750 §2.1).
    public static boolean isAccessToken(String token) {
        return token != null && token.startsWith(ACCESS_PREFIX);
    }

    public static boolean isRefreshToken(String token) {
        return token != null && token.startsWith(REFRESH_PREFIX);
    }

    public static boolean isOAuthToken(String token) {
        return isAccessToken(token) || isRefreshToken(token);
    }

    public static boolean isMcpOAuthToken(String authHeader) {
        if (!Strings.CI.startsWith(authHeader, BEARER_PREFIX)) {
            return false;
        }
        return isAccessToken(authHeader.substring(BEARER_PREFIX.length()).trim());
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String randomSuffix() {
        byte[] bytes = new byte[RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
