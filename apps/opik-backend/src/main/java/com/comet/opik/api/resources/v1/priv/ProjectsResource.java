package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Page;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectCriteria;
import com.comet.opik.api.ProjectRetrieve;
import com.comet.opik.api.ProjectStatsSummary;
import com.comet.opik.api.ProjectUpdate;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.comet.opik.api.resources.v1.priv.validate.ParamsValidator;
import com.comet.opik.api.sorting.SortingFactoryProjects;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectMetricsService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.domain.ProjectMetricsService.ERR_START_BEFORE_END;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/projects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Projects", description = "Project related resources")
public class ProjectsResource {

    private static final String PAGE_SIZE = "10";
    private final @NonNull ProjectService projectService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull SortingFactoryProjects sortingFactory;
    private final @NonNull ProjectMetricsService metricsService;
    private final @NonNull FeedbackScoreService feedbackScoreService;

    @GET
    @Operation(operationId = "findProjects", summary = "Find projects", description = "Find projects", responses = {
            @ApiResponse(responseCode = "200", description = "Project resource", content = @Content(schema = @Schema(implementation = Project.ProjectPage.class)))
    })
    @JsonView({Project.View.Public.class})
    public Response find(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue(PAGE_SIZE) int size,
            @QueryParam("name") String name,
            @QueryParam("sorting") String sorting) {

        var criteria = ProjectCriteria.builder()
                .projectName(name)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);

        log.info("Find projects by '{}' on workspaceId '{}'", criteria, workspaceId);
        Page<Project> projectPage = projectService.find(page, size, criteria, sortingFields);
        log.info("Found projects by '{}', count '{}' on workspaceId '{}'", criteria, projectPage.size(), workspaceId);

