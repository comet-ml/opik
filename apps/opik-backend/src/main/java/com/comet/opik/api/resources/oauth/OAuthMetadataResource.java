package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.List;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.AUTH_METHOD_NONE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.CODE_CHALLENGE_METHOD_S256;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_AUTHORIZATION_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_REFRESH_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.RESPONSE_TYPE_CODE;

@Path("/.well-known/oauth-authorization-server")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OAuthMetadataResource {

    private final @NonNull OpikConfiguration opikConfig;

    @GET
    public Response metadata() {
        McpOAuthConfig config = opikConfig.getMcpOAuth();
        String issuer = config.getIssuer();
        return Response.ok(AuthorizationServerMetadata.builder()
                .issuer(issuer)
                .authorizationEndpoint(issuer + "/oauth/authorize")
                .tokenEndpoint(issuer + "/oauth/token")
                .revocationEndpoint(issuer + "/oauth/revoke")
                .registrationEndpoint(issuer + "/oauth/register")
                .responseTypesSupported(List.of(RESPONSE_TYPE_CODE))
                .grantTypesSupported(List.of(GRANT_AUTHORIZATION_CODE, GRANT_REFRESH_TOKEN))
                .codeChallengeMethodsSupported(List.of(CODE_CHALLENGE_METHOD_S256))
                .tokenEndpointAuthMethodsSupported(List.of(AUTH_METHOD_NONE))
                .build())
                .build();
    }
}
