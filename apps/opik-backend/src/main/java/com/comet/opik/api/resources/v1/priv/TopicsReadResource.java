package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.topics.TopicReadService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.UUID;

@Path("/v1/private/topics")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Topics", description = "Project topics and internal topics operations")
public class TopicsReadResource {

    private final @NonNull TopicReadService service;
    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "getProjectTopics", summary = "Get a project's topics", description = "The project's topics over the last 30 UTC days, today included: per topic its count, share, today's change and a daily series, plus Other. status is disabled when the workspace is not opted in to topics, collecting while the project has no topics yet, and ready otherwise. Fields of other states are omitted.", responses = {
            @ApiResponse(responseCode = "200", description = "The project's topics", content = @Content(schema = @Schema(implementation = ProjectTopics.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "The project is not in the workspace", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "501", description = "Topics are off"),
            @ApiResponse(responseCode = "503", description = "The counts read hit a limit; retry after Retry-After seconds", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    @RateLimited(value = "getProjectTopics:{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    public Response getProjectTopics(@QueryParam("project_id") @NotNull UUID projectId) {
        if (!config.isEnabled()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).build();
        }
        var context = requestContext.get();
        log.info("Getting project topics, project_id '{}', workspace_id '{}'", projectId, context.getWorkspaceId());
        var topics = service.get(context.getWorkspaceId(), context.getWorkspaceName(), projectId);
        return Response.ok(topics).build();
    }
}
