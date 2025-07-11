package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Dashboard;
import com.comet.opik.api.DashboardPanel;
import com.comet.opik.api.DashboardSection;
import com.comet.opik.api.DashboardUpdate;
import com.comet.opik.api.ExperimentDashboard;
import com.comet.opik.domain.DashboardService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
import io.dropwizard.jersey.errors.ErrorMessage;
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
import java.util.Optional;
import java.util.UUID;

@Path("/v1/private/dashboards")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Dashboards", description = "Dashboard resources")
public class DashboardResource {

    private final @NonNull DashboardService service;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "findDashboards", summary = "Find dashboards", description = "Find dashboards", responses = {
            @ApiResponse(responseCode = "200", description = "Dashboards list", content = @Content(schema = @Schema(implementation = Dashboard.class)))
    })
    @JsonView(Dashboard.View.Public.class)
    public Response findDashboards() {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding dashboards on workspaceId '{}'", workspaceId);
        List<Dashboard> dashboards = service.findAll();
        log.info("Found {} dashboards on workspaceId '{}'", dashboards.size(), workspaceId);

        return Response.ok().entity(dashboards).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getDashboardById", summary = "Get dashboard by id", description = "Get dashboard by id", responses = {
            @ApiResponse(responseCode = "200", description = "Dashboard resource", content = @Content(schema = @Schema(implementation = Dashboard.class)))
    })
    @JsonView(Dashboard.View.Public.class)
    public Response getDashboardById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding dashboard by id '{}' on workspaceId '{}'", id, workspaceId);
        Dashboard dashboard = service.findById(id);
        log.info("Found dashboard by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.ok().entity(dashboard).build();
    }

    @POST
    @Operation(operationId = "createDashboard", summary = "Create dashboard", description = "Create dashboard", responses = {
            @ApiResponse(responseCode = "201", description = "Created", content = @Content(schema = @Schema(implementation = Dashboard.class)), headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/api/v1/private/dashboards/{id}", schema = @Schema(implementation = String.class))
            })
    })
    @RateLimited
    @JsonView(Dashboard.View.Public.class)
    public Response createDashboard(
            @RequestBody(content = @Content(schema = @Schema(implementation = Dashboard.class))) @JsonView(Dashboard.View.Write.class) @NotNull @Valid Dashboard dashboard,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating dashboard with name '{}', on workspace_id '{}'", dashboard.name(), workspaceId);
        Dashboard savedDashboard = service.create(dashboard);
        log.info("Created dashboard with name '{}', id '{}', on workspace_id '{}'", savedDashboard.name(),
                savedDashboard.id(), workspaceId);

        URI uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(savedDashboard.id().toString())).build();
        return Response.created(uri).entity(savedDashboard).build();
    }

    @PUT
    @Path("{id}")
    @Operation(operationId = "updateDashboard", summary = "Update dashboard by id", description = "Update dashboard by id", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    @RateLimited
    public Response updateDashboard(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = DashboardUpdate.class))) @NotNull @Valid DashboardUpdate dashboardUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Updating dashboard by id '{}' on workspace_id '{}'", id, workspaceId);
        service.update(id, dashboardUpdate);
        log.info("Updated dashboard by id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteDashboard", summary = "Delete dashboard by id", description = "Delete dashboard by id", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    public Response deleteDashboard(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting dashboard by id '{}' on workspace_id '{}'", id, workspaceId);
        service.delete(id);
        log.info("Deleted dashboard by id '{}' on workspace_id '{}'", id, workspaceId);
        return Response.noContent().build();
    }

    @DELETE
    @Operation(operationId = "deleteDashboards", summary = "Delete dashboards", description = "Delete dashboards", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "400", description = "Bad request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response deleteDashboards(
            @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting dashboards by ids '{}' on workspace_id '{}'", batchDelete.ids(), workspaceId);
        service.delete(batchDelete.ids());
        log.info("Deleted dashboards by ids '{}' on workspace_id '{}'", batchDelete.ids(), workspaceId);

        return Response.noContent().build();
    }

    // Experiment Dashboard Association Endpoints

    @POST
    @Path("/experiments/{experimentId}/associate/{dashboardId}")
    @Operation(operationId = "associateExperimentWithDashboard", summary = "Associate experiment with dashboard", description = "Associate experiment with dashboard", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    public Response associateExperimentWithDashboard(@PathParam("experimentId") UUID experimentId,
            @PathParam("dashboardId") UUID dashboardId) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Associating experiment '{}' with dashboard '{}' on workspace_id '{}'", experimentId, dashboardId,
                workspaceId);
        service.associateExperimentWithDashboard(experimentId, dashboardId);
        log.info("Associated experiment '{}' with dashboard '{}' on workspace_id '{}'", experimentId, dashboardId,
                workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/experiments/{experimentId}/association")
    @Operation(operationId = "removeExperimentDashboardAssociation", summary = "Remove experiment dashboard association", description = "Remove experiment dashboard association", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    public Response removeExperimentDashboardAssociation(@PathParam("experimentId") UUID experimentId) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Removing experiment dashboard association for experiment '{}' on workspace_id '{}'", experimentId,
                workspaceId);
        service.removeExperimentDashboardAssociation(experimentId);
        log.info("Removed experiment dashboard association for experiment '{}' on workspace_id '{}'", experimentId,
                workspaceId);

        return Response.noContent().build();
    }

    @GET
    @Path("/experiments/{experimentId}")
    @Operation(operationId = "getExperimentDashboard", summary = "Get experiment dashboard association", description = "Get experiment dashboard association", responses = {
            @ApiResponse(responseCode = "200", description = "Experiment dashboard association", content = @Content(schema = @Schema(implementation = ExperimentDashboard.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @JsonView(ExperimentDashboard.View.Public.class)
    public Response getExperimentDashboard(@PathParam("experimentId") UUID experimentId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding experiment dashboard association for experiment '{}' on workspace_id '{}'", experimentId,
                workspaceId);
        Optional<ExperimentDashboard> experimentDashboard = service.findExperimentDashboard(experimentId);

        if (experimentDashboard.isEmpty()) {
            log.info("Experiment dashboard association not found for experiment '{}' on workspace_id '{}'",
                    experimentId, workspaceId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        log.info("Found experiment dashboard association for experiment '{}' on workspace_id '{}'", experimentId,
                workspaceId);
        return Response.ok(experimentDashboard.get()).build();
    }

    // Dashboard Section and Panel Creation Endpoints

    @POST
    @Path("/{dashboardId}/sections")
    @Operation(operationId = "createDashboardSection", summary = "Create a dashboard section", description = "Create a dashboard section", responses = {
            @ApiResponse(responseCode = "201", description = "Created", content = @Content(schema = @Schema(implementation = DashboardSection.class)))
    })
    @RateLimited
    @JsonView(Dashboard.View.Public.class)
    public Response createDashboardSection(
            @PathParam("dashboardId") UUID dashboardId,
            @RequestBody(content = @Content(schema = @Schema(implementation = CreateDashboardSectionRequest.class))) @NotNull @Valid CreateDashboardSectionRequest request,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating section for dashboard '{}' on workspace_id '{}'", dashboardId, workspaceId);

        DashboardSection createdSection = service.createSection(dashboardId, request.title, request.position_order);

        log.info("Created section with id '{}' for dashboard '{}' on workspace_id '{}'", createdSection.id(),
                dashboardId, workspaceId);

        URI uri = uriInfo.getAbsolutePathBuilder().path("sections/%s".formatted(createdSection.id().toString()))
                .build();
        return Response.created(uri).entity(createdSection).build();
    }

    @POST
    @Path("/{dashboardId}/sections/{sectionId}/panels")
    @Operation(operationId = "createDashboardPanel", summary = "Create a dashboard panel", description = "Create a dashboard panel", responses = {
            @ApiResponse(responseCode = "201", description = "Created", content = @Content(schema = @Schema(implementation = DashboardPanel.class)))
    })
    @RateLimited
    @JsonView(Dashboard.View.Public.class)
    public Response createDashboardPanel(
            @PathParam("dashboardId") UUID dashboardId,
            @PathParam("sectionId") UUID sectionId,
            @RequestBody(content = @Content(schema = @Schema(implementation = CreateDashboardPanelRequest.class))) @NotNull @Valid CreateDashboardPanelRequest request,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating panel for section '{}' in dashboard '{}' on workspace_id '{}'", sectionId, dashboardId,
                workspaceId);

        DashboardPanel createdPanel = service.createPanel(dashboardId, sectionId, request.name, request.type,
                request.configuration, request.layout, request.templateId);

        log.info("Created panel with id '{}' for section '{}' in dashboard '{}' on workspace_id '{}'",
                createdPanel.id(), sectionId, dashboardId, workspaceId);

        URI uri = uriInfo.getAbsolutePathBuilder().path("panels/%s".formatted(createdPanel.id().toString())).build();
        return Response.created(uri).entity(createdPanel).build();
    }

    // Request DTOs for section and panel creation
    public static class CreateDashboardSectionRequest {
        public String title;
        public Integer position_order;
    }

    public static class CreateDashboardPanelRequest {
        public String name;
        public String type;
        public Object configuration;
        public PanelLayout layout;
        public UUID templateId;

        public static class PanelLayout {
            public int x;
            public int y;
            public int w;
            public int h;
        }
    }
}
