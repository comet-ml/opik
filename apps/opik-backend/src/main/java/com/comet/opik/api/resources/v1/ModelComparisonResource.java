package com.comet.opik.api.resources.v1;

import com.comet.opik.api.ModelComparison;
import com.comet.opik.domain.ModelComparisonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

@Path("/api/v1/model-comparisons")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor(onConstructor_ = @__({@Inject}))
@Tag(name = "Model Comparisons", description = "API for managing and analyzing model comparisons")
@Slf4j
public class ModelComparisonResource {

    private final @NonNull ModelComparisonService modelComparisonService;

    @GET
    @Operation(
            summary = "Get model comparisons",
            description = "Retrieve a paginated list of model comparisons with optional filtering"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Model comparisons retrieved successfully",
            content = @Content(schema = @Schema(implementation = ModelComparison.ModelComparisonPage.class))
    )
    public Response getModelComparisons(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("sorting") String sorting,
            @QueryParam("search") String search
    ) {
        log.info("Retrieving model comparisons - page: '{}', size: '{}', search: '{}'", page, size, search);
        
        var comparisons = modelComparisonService.getModelComparisons(page, size, sorting, search);
        return Response.ok(comparisons).build();
    }

    @POST
    @Operation(
            summary = "Create model comparison",
            description = "Create a new model comparison analysis"
    )
    @ApiResponse(
            responseCode = "201",
            description = "Model comparison created successfully",
            content = @Content(schema = @Schema(implementation = ModelComparison.class))
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input data"
    )
    public Response createModelComparison(@Valid ModelComparison request) {
        log.info("Creating model comparison: '{}'", request.name());
        
        var comparison = modelComparisonService.createModelComparison(request);
        return Response.status(Response.Status.CREATED)
                .entity(comparison)
                .build();
    }

    @GET
    @Path("/{id}")
    @Operation(
            summary = "Get model comparison by ID",
            description = "Retrieve a specific model comparison with detailed results"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Model comparison retrieved successfully",
            content = @Content(schema = @Schema(implementation = ModelComparison.class))
    )
    @ApiResponse(
            responseCode = "404",
            description = "Model comparison not found"
    )
    public Response getModelComparison(
            @PathParam("id") @Parameter(description = "Model comparison ID") UUID id
    ) {
        log.info("Retrieving model comparison: '{}'", id);
        
        var comparison = modelComparisonService.getModelComparison(id);
        return Response.ok(comparison).build();
    }

    @PUT
    @Path("/{id}")
    @Operation(
            summary = "Update model comparison",
            description = "Update an existing model comparison"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Model comparison updated successfully",
            content = @Content(schema = @Schema(implementation = ModelComparison.class))
    )
    @ApiResponse(
            responseCode = "404",
            description = "Model comparison not found"
    )
    public Response updateModelComparison(
            @PathParam("id") @Parameter(description = "Model comparison ID") UUID id,
            @Valid ModelComparison request
    ) {
        log.info("Updating model comparison: '{}'", id);
        
        var comparison = modelComparisonService.updateModelComparison(id, request);
        return Response.ok(comparison).build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(
            summary = "Delete model comparison",
            description = "Delete a model comparison"
    )
    @ApiResponse(
            responseCode = "204",
            description = "Model comparison deleted successfully"
    )
    @ApiResponse(
            responseCode = "404",
            description = "Model comparison not found"
    )
    public Response deleteModelComparison(
            @PathParam("id") @Parameter(description = "Model comparison ID") UUID id
    ) {
        log.info("Deleting model comparison: '{}'", id);
        
        modelComparisonService.deleteModelComparison(id);
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/analyze")
    @Operation(
            summary = "Run model comparison analysis",
            description = "Execute analysis for a model comparison to generate results"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Analysis completed successfully",
            content = @Content(schema = @Schema(implementation = ModelComparison.class))
    )
    @ApiResponse(
            responseCode = "404",
            description = "Model comparison not found"
    )
    public Response runAnalysis(
            @PathParam("id") @Parameter(description = "Model comparison ID") UUID id
    ) {
        log.info("Running analysis for model comparison: '{}'", id);
        
        var comparison = modelComparisonService.runAnalysis(id);
        return Response.ok(comparison).build();
    }

    @GET
    @Path("/available-models")
    @Operation(
            summary = "Get available models for comparison",
            description = "Retrieve list of models that can be used in comparisons"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Available models retrieved successfully"
    )
    public Response getAvailableModels() {
        log.info("Retrieving available models for comparison");
        
        var models = modelComparisonService.getAvailableModels();
        return Response.ok(models).build();
    }

    @GET
    @Path("/available-datasets")
    @Operation(
            summary = "Get available datasets for comparison",
            description = "Retrieve list of datasets that can be used in comparisons"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Available datasets retrieved successfully"
    )
    public Response getAvailableDatasets() {
        log.info("Retrieving available datasets for comparison");
        
        var datasets = modelComparisonService.getAvailableDatasets();
        return Response.ok(datasets).build();
    }

    @GET
    @Path("/{id}/export")
    @Operation(
            summary = "Export model comparison results",
            description = "Export model comparison results in various formats (JSON, CSV)"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Export completed successfully"
    )
    @ApiResponse(
            responseCode = "404",
            description = "Model comparison not found"
    )
    public Response exportResults(
            @PathParam("id") @Parameter(description = "Model comparison ID") UUID id,
            @QueryParam("format") @DefaultValue("json") String format
    ) {
        log.info("Exporting model comparison results: '{}', format: '{}'", id, format);
        
        var exportData = modelComparisonService.exportResults(id, format);
        return Response.ok(exportData).build();
    }
}