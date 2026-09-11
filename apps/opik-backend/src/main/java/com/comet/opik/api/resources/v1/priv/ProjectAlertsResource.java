package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.Alert;
import com.comet.opik.api.filter.AlertFilter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.sorting.SortingFactoryAlerts;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.AlertService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.annotation.JsonView;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

@Path("/v1/private/projects/{projectId}/alerts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Projects", description = "Project related resources")
public class ProjectAlertsResource {

    private final @NonNull AlertService alertService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull SortingFactoryAlerts sortingFactory;
    private final @NonNull FiltersFactory filtersFactory;

    @GET
    @Operation(operationId = "findAlertsByProject", summary = "Find alerts by project", description = "Find alerts scoped to a project", responses = {
            @ApiResponse(responseCode = "200", description = "Alerts page", content = @Content(schema = @Schema(implementation = Alert.AlertPage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @JsonView(Alert.View.Public.class)
    public Response find(
            @PathParam("projectId") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("sorting") String sorting,
            @QueryParam("filters") String filters) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding alerts by project '{}', page '{}', size '{}', workspaceId '{}'", projectId, page, size,
                workspaceId);

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);
        var alertFilters = filtersFactory.newFilters(filters, AlertFilter.LIST_TYPE_REFERENCE);
        Alert.AlertPage alertPage = alertService.find(page, size, sortingFields, alertFilters, projectId);

        log.info("Found alerts by project '{}', count '{}', workspaceId '{}'", projectId, alertPage.size(),
                workspaceId);

        return Response.ok(alertPage).build();
    }
}
