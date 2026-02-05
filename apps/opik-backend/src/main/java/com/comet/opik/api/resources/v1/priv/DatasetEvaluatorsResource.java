package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.DatasetEvaluator;
import com.comet.opik.api.DatasetEvaluator.DatasetEvaluatorBatchRequest;
import com.comet.opik.api.DatasetEvaluator.DatasetEvaluatorPage;
import com.comet.opik.domain.DatasetEvaluatorService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
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
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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

@Path("/v1/private/datasets/{datasetId}/evaluators")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Dataset Evaluators", description = "Dataset Evaluator resources")
public class DatasetEvaluatorsResource {

    private final @NonNull DatasetEvaluatorService service;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/batch")
    @Operation(operationId = "createDatasetEvaluatorsBatch", summary = "Create dataset evaluators in batch", description = "Create multiple dataset evaluators for a dataset", responses = {
            @ApiResponse(responseCode = "200", description = "Created evaluators", content = @Content(schema = @Schema(implementation = DatasetEvaluator[].class)))
    })
    @RateLimited
    @JsonView(DatasetEvaluator.View.Public.class)
    public Response createBatch(
            @PathParam("datasetId") UUID datasetId,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetEvaluatorBatchRequest.class))) @NotNull @Valid DatasetEvaluatorBatchRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating {} dataset evaluators for dataset '{}' on workspaceId '{}'",
                request.evaluators().size(), datasetId, workspaceId);

        List<DatasetEvaluator> created = service.createBatch(datasetId, request);

        log.info("Created {} dataset evaluators for dataset '{}' on workspaceId '{}'",
                created.size(), datasetId, workspaceId);

        return Response.ok(created).build();
    }

    @GET
    @Operation(operationId = "getDatasetEvaluators", summary = "Get dataset evaluators", description = "Get paginated list of evaluators for a dataset", responses = {
            @ApiResponse(responseCode = "200", description = "Dataset evaluators page", content = @Content(schema = @Schema(implementation = DatasetEvaluatorPage.class)))
    })
    @JsonView(DatasetEvaluator.View.Public.class)
    public Response getEvaluators(
            @PathParam("datasetId") UUID datasetId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting dataset evaluators for dataset '{}' on workspaceId '{}', page={}, size={}",
                datasetId, workspaceId, page, size);

        DatasetEvaluatorPage result = service.getByDatasetId(datasetId, page - 1, size);

        log.info("Found {} dataset evaluators for dataset '{}' on workspaceId '{}'",
                result.total(), datasetId, workspaceId);

        return Response.ok(result).build();
    }

    @POST
    @Path("/delete-batch")
    @Operation(operationId = "deleteDatasetEvaluatorsBatch", summary = "Delete dataset evaluators in batch", description = "Delete multiple dataset evaluators by their IDs", responses = {
            @ApiResponse(responseCode = "204", description = "No content")
    })
    public Response deleteBatch(
            @PathParam("datasetId") UUID datasetId,
            @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @NotNull @Valid BatchDelete request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting {} dataset evaluators for dataset '{}' on workspaceId '{}'",
                request.ids().size(), datasetId, workspaceId);

        service.deleteBatch(request.ids());

        log.info("Deleted dataset evaluators for dataset '{}' on workspaceId '{}'",
                datasetId, workspaceId);

        return Response.noContent().build();
    }
}
