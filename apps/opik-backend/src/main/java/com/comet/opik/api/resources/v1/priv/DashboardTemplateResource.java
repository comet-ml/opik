package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.DashboardTemplate;
import com.comet.opik.api.DashboardTemplateUpdate;
import com.comet.opik.domain.DashboardTemplateService;
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
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
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

@Path("/v1/private/dashboard-templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Dashboard Templates", description = "Dashboard template resources")
public class DashboardTemplateResource {

    private final @NonNull DashboardTemplateService service;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "findDashboardTemplates", summary = "Find dashboard templates", description = "Find dashboard templates", responses = {
            @ApiResponse(responseCode = "200", description = "Dashboard templates list", content = @Content(schema = @Schema(implementation = DashboardTemplate.class)))
    })
    @JsonView(DashboardTemplate.View.Public.class)
    public Response findDashboardTemplates() {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding dashboard templates on workspaceId '{}'", workspaceId);
        List<DashboardTemplate> templates = service.findAll();
        log.info("Found {} dashboard templates on workspaceId '{}'", templates.size(), workspaceId);

        return Response.ok().entity(templates).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getDashboardTemplateById", summary = "Get dashboard template by id", description = "Get dashboard template by id", responses = {
            @ApiResponse(responseCode = "200", description = "Dashboard template resource", content = @Content(schema = @Schema(implementation = DashboardTemplate.class)))
    })
    @JsonView(DashboardTemplate.View.Public.class)
    public Response getDashboardTemplateById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding dashboard template by id '{}' on workspaceId '{}'", id, workspaceId);
        DashboardTemplate template = service.findById(id);
        log.info("Found dashboard template by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.ok().entity(template).build();
    }

    @POST
    @Operation(operationId = "createDashboardTemplate", summary = "Create dashboard template", description = "Create dashboard template", responses = {
            @ApiResponse(responseCode = "201", description = "Created dashboard template", content = @Content(schema = @Schema(implementation = DashboardTemplate.class)), headers = {
                    @Header(name = "Location", required = true, schema = @Schema(implementation = String.class))})
    })
    @RateLimited
    @JsonView(DashboardTemplate.View.Public.class)
    public Response createDashboardTemplate(
            @RequestBody(content = @Content(schema = @Schema(implementation = DashboardTemplate.class))) @JsonView(DashboardTemplate.View.Write.class) @Valid @NotNull DashboardTemplate template,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating dashboard template '{}' on workspaceId '{}'", template.name(), workspaceId);
        var createdTemplate = service.create(template);
        log.info("Created dashboard template '{}' with id '{}' on workspaceId '{}'", createdTemplate.name(),
                createdTemplate.id(), workspaceId);

        URI location = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(createdTemplate.id())).build();

        return Response.created(location).entity(createdTemplate).build();
    }

    @PUT
    @Path("/{id}")
    @Operation(operationId = "updateDashboardTemplate", summary = "Update dashboard template", description = "Update dashboard template", responses = {
            @ApiResponse(responseCode = "200", description = "Updated dashboard template", content = @Content(schema = @Schema(implementation = DashboardTemplate.class)))
    })
    @RateLimited
    @JsonView(DashboardTemplate.View.Public.class)
    public Response updateDashboardTemplate(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = DashboardTemplateUpdate.class))) @Valid @NotNull DashboardTemplateUpdate templateUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating dashboard template by id '{}' on workspaceId '{}'", id, workspaceId);
        var updatedTemplate = service.update(id, templateUpdate);
        log.info("Updated dashboard template by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.ok().entity(updatedTemplate).build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteDashboardTemplate", summary = "Delete dashboard template", description = "Delete dashboard template", responses = {
            @ApiResponse(responseCode = "204", description = "No content")
    })
    @RateLimited
    public Response deleteDashboardTemplate(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting dashboard template by id '{}' on workspaceId '{}'", id, workspaceId);
        service.delete(id);
        log.info("Deleted dashboard template by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Operation(operationId = "deleteDashboardTemplates", summary = "Delete dashboard templates", description = "Delete dashboard templates in batch", responses = {
            @ApiResponse(responseCode = "204", description = "No content")
    })
    @RateLimited
    public Response deleteDashboardTemplates(
            @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid @NotNull BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting dashboard templates '{}' on workspaceId '{}'", batchDelete.ids(), workspaceId);
        service.delete(batchDelete.ids());
        log.info("Deleted dashboard templates '{}' on workspaceId '{}'", batchDelete.ids(), workspaceId);

        return Response.noContent().build();
    }
}