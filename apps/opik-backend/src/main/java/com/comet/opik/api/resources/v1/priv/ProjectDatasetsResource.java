package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.filter.DatasetFilter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.DatasetCriteria;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.fasterxml.jackson.annotation.JsonView;
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

import static com.comet.opik.api.Dataset.DatasetPage;

@Path("/v1/private/projects/{projectId}/datasets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Projects", description = "Project related resources")
public class ProjectDatasetsResource {

    private final @NonNull DatasetService service;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull FiltersFactory filtersFactory;
    private final @NonNull SortingFactoryDatasets sortingFactory;

    @GET
    @Operation(operationId = "findDatasetsByProject", summary = "Find datasets by project", description = "Find datasets scoped to a project", responses = {
            @ApiResponse(responseCode = "200", description = "Dataset page", content = @Content(schema = @Schema(implementation = DatasetPage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.DATASET_VIEW)
    @JsonView(Dataset.View.Public.class)
    public Response findDatasets(
            @PathParam("projectId") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("with_experiments_only") boolean withExperimentsOnly,
            @QueryParam("with_optimizations_only") boolean withOptimizationsOnly,
            @QueryParam("name") @Schema(description = "Filter datasets by name (partial match, case insensitive)") String name,
            @QueryParam("sorting") String sorting,
            @QueryParam("filters") String filters) {

        String workspaceId = requestContext.get().getWorkspaceId();

        var queryFilters = filtersFactory.newFilters(filters, DatasetFilter.LIST_TYPE_REFERENCE);
        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);

        var criteria = DatasetCriteria.builder()
                .name(name)
                .withExperimentsOnly(withExperimentsOnly)
                .withOptimizationsOnly(withOptimizationsOnly)
                .projectId(projectId)
                .filters(queryFilters)
                .build();

        log.info("Finding datasets by project '{}' on workspaceId '{}'", projectId, workspaceId);
        DatasetPage datasetPage = service.find(page, size, criteria, sortingFields);
        log.info("Found datasets by project '{}', count '{}' on workspaceId '{}'", projectId, datasetPage.size(),
                workspaceId);

        return Response.ok(datasetPage).build();
    }

}
