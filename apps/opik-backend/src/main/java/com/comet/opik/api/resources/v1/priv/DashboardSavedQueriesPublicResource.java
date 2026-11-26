package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.DashboardSavedQuery;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.DashboardSavedQueryService;
import com.comet.opik.infrastructure.CustomChartsConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.UUID;

/**
 * Public entry point for saving and reading the queries Ollie chart widgets run, called by the Opik MCP server and by
 * API keys directly. Saving checks the SQL as a widget run would before storing it; a saved query only ever runs
 * through a widget, by reference, never through this resource. Save spends the same per-workspace rate limit and
 * concurrent-query slots as {@link AnalyticsQueriesPublicResource}: its checks query ClickHouse as the EXTENDED user
 * too. Ollie's internal save is not counted against the slots, like the rest of its chart authoring.
 */
@Path("/v1/private/dashboard-saved-queries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@Tag(name = "Dashboard saved queries", description = "Saved queries Ollie chart widgets run")
public class DashboardSavedQueriesPublicResource {

    private final DashboardSavedQueryService dashboardSavedQueryService;
    private final ServiceTogglesConfig serviceToggles;
    private final CustomChartsConfig customCharts;

    // Hand-written rather than Lombok's: image builds do not read lombok.config, so a generated constructor loses the
    // @Config qualifiers and customCharts is injected as an empty default instance (OPIK-8548).
    @Inject
    public DashboardSavedQueriesPublicResource(
            @NonNull DashboardSavedQueryService dashboardSavedQueryService,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles,
            @NonNull @Config("customCharts") CustomChartsConfig customCharts) {
        this.dashboardSavedQueryService = dashboardSavedQueryService;
        this.serviceToggles = serviceToggles;
        this.customCharts = customCharts;
    }

    @POST
    @Operation(operationId = "saveDashboardQuery", summary = "Save a chart query", description = "Saves the SQL of a chart so an ollie_chart dashboard widget can reference it by id and run it over the dashboard's date range, through the {{window_start}} and {{window_end}} placeholders. The SQL is checked as a widget run would check it, and rejected with 400 if a run would reject it. Returns 501 when Custom Charts or its public endpoints are not enabled.", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/dashboard-saved-queries/{id}", schema = @Schema(implementation = String.class))}, content = @Content(schema = @Schema(implementation = DashboardSavedQuery.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "429", description = "Too many analytics queries, or too many running in the workspace; retry after the Retry-After delay", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "501", description = "Custom Charts or its public endpoints are not enabled")})
    @RequiredPermissions(WorkspaceUserPermission.DASHBOARD_CREATE)
    @RateLimited(value = "analyticsQueries:{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    public Response save(
            @RequestBody(content = @Content(schema = @Schema(implementation = DashboardSavedQuery.class))) @NotNull @Valid DashboardSavedQuery query,
            @Context UriInfo uriInfo) {
        requireEnabled();
        var saved = dashboardSavedQueryService.createInWorkspaceSlot(query);
        var uri = uriInfo.getAbsolutePathBuilder().path(saved.id().toString()).build();
        return Response.created(uri).entity(saved).build();
    }

    /**
     * Public so an MCP client or API user can read back what a chart runs, to explain it or to save a changed copy:
     * saved queries are never edited in place. It returns the stored SQL, placeholders included, and runs nothing,
     * so it needs no SQL checks and stays off the analytics-queries rate limit; a widget run re-checks the query
     * every time.
     */
    @GET
    @Path("/{id}")
    @Operation(operationId = "getDashboardQuery", summary = "Get a chart query", description = "Gets a saved chart query of the caller's workspace by id, its SQL as saved, placeholders included. Returns 501 when Custom Charts or its public endpoints are not enabled.", responses = {
            @ApiResponse(responseCode = "200", description = "Dashboard saved query", content = @Content(schema = @Schema(implementation = DashboardSavedQuery.class))),
            @ApiResponse(responseCode = "404", description = "Not found"),
            @ApiResponse(responseCode = "501", description = "Custom Charts or its public endpoints are not enabled")})
    @RequiredPermissions(WorkspaceUserPermission.DASHBOARD_VIEW)
    @RateLimited
    public DashboardSavedQuery get(@PathParam("id") UUID id) {
        requireEnabled();
        return dashboardSavedQueryService.findById(id)
                .orElseThrow(
                        () -> new NotFoundException("Dashboard saved query '%s' not found".formatted(id)));
    }

    private void requireEnabled() {
        if (!customCharts.isPublicEndpointsEnabled(serviceToggles)) {
            throw new ServerErrorException(Response.Status.NOT_IMPLEMENTED);
        }
    }
}
