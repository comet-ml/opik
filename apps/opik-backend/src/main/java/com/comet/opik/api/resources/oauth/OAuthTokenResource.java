package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils;
import com.comet.opik.domain.mcpoauth.OAuthTokenService;
import com.comet.opik.domain.mcpoauth.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.CACHE_CONTROL_NO_STORE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.HEADER_PRAGMA;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE_VERIFIER;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_GRANT_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REDIRECT_URI;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REFRESH_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PRAGMA_NO_CACHE;

/**
 * OAuth token and revocation endpoints (RFC 6749 / RFC 7009).
 * <p>
 * The resource only binds the form parameters and assembles the HTTP response. Grant-type dispatch,
 * parameter validation, client resolution, and error translation live in {@link OAuthTokenService}, which
 * raises an {@code OAuthException} on failure; {@code OAuthExceptionMapper} renders that into the RFC 6749
 * §5.2 error envelope.
 */
@Path("/oauth")
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "MCP OAuth", description = "MCP OAuth 2.1 Authorization Server resources")
public class OAuthTokenResource {

    private final @NonNull OAuthTokenService tokenService;

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "token", summary = "OAuth Token Endpoint", description = "OAuth 2.1 token endpoint (RFC 6749 §4.1.3, §6). Exchanges an authorization code with PKCE or a refresh token for an access/refresh token pair", responses = {
            @ApiResponse(responseCode = "200", description = "Token response", content = @Content(schema = @Schema(implementation = TokenResponse.class))),
            @ApiResponse(responseCode = "400", description = "OAuth error (RFC 6749 §5.2)", content = @Content(schema = @Schema(implementation = OAuthError.class)))})
    public Response token(
            @FormParam(PARAM_GRANT_TYPE) String grantType,
            @FormParam(PARAM_CODE) String code,
            @FormParam(PARAM_REDIRECT_URI) String redirectUri,
            @FormParam(PARAM_CLIENT_ID) String clientId,
            @FormParam(PARAM_CODE_VERIFIER) String codeVerifier,
            @FormParam(PARAM_REFRESH_TOKEN) String refreshToken) {

        log.info("MCP OAuth token request '{}', '{}'", grantType, clientId);

        Response response = okToken(
                tokenService.issueToken(grantType, code, redirectUri, clientId, codeVerifier, refreshToken));

        log.info("MCP OAuth token issued '{}', '{}'", grantType, clientId);
        return response;
    }

    @POST
    @Path("/revoke")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(operationId = "revoke", summary = "OAuth Token Revocation Endpoint", description = "OAuth 2.0 token revocation endpoint (RFC 7009). Always returns 200, whether the token was revoked, never existed, or was invalid", responses = {
            @ApiResponse(responseCode = "200", description = "Revocation acknowledged")})
    public Response revoke(
            @FormParam(PARAM_TOKEN) String token,
            @FormParam(PARAM_CLIENT_ID) String clientId) {

        log.info("MCP OAuth revoke request '{}', '{}'", McpOAuthTokenUtils.maskToken(token), clientId);

        // RFC 7009 §2.2: the AS returns 200 whether the token was revoked, never existed, or was invalid.
        if (!StringUtils.isBlank(token)) {
            tokenService.revoke(token);
        }
        return Response.ok().build();
    }

    private Response okToken(TokenResponse body) {
        return noStore(Response.ok(body)).build();
    }

    /**
     * Applies the RFC 6749 §5.1 no-caching headers required on token-endpoint responses, so credentials
     * are never stored by intermediaries.
     */
    private Response.ResponseBuilder noStore(Response.ResponseBuilder builder) {
        return builder
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                .header(HEADER_PRAGMA, PRAGMA_NO_CACHE);
    }

}
