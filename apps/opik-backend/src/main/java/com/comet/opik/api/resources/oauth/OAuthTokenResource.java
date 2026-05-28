package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.McpOAuthTokens;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.domain.mcpoauth.TokenResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_CLIENT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_REQUEST;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_UNSUPPORTED_GRANT_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_AUTHORIZATION_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_REFRESH_TOKEN;

@Path("/oauth")
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OAuthTokenResource {

    private final @NonNull OAuthClientService clientService;
    private final @NonNull McpOAuthService mcpOAuthService;

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response token(
            @FormParam("grant_type") String grantType,
            @FormParam("code") String code,
            @FormParam("redirect_uri") String redirectUri,
            @FormParam("client_id") String clientId,
            @FormParam("code_verifier") String codeVerifier,
            @FormParam("refresh_token") String refreshToken) {

        if (GRANT_AUTHORIZATION_CODE.equals(grantType)) {
            if (StringUtils.isBlank(code) || StringUtils.isBlank(redirectUri) || StringUtils.isBlank(clientId)
                    || StringUtils.isBlank(codeVerifier)) {
                return error(ERROR_INVALID_REQUEST);
            }
            if (clientService.resolve(clientId).isEmpty()) {
                return error(ERROR_INVALID_CLIENT);
            }
            try {
                return okToken(mcpOAuthService.exchangeCode(code, codeVerifier, redirectUri, clientId));
            } catch (BadRequestException e) {
                return error(e.getMessage());
            }
        }

        if (GRANT_REFRESH_TOKEN.equals(grantType)) {
            if (StringUtils.isBlank(refreshToken) || StringUtils.isBlank(clientId)) {
                return error(ERROR_INVALID_REQUEST);
            }
            if (clientService.resolve(clientId).isEmpty()) {
                return error(ERROR_INVALID_CLIENT);
            }
            try {
                return okToken(mcpOAuthService.refresh(refreshToken, clientId));
            } catch (BadRequestException e) {
                return error(e.getMessage());
            }
        }

        return StringUtils.isBlank(grantType) ? error(ERROR_INVALID_REQUEST) : error(ERROR_UNSUPPORTED_GRANT_TYPE);
    }

    @POST
    @Path("/revoke")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response revoke(
            @FormParam("token") String token,
            @FormParam("token_type_hint") String tokenTypeHint,
            @FormParam("client_id") String clientId) {

        // RFC 7009 §2.2: the AS returns 200 whether the token was revoked, never existed, or was invalid.
        if (!StringUtils.isBlank(token)) {
            try {
                mcpOAuthService.revoke(token);
            } catch (Exception e) {
                log.warn("MCP OAuth revoke failed [token={}, client_id={}]: {}",
                        maskToken(token), clientId, e.toString(), e);
            }
        }
        return Response.ok().build();
    }

    private static String maskToken(String token) {
        if (StringUtils.isEmpty(token)) {
            return "";
        }
        //expected Opik token size
        if (token.length() > McpOAuthTokens.RANDOM_BYTES) {
            return token.substring(0, 12) + "..." + token.substring(token.length() - 4);
        } else {
            //return full string as confirmed not to be expected token shape
            return token;
        }
    }

    private static Response okToken(TokenResponse body) {
        return Response.ok(body)
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .build();
    }

    private static Response error(String code) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .entity(new OAuthError(code, null))
                .build();
    }

}
