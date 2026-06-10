package com.comet.opik.domain.mcpoauth;

import lombok.experimental.UtilityClass;

@UtilityClass
public class OAuthConstants {

    // RFC 6749 §4.1.3, §6 — grant types
    public static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
    public static final String GRANT_REFRESH_TOKEN = "refresh_token";

    // RFC 6749 §3.1.1 — response types
    public static final String RESPONSE_TYPE_CODE = "code";

    // RFC 6749 §4.1.1, §4.1.2 + RFC 7636 + RFC 8707 — authorization request/response query parameters
    public static final String PARAM_CLIENT_ID = "client_id";
    public static final String PARAM_REDIRECT_URI = "redirect_uri";
    public static final String PARAM_RESPONSE_TYPE = "response_type";
    public static final String PARAM_CODE_CHALLENGE = "code_challenge";
    public static final String PARAM_CODE_CHALLENGE_METHOD = "code_challenge_method";
    public static final String PARAM_RESOURCE = "resource";
    public static final String PARAM_STATE = "state";
    public static final String PARAM_CODE = "code";
    public static final String PARAM_ERROR = "error";

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
}
