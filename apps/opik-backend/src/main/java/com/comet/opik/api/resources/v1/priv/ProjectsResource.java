package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.Page;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectCriteria;
import com.comet.opik.api.ProjectUpdate;
import com.comet.opik.api.error.ErrorMessage;
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

import java.util.UUID;

@Path("/v1/private/projects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Projects", description = "Project related resources")
public class ProjectsResource {

    private final @NonNull ProjectService projectService;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "findProjects", summary = "Find projects", description = "Find projects", responses = {
            @ApiResponse(responseCode = "200", description = "Project resource", content = @Content(schema = @Schema(implementation = Project.ProjectPage.class)))
    })
    @JsonView({Project.View.Public.class})
    public Response find(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("name") String name) {

        var criteria = ProjectCriteria.builder()
                .projectName(name)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Find projects by '{}' on workspaceId '{}'", criteria, workspaceId);
        Page<Project> projectPage = projectService.find(page, size, criteria);
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
    @Operation(operationId = "createProject", summary = "Create project", description = "Get project", responses = {
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

}
