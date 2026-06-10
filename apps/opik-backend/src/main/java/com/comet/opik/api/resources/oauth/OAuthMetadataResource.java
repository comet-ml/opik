package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "MCP OAuth", description = "MCP OAuth 2.1 Authorization Server resources")
public class OAuthMetadataResource {

    private final @NonNull OpikConfiguration opikConfig;

    @GET
    @Operation(operationId = "getOAuthAuthorizationServerMetadata", summary = "Get OAuth Authorization Server Metadata", description = "Get OAuth 2.1 Authorization Server Metadata (RFC 8414)", responses = {
            @ApiResponse(responseCode = "200", description = "Authorization Server Metadata", content = @Content(schema = @Schema(implementation = AuthorizationServerMetadata.class)))})
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
