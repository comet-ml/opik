package com.comet.opik.domain.mcpoauth;

import lombok.experimental.UtilityClass;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.BEARER_PREFIX;

@UtilityClass
public class McpOAuthTokenUtils {

    public static final String ACCESS_PREFIX = "opik_mcp_at_";
    public static final String REFRESH_PREFIX = "opik_mcp_rt_";
    public static final int RANDOM_BYTES = 32;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom SECURE_RANDOM = getSecureRandom();

    public static String generateAccessToken() {
        return ACCESS_PREFIX + randomToken();
    }

    public static String generateRefreshToken() {
        return REFRESH_PREFIX + randomToken();
    }

    public static String generateCode() {
        return randomToken();
    }

    public static String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
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
        return DigestUtils.sha256Hex(token);
    }

    // Never emit token fragments: a SHA-256 prefix is non-reversible yet stable enough to
    // correlate the same token across log lines.
    public static String maskToken(String token) {
        if (StringUtils.isEmpty(token)) {
            return "";
        }
        return "sha256:" + hash(token).substring(0, 12);
    }

    private static SecureRandom getSecureRandom() {
        try {
            return SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Strong SecureRandom not available", e);
        }
    }

}
