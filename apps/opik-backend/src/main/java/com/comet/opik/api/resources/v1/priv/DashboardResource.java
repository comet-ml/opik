package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.dashboard.CreateDashboardRequest;
import com.comet.opik.api.dashboard.Dashboard;
import com.comet.opik.api.dashboard.DashboardUpdate;
import com.comet.opik.domain.DashboardService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.comet.opik.utils.AsyncUtils;
import com.fasterxml.jackson.annotation.JsonView;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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

import java.net.URI;
import java.util.UUID;

import static com.comet.opik.api.dashboard.Dashboard.DashboardPage;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;

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
    @Path("/{id}")
    @Operation(operationId = "getDashboardById", summary = "Get dashboard by id", description = "Get dashboard by id", responses = {
            @ApiResponse(responseCode = "200", description = "Dashboard resource", content = @Content(schema = @Schema(implementation = Dashboard.class)))
    })
    @JsonView(Dashboard.View.Public.class)
    public Response getDashboardById(@PathParam("id") UUID id) {
        var dashboard = setRequestContext(requestContext)
                .then(service.findById(id))
                .block();

        return Response.ok(dashboard).build();
    }

    @GET
    @Operation(operationId = "getDashboards", summary = "Get dashboards", description = "Get dashboards with pagination and search", responses = {
            @ApiResponse(responseCode = "200", description = "Dashboard page", content = @Content(schema = @Schema(implementation = DashboardPage.class)))
    })
    @JsonView(Dashboard.View.Public.class)
    public Response getDashboards(
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("25") @Min(1) int size,
            @QueryParam("search") String search,
            @QueryParam("order_by") String orderBy,
            @QueryParam("sort_order") String sortOrder) {

        var dashboardPage = setRequestContext(requestContext)
                .then(service.find(page, size, search, orderBy, sortOrder))
                .block();

        return Response.ok(dashboardPage).build();
    }

    @POST
    @Operation(operationId = "createDashboard", summary = "Create dashboard", description = "Create a new dashboard", responses = {
            @ApiResponse(responseCode = "201", description = "Created dashboard", content = @Content(schema = @Schema(implementation = Dashboard.class)))
    })
    @JsonView(Dashboard.View.Public.class)
    @RateLimited
    public Response createDashboard(
            @RequestBody(content = @Content(schema = @Schema(implementation = CreateDashboardRequest.class))) @Valid @NotNull CreateDashboardRequest request,
            @Context UriInfo uriInfo) {

        var dashboard = setRequestContext(requestContext)
                .then(service.create(request))
                .block();

        var uri = URI.create(uriInfo.getAbsolutePath() + "/" + dashboard.id());
        return Response.created(uri).entity(dashboard).build();
    }

    @PATCH
    @Path("/{id}")
    @Operation(operationId = "updateDashboard", summary = "Update dashboard", description = "Update an existing dashboard", responses = {
            @ApiResponse(responseCode = "200", description = "Updated dashboard", content = @Content(schema = @Schema(implementation = Dashboard.class)))
    })
    @JsonView(Dashboard.View.Public.class)
    @RateLimited
    public Response updateDashboard(
            @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = DashboardUpdate.class))) @Valid @NotNull DashboardUpdate update) {

        var dashboard = setRequestContext(requestContext)
                .then(service.update(id, update))
                .block();

        return Response.ok(dashboard).build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteDashboard", summary = "Delete dashboard", description = "Delete a dashboard", responses = {
            @ApiResponse(responseCode = "204", description = "Dashboard deleted")
    })
    @RateLimited
    public Response deleteDashboard(@PathParam("id") UUID id) {
        setRequestContext(requestContext)
                .then(service.delete(id))
                .block();

        return Response.noContent().build();
    }
}
