package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.mcpoauth.ClientRegistrationRequest;
import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.util.List;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.AUTH_METHOD_NONE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_AUTHORIZATION_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_REFRESH_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.RESPONSE_TYPE_CODE;

@Path("/oauth/register")
@Timed
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OAuthRegisterResource {

    private final @NonNull OAuthClientService clientService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response register(@NonNull ClientRegistrationRequest request) {
        McpOAuthClient client = clientService.register(request);
        ClientRegistrationResponse body = ClientRegistrationResponse.builder()
                .clientId(client.clientId())
                .clientIdIssuedAt(client.createdAt() == null ? null : client.createdAt().getEpochSecond())
                .clientName(client.name())
                .logoUri(client.logoUri())
                .redirectUris(client.redirectUris())
                .tokenEndpointAuthMethod(AUTH_METHOD_NONE)
                .grantTypes(List.of(GRANT_AUTHORIZATION_CODE, GRANT_REFRESH_TOKEN))
                .responseTypes(List.of(RESPONSE_TYPE_CODE))
                .build();
        // RFC 7591 §3.2.1: the response SHOULD include a Location header pointing
        // at a client-configuration endpoint. We don't expose a public read-back
        // endpoint, so we point at the admin path used for manual lifecycle
        // operations; the URI is informational and not part of the OAuth dance.
        return Response.created(URI.create("/admin/mcp-oauth-clients/" + client.clientId()))
                .entity(body)
                .build();
    }
}
