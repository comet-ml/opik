package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AuthDetailsHolder;
import com.comet.opik.api.WorkspaceListResponse;
import com.comet.opik.api.WorkspaceNameHolder;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.AuthService;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/private/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Check", description = "Access check resources")
public class AuthenticationResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull AuthService authService;

    @POST
    @Operation(operationId = "checkAccess", summary = "Check user access to workspace", description = "Check user access to workspace", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "401", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response checkAccess(
            @RequestBody(content = @Content(schema = @Schema(implementation = AuthDetailsHolder.class))) @Valid AuthDetailsHolder authDetailsHolder) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("User '{}' has access to workspace_id '{}'", userName, workspaceId);

        return Response.noContent().build();
    }

    @GET
    @Path("workspace")
    @Operation(operationId = "getWorkspaceName", summary = "User's default workspace name", description = "User's default workspace name", responses = {
            @ApiResponse(responseCode = "200", description = "Authentication resource", content = @Content(schema = @Schema(implementation = WorkspaceNameHolder.class))),
            @ApiResponse(responseCode = "401", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getWorkspaceName() {
        String workspaceName = requestContext.get().getWorkspaceName();
        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("User '{}' has workspaceName '{}', workspaceid '{}'", userName, workspaceName, workspaceId);

        return Response.ok().entity(WorkspaceNameHolder.builder()
                .workspaceName(workspaceName)
                .build())
                .build();
    }

    @GET
    @Path("workspaces")
    @Operation(operationId = "getUserWorkspaces", summary = "Get user's workspaces", description = "Get list of workspaces accessible to the user", responses = {
            @ApiResponse(responseCode = "200", description = "List of workspaces", content = @Content(schema = @Schema(implementation = WorkspaceListResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getUserWorkspaces(
            @QueryParam("organizationId") String organizationId,
            HttpHeaders headers) {
        String userName = requestContext.get().getUserName();
        String apiKey = requestContext.get().getApiKey();
        Cookie sessionToken = headers.getCookies().get(RequestContext.SESSION_COOKIE);

        log.info("Getting workspaces for user '{}'", userName);

        var workspaces = authService.getUserWorkspaces(apiKey, sessionToken, organizationId);

        log.info("Found {} workspaces for user '{}'", workspaces.size(), userName);

        return Response.ok().entity(WorkspaceListResponse.builder()
                .workspaces(workspaces)
                .build())
                .build();
    }
}
