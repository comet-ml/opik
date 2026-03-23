package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.filter.OptimizationFilter;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.OptimizationSearchCriteria;
import com.comet.opik.domain.OptimizationService;
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

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/projects/{projectId}/optimizations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Projects", description = "Project related resources")
public class ProjectOptimizationsResource {

    private final @NonNull OptimizationService optimizationService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull FiltersFactory filtersFactory;

    @GET
    @Operation(operationId = "findOptimizationsByProject", summary = "Find optimizations by project", description = "Find optimizations scoped to a project", responses = {
            @ApiResponse(responseCode = "200", description = "Optimizations page", content = @Content(schema = @Schema(implementation = Optimization.OptimizationPage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @JsonView(Optimization.View.Public.class)
    public Response find(
            @PathParam("projectId") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("dataset_id") UUID datasetId,
            @QueryParam("dataset_name") String datasetName,
            @QueryParam("name") @Schema(description = "Filter optimizations by name (partial match, case insensitive)") String name,
            @QueryParam("dataset_deleted") Boolean datasetDeleted,
            @QueryParam("filters") String filters) {

        List<OptimizationFilter> parsedFilters = (List<OptimizationFilter>) filtersFactory.newFilters(filters,
                OptimizationFilter.LIST_TYPE_REFERENCE);

        var searchCriteria = OptimizationSearchCriteria.builder()
                .datasetId(datasetId)
                .datasetName(datasetName)
                .name(name)
                .datasetDeleted(datasetDeleted)
                .projectId(projectId)
                .filters(parsedFilters)
                .entityType(EntityType.TRACE)
                .build();

        log.info("Finding optimizations by project '{}', page '{}', size '{}'", projectId, page, size);
        var optimizations = optimizationService.find(page, size, searchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Found optimizations by project '{}', count '{}', page '{}', size '{}'",
                projectId, optimizations.size(), page, size);
        return Response.ok().entity(optimizations).build();
    }
}