        return Response.ok().entity(projectPage).build();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getProjectById", summary = "Get project by id", description = "Get project by id", responses = {
            @ApiResponse(responseCode = "200", description = "Project resource", content = @Content(schema = @Schema(implementation = Project.class)))})
    @JsonView({Project.View.Public.class})
    public Response getById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting project by id '{}' on workspace_id '{}'", id, workspaceId);

        Project project = projectService.get(id);

        log.info("Got project by id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.ok().entity(project).build();
    }

    @POST
    @Operation(operationId = "createProject", summary = "Create project", description = "Create project", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/projects/{projectId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = Project.class))) @JsonView(Project.View.Write.class) @Valid Project project,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating project with name '{}', on workspace_id '{}'", project.name(), workspaceId);

        var projectId = projectService.create(project).id();

        log.info("Created project with name '{}', id '{}', on workspace_id '{}'", project.name(), projectId,
                workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(projectId)).build();

        return Response.created(uri).build();
    }

    @PATCH
    @Path("{id}")
    @Operation(operationId = "updateProject", summary = "Update project by id", description = "Update project by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response update(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = ProjectUpdate.class))) @Valid ProjectUpdate project) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating project with id '{}' on workspaceId '{}'", id, workspaceId);
        projectService.update(id, project);
        log.info("Updated project with id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteProjectById", summary = "Delete project by id", description = "Delete project by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response deleteById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting project by id '{}' on workspaceId '{}'", id, workspaceId);
        projectService.delete(id);
        log.info("Deleted project by id '{}' on workspaceId '{}'", id, workspaceId);
        return Response.noContent().build();
    }

    @POST
    @Path("/retrieve")
    @Operation(operationId = "retrieveProject", summary = "Retrieve project", description = "Retrieve project", responses = {
            @ApiResponse(responseCode = "200", description = "Project resource", content = @Content(schema = @Schema(implementation = Project.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @JsonView({Project.View.Detailed.class})
    public Response retrieveProject(
            @RequestBody(content = @Content(schema = @Schema(implementation = ProjectRetrieve.class))) @Valid ProjectRetrieve retrieve) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Retrieve project by name '{}', on workspace_id '{}'", retrieve.name(), workspaceId);
        Project project = projectService.retrieveByName(retrieve.name());
        log.info("Retrieved project id '{}' by name '{}', on workspace_id '{}'", project.id(), retrieve.name(),
                workspaceId);
        return Response.ok().entity(project).build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteProjectsBatch", summary = "Delete projects", description = "Delete projects batch", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
    })
    public Response deleteProjectsBatch(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting projects by ids, count '{}', on workspace_id '{}'", batchDelete.ids().size(), workspaceId);
        projectService.delete(batchDelete.ids());
        log.info("Deleted projects by ids, count '{}', on workspace_id '{}'", batchDelete.ids().size(), workspaceId);
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/metrics")
    @Operation(operationId = "getProjectMetrics", summary = "Get Project Metrics", description = "Gets specified metrics for a project", responses = {
            @ApiResponse(responseCode = "200", description = "Project Metrics", content = @Content(schema = @Schema(implementation = ProjectMetricResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @JsonView({Project.View.Public.class})
    public Response getProjectMetrics(
            @PathParam("id") UUID projectId,
            @RequestBody(content = @Content(schema = @Schema(implementation = ProjectMetricRequest.class))) @Valid ProjectMetricRequest request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        validate(request);

        log.info("Retrieve project metrics for projectId '{}', on workspace_id '{}', metric '{}'", projectId,
                workspaceId, request.metricType());
        ProjectMetricResponse<? extends Number> response = metricsService.getProjectMetrics(projectId, request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved project id metrics for projectId '{}', on workspace_id '{}', metric '{}'", projectId,
                workspaceId, request.metricType());

        return Response.ok().entity(response).build();
    }

    @GET
    @Path("/feedback-scores/names")
    @Operation(operationId = "findFeedbackScoreNamesByProjectIds", summary = "Find Feedback Score names By Project Ids", description = "Find Feedback Score names By Project Ids", responses = {
            @ApiResponse(responseCode = "200", description = "Feedback Scores resource", content = @Content(schema = @Schema(implementation = FeedbackScoreNames.class)))
    })
    public Response findFeedbackScoreNames(@QueryParam("project_ids") String projectIdsQueryParam) {

        var projectIds = Optional.ofNullable(projectIdsQueryParam)
                .map(ParamsValidator::getIds)
                .orElse(Collections.emptySet());

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Find feedback score names by project_ids '{}', on workspaceId '{}'",
                projectIds, workspaceId);
        FeedbackScoreNames feedbackScoreNames = feedbackScoreService
                .getProjectsFeedbackScoreNames(projectIds)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Found feedback score names '{}' by project_ids '{}', on workspaceId '{}'",
                feedbackScoreNames.scores().size(), projectIds, workspaceId);

        return Response.ok(feedbackScoreNames).build();
    }

    private void validate(ProjectMetricRequest request) {
        if (!request.intervalStart().isBefore(request.intervalEnd())) {
            throw new BadRequestException(ERR_START_BEFORE_END);
        }
    }

    @GET
    @Path("/stats")
    @Operation(operationId = "getProjectStats", summary = "Get Project Stats", description = "Get Project Stats", responses = {
            @ApiResponse(responseCode = "200", description = "Project Stats", content = @Content(schema = @Schema(implementation = ProjectStatsSummary.class))),
    })
    public Response getProjectStats(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue(PAGE_SIZE) int size,
            @QueryParam("name") String name,
            @QueryParam("sorting") String sorting) {

        var criteria = ProjectCriteria.builder()
                .projectName(name)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);

        log.info("Find projects stats by '{}' on workspaceId '{}'", criteria, workspaceId);
        ProjectStatsSummary projectStatisticsSummary = projectService.getStats(page, size, criteria, sortingFields);
        log.info("Found projects stats by '{}', count '{}' on workspaceId '{}'", criteria,
                projectStatisticsSummary.content().size(), workspaceId);

        return Response.ok().entity(projectStatisticsSummary).build();
    }

}
