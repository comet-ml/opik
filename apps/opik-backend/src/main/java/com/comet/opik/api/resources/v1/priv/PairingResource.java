package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.connect.ActivateRequest;
import com.comet.opik.api.connect.CreateSessionRequest;
import com.comet.opik.api.connect.CreateSessionResponse;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.pairing.PairingService;
import com.comet.opik.infrastructure.LocalRunnerConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Path("/v1/private/pairing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Pairing", description = "Pairing sessions for the `opik connect` and `opik endpoint` CLI commands")
public class PairingResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull PairingService pairingService;
    private final @NonNull LocalRunnerConfig runnerConfig;

    @POST
    @Path("/sessions")
    @RateLimited
    @Operation(operationId = "createPairingSession", summary = "Create a pairing session", description = "Register a short-lived pairing session that a local daemon will later activate via HMAC", responses = {
            @ApiResponse(responseCode = "201", description = "Session created", content = @Content(schema = @Schema(implementation = CreateSessionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Project not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable entity", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "501", description = "Feature disabled", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response createSession(
            @RequestBody(content = @Content(schema = @Schema(implementation = CreateSessionRequest.class))) @NotNull @Valid CreateSessionRequest request) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        CreateSessionResponse response = pairingService.create(workspaceId, userName, request);
        return Response.status(Response.Status.CREATED).entity(response).build();
    }

    @POST
    @Path("/sessions/{sessionId}/activate")
    @RateLimited
    @Operation(operationId = "activatePairingSession", summary = "Activate a pairing session", description = "Verify the activation HMAC and flip the runner row to CONNECTED", responses = {
            @ApiResponse(responseCode = "201", description = "Session activated", headers = @Header(name = "Location", description = "URI of the runner")),
            @ApiResponse(responseCode = "403", description = "Invalid HMAC", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Session not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Session already activated", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable entity", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "501", description = "Feature disabled", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response activate(
            @PathParam("sessionId") @NotNull UUID sessionId,
            @RequestBody(content = @Content(schema = @Schema(implementation = ActivateRequest.class))) @NotNull @Valid ActivateRequest request,
            @Context UriInfo uriInfo) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        UUID runnerId = pairingService.activate(workspaceId, userName, sessionId, request);
        URI location = uriInfo.getBaseUriBuilder()
                .path("v1/private/local-runners/{runnerId}")
                .build(runnerId);
        return Response.created(location).build();
    }

    private void ensureEnabled() {
        if (!runnerConfig.isEnabled()) {
            throw new WebApplicationException(
                    Response.status(Response.Status.NOT_IMPLEMENTED)
                            .type(MediaType.APPLICATION_JSON)
                            .entity(new ErrorMessage(List.of("pairing is not enabled on this deployment")))
                            .build());
        }
    }
}
