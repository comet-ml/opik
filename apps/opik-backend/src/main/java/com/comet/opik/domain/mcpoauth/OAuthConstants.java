package com.comet.opik.domain.mcpoauth;

import lombok.experimental.UtilityClass;

import java.util.List;

@UtilityClass
public class OAuthConstants {

    // RFC 6749 §4.1.3, §6 — grant types
    public static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
    public static final String GRANT_REFRESH_TOKEN = "refresh_token";
    public static final List<String> DEFAULT_GRANT_TYPES = List.of(GRANT_AUTHORIZATION_CODE, GRANT_REFRESH_TOKEN);

    // RFC 6749 §3.1.1 — response types
    public static final String RESPONSE_TYPE_CODE = "code";
    public static final List<String> DEFAULT_RESPONSE_TYPES = List.of(RESPONSE_TYPE_CODE);

    // RFC 7636 — PKCE
    public static final String CODE_CHALLENGE_METHOD_S256 = "S256";

    // RFC 6750 — bearer token type
    public static final String TOKEN_TYPE_BEARER = "Bearer";
    public static final String BEARER_PREFIX = TOKEN_TYPE_BEARER + " ";

    // Header used by opik-backend → comet-backend /opik/auth-by-username to carry the OAuth-resolved userName.
    public static final String OAUTH_USERNAME_HEADER = "X-Opik-OAuth-Username";

    // Cookie carrying the consent CSRF token between GET /authorize/context and POST /authorize.
    public static final String CSRF_COOKIE = "mcp_oauth_csrf";

    // RFC 8414 — token endpoint auth methods
    public static final String AUTH_METHOD_NONE = "none";

    // RFC 6749 §4.1.2.1, §5.2 + RFC 8707 — error codes
    public static final String ERROR_INVALID_REQUEST = "invalid_request";
    public static final String ERROR_INVALID_CLIENT = "invalid_client";
    public static final String ERROR_INVALID_GRANT = "invalid_grant";
    public static final String ERROR_INVALID_TARGET = "invalid_target";
    public static final String ERROR_UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
    public static final String ERROR_UNSUPPORTED_RESPONSE_TYPE = "unsupported_response_type";

    public static final String RATE_LIMIT_BUCKET_PREFIX = "mcp_oauth_register";
    public static final String RATE_LIMIT_BUCKET = RATE_LIMIT_BUCKET_PREFIX + ":%s";
}
