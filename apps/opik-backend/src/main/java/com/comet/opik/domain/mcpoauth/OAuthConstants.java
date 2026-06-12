package com.comet.opik.domain.mcpoauth;

import lombok.experimental.UtilityClass;

import java.util.List;

@UtilityClass
public class OAuthConstants {

    // RFC 6749 §4.1.3, §6 — grant types
    public static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
    public static final String GRANT_REFRESH_TOKEN = "refresh_token";
    public static final List<String> DEFAULT_GRANT_TYPES = List.of(GRANT_AUTHORIZATION_CODE, GRANT_REFRESH_TOKEN);

    // RFC 6749 §4.1.3, §6 + RFC 7009 §2.1 — token/revocation request parameter names
    public static final String PARAM_GRANT_TYPE = "grant_type";
    public static final String PARAM_CODE = "code";
    public static final String PARAM_REDIRECT_URI = "redirect_uri";
    public static final String PARAM_CLIENT_ID = "client_id";
    public static final String PARAM_CODE_VERIFIER = "code_verifier";
    public static final String PARAM_REFRESH_TOKEN = "refresh_token";
    public static final String PARAM_TOKEN = "token";

    // RFC 6749 §3.1.1 — response types
    public static final String RESPONSE_TYPE_CODE = "code";
    public static final List<String> DEFAULT_RESPONSE_TYPES = List.of(RESPONSE_TYPE_CODE);

    // RFC 6749 §4.1.1, §4.1.2 + RFC 7636 + RFC 8707 — authorization request/response query parameters
    public static final String PARAM_RESPONSE_TYPE = "response_type";
    public static final String PARAM_CODE_CHALLENGE = "code_challenge";
    public static final String PARAM_CODE_CHALLENGE_METHOD = "code_challenge_method";
    public static final String PARAM_RESOURCE = "resource";
    public static final String PARAM_STATE = "state";
    public static final String PARAM_ERROR = "error";

    // RFC 7636 — PKCE
    public static final String CODE_CHALLENGE_METHOD_S256 = "S256";

    // RFC 6750 — bearer token type
    public static final String TOKEN_TYPE_BEARER = "Bearer";
    public static final String BEARER_PREFIX = TOKEN_TYPE_BEARER + " ";

    // RFC 6749 §5.1 — token-endpoint responses must not be cached
    public static final String HEADER_PRAGMA = "Pragma";
    public static final String CACHE_CONTROL_NO_STORE = "no-store";
    public static final String PRAGMA_NO_CACHE = "no-cache";

    // Header used by opik-backend → comet-backend /opik/auth-by-username to carry the OAuth-resolved userName.
    public static final String OAUTH_USERNAME_HEADER = "X-Opik-OAuth-Username";

    // Cookie carrying the consent CSRF token between GET /authorize/context and POST /authorize.
    public static final String CSRF_COOKIE = "mcp_oauth_csrf";

    // RFC 8414 — token endpoint auth methods
    public static final String AUTH_METHOD_NONE = "none";

    // RFC 8414 §3 — authorization server metadata discovery path and the advertised endpoint paths
    public static final String AUTHORIZATION_SERVER_METADATA_PATH = "/.well-known/oauth-authorization-server";
    public static final String AUTHORIZE_PATH = "/oauth/authorize";
    public static final String TOKEN_PATH = "/oauth/token";
    public static final String REVOKE_PATH = "/oauth/revoke";
    public static final String REGISTER_PATH = "/oauth/register";

    // RFC 6749 §4.1.2.1, §5.2 + RFC 8707 — error codes
    public static final String ERROR_INVALID_REQUEST = "invalid_request";
    public static final String ERROR_INVALID_CLIENT = "invalid_client";
    public static final String ERROR_INVALID_GRANT = "invalid_grant";
    public static final String ERROR_INVALID_TARGET = "invalid_target";
    public static final String ERROR_UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
    public static final String ERROR_UNSUPPORTED_RESPONSE_TYPE = "unsupported_response_type";

    // RFC 7591 §3.2.1 client-configuration endpoint path; the registration response Location points here.
    public static final String CLIENT_CONFIG_PATH_PREFIX = "/admin/mcp-oauth-clients/";
}
