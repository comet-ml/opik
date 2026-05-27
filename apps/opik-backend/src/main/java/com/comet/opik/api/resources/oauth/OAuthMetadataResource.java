package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.infrastructure.McpOAuthConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;

@Path("/.well-known/oauth-authorization-server")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OAuthMetadataResource {

    private final @NonNull @Config("mcpOAuth") McpOAuthConfig config;

    @GET
    public Response metadata() {
        if (!config.isEnabled()) {
            throw new NotFoundException();
        }
        String issuer = config.getIssuer();
        return Response.ok(AuthorizationServerMetadata.builder()
                .issuer(issuer)
                .authorizationEndpoint(issuer + "/oauth/authorize")
                .tokenEndpoint(issuer + "/oauth/token")
                .revocationEndpoint(issuer + "/oauth/revoke")
                .registrationEndpoint(issuer + "/oauth/register")
                .responseTypesSupported(List.of("code"))
                .grantTypesSupported(List.of("authorization_code", "refresh_token"))
                .codeChallengeMethodsSupported(List.of("S256"))
                .tokenEndpointAuthMethodsSupported(List.of("none"))
                .build())
                .build();
    }
}
