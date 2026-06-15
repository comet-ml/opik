package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.mcpoauth.ClientRegistrationRequest;
import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/oauth/register")
@Timed
@Slf4j
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "MCP OAuth", description = "MCP OAuth 2.1 Authorization Server resources")
public class OAuthRegisterResource {

    private final @NonNull OAuthClientService clientService;

    /**
     * DCR is open (no auth) by design — throttled per source IP via {@link RateLimited} to bound spam registrations.
     * RFC 7591 §3.2.1: respond 201 with the client metadata body.
     */
    @POST
    @RateLimited(value = "mcpOAuthRegister:{clientIp}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    @Operation(operationId = "registerOAuthClient", summary = "OAuth Dynamic Client Registration Endpoint", description = "OAuth 2.0 Dynamic Client Registration (RFC 7591). Registers a public client for the MCP OAuth flow; throttled per source IP", responses = {
            @ApiResponse(responseCode = "201", description = "Registered client metadata", content = @Content(schema = @Schema(implementation = ClientRegistrationResponse.class))),
            @ApiResponse(responseCode = "429", description = "Registration rate limit exceeded")})
    public Response register(@NonNull @Valid ClientRegistrationRequest request) {

        log.info("MCP OAuth client registration request '{}'", request.clientName());

        McpOAuthClient client = clientService.register(request);
        ClientRegistrationResponse body = ClientRegistrationResponseMapper.INSTANCE.toResponse(client);
        log.info("MCP OAuth client registered '{}'", client.id());
        return Response.status(Response.Status.CREATED).entity(body).build();
    }
}
