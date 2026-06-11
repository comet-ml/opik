package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.domain.mcpoauth.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
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

import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_CLIENT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_GRANT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_REQUEST;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_UNSUPPORTED_GRANT_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_AUTHORIZATION_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_REFRESH_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE_VERIFIER;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_GRANT_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REDIRECT_URI;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REFRESH_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_TOKEN;

/**
 * OAuth token and revocation endpoints (RFC 6749 / RFC 7009).
 * <p>
 * Parameter validation is performed in-method rather than via constraint-validation annotations
 * for three reasons:
 * <ul>
 *   <li><b>Conditional requirements.</b> Annotations apply unconditionally and would
 *       reject otherwise-valid requests.</li>
 *   <li><b>RFC-specific error bodies.</b> Failures must return the OAuth error envelope
 *       (e.g. {@code {"error":"invalid_request"}}, {@code unsupported_grant_type}.
 *       A constraint violation would instead yield the framework's default validation
 *       response, breaking the contract.</li>
 *   <li><b>RFC 7009 §2.2.</b> {@code /revoke} must return {@code 200} even for a blank or invalid
 *       token, so a {@code @NotBlank} constraint on {@code token} would be incorrect.</li>
 * </ul>
 */
@Path("/oauth")
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "MCP OAuth", description = "MCP OAuth 2.1 Authorization Server resources")
public class OAuthTokenResource {

    private final @NonNull OAuthClientService clientService;
    private final @NonNull McpOAuthService mcpOAuthService;

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

        log.info("MCP OAuth token request [grant_type={}, client_id={}]", grantType, clientId);

        if (GRANT_AUTHORIZATION_CODE.equals(grantType)) {
            if (StringUtils.isBlank(code) || StringUtils.isBlank(redirectUri) || StringUtils.isBlank(clientId)
                    || StringUtils.isBlank(codeVerifier)) {
                log.warn("MCP OAuth authorization_code request rejected: missing required parameters [client_id={}]",
                        clientId);
                return error(ERROR_INVALID_REQUEST);
            }
            if (clientService.resolve(clientId).isEmpty()) {
                log.warn("MCP OAuth authorization_code request rejected: unknown client [client_id={}]", clientId);
                return error(ERROR_INVALID_CLIENT);
            }
            try {
                Response response = okToken(mcpOAuthService.exchangeCode(code, codeVerifier, redirectUri, clientId));
                log.info("MCP OAuth authorization_code exchanged [client_id={}]", clientId);
                return response;
            } catch (BadRequestException e) {
                log.warn("MCP OAuth authorization_code exchange failed [client_id={}]", clientId, e);
                return error(ERROR_INVALID_GRANT);
            }
        }

        if (GRANT_REFRESH_TOKEN.equals(grantType)) {
            if (StringUtils.isBlank(refreshToken) || StringUtils.isBlank(clientId)) {
                log.warn("MCP OAuth refresh_token request rejected: missing required parameters [client_id={}]",
                        clientId);
                return error(ERROR_INVALID_REQUEST);
            }
            if (clientService.resolve(clientId).isEmpty()) {
                log.warn("MCP OAuth refresh_token request rejected: unknown client [client_id={}]", clientId);
                return error(ERROR_INVALID_CLIENT);
            }
            try {
                Response response = okToken(mcpOAuthService.refresh(refreshToken, clientId));
                log.info("MCP OAuth refresh_token rotated [client_id={}]", clientId);
                return response;
            } catch (BadRequestException e) {
                log.warn("MCP OAuth refresh failed [client_id={}]", clientId, e);
                return error(ERROR_INVALID_GRANT);
            }
        }

        if (StringUtils.isBlank(grantType)) {
            log.warn("MCP OAuth token request rejected: missing grant_type [client_id={}]", clientId);
            return error(ERROR_INVALID_REQUEST);
        }
        log.warn("MCP OAuth token request rejected: unsupported grant_type [grant_type={}, client_id={}]",
                grantType, clientId);
        return error(ERROR_UNSUPPORTED_GRANT_TYPE);
    }

    @POST
    @Path("/revoke")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(operationId = "revoke", summary = "OAuth Token Revocation Endpoint", description = "OAuth 2.0 token revocation endpoint (RFC 7009). Always returns 200, whether the token was revoked, never existed, or was invalid", responses = {
            @ApiResponse(responseCode = "200", description = "Revocation acknowledged")})
    public Response revoke(
            @FormParam(PARAM_TOKEN) String token,
            @FormParam(PARAM_CLIENT_ID) String clientId) {

        log.info("MCP OAuth revoke request [token={}, client_id={}]", McpOAuthTokenUtils.maskToken(token), clientId);

        // RFC 7009 §2.2: the AS returns 200 whether the token was revoked, never existed, or was invalid.
        if (!StringUtils.isBlank(token)) {
            try {
                mcpOAuthService.revoke(token);
            } catch (Exception e) {
                log.warn("MCP OAuth revoke failed [token={}, client_id={}]",
                        McpOAuthTokenUtils.maskToken(token), clientId, e);
            }
        }
        return Response.ok().build();
    }

    private static Response okToken(TokenResponse body) {
        return noStore(Response.ok(body)).build();
    }

    private static Response error(String code) {
        return noStore(Response.status(Response.Status.BAD_REQUEST))
                .type(MediaType.APPLICATION_JSON)
                .entity(OAuthError.builder().error(code).build())
                .build();
    }

    /**
     * Applies the RFC 6749 §5.1 no-caching headers required on token-endpoint responses, so credentials
     * are never stored by intermediaries.
     */
    private static Response.ResponseBuilder noStore(Response.ResponseBuilder builder) {
        return builder
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Pragma", "no-cache");
    }

}
