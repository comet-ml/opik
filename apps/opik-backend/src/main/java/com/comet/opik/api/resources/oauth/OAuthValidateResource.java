package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.McpOAuthTokens;
import com.comet.opik.domain.mcpoauth.ValidatedToken;
import jakarta.inject.Inject;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Strings;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.BEARER_PREFIX;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.TOKEN_TYPE_BEARER;

@Path("/opik/auth-oauth")
@Timed
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OAuthValidateResource {

    private final @NonNull McpOAuthService mcpOAuthService;

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response validate(@HeaderParam(HttpHeaders.AUTHORIZATION) String authHeader) {
        if (!Strings.CI.startsWith(authHeader, BEARER_PREFIX)) {
            throw new NotAuthorizedException(TOKEN_TYPE_BEARER);
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (!McpOAuthTokens.isAccessToken(token)) {
            throw new NotAuthorizedException(TOKEN_TYPE_BEARER);
        }
        ValidatedToken validated = mcpOAuthService.validateAccessToken(token)
                .orElseThrow(() -> new NotAuthorizedException(TOKEN_TYPE_BEARER));
        return Response.ok(validated).build();
    }
}
