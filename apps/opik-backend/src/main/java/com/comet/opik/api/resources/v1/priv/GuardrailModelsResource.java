package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.GuardrailModel;
import com.comet.opik.api.GuardrailModel.GuardrailModelPage;
import com.comet.opik.api.GuardrailModelUpdate;
import com.comet.opik.api.GuardrailTrainingRun;
import com.comet.opik.api.GuardrailTrainingRun.GuardrailTrainingRunPage;
import com.comet.opik.api.GuardrailTrainingRunStatus;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.SortingFactoryGuardrailModels;
import com.comet.opik.domain.GuardrailTrainingDataset;
import com.comet.opik.domain.GuardrailsAccess;
import com.comet.opik.infrastructure.FeatureFlags;
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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.glassfish.jersey.media.multipart.FormDataParam;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * The guardrail models a workspace trains on its own labelled data, and their training runs. Behind the training
 * toggle and the same organization gate as every guardrails endpoint.
 *
 * <p>Training data arrives as a multipart JSONL file and is validated whole before anything is created.</p>
 *
 * <p>The services behind the endpoints are not wired in yet: past the gates and validation, every request is a 501
 * until they are.</p>
 */
@Path("/v1/private/guardrails/models")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Guardrail models", description = "Guardrail models a workspace trains on its own labelled data")
public class GuardrailModelsResource {

    private static final String FORBIDDEN = "The caller lacks the workspace settings permission, or guardrail model training is not enabled for this deployment, or guardrails are not enabled for the workspace's organization";
    private static final String FORBIDDEN_READ = "Guardrail model training is not enabled for this deployment, or guardrails are not enabled for the workspace's organization";
    private static final String UNAVAILABLE = "Could not verify with the platform that guardrails are enabled for the workspace's organization; retry later";
    private static final String NOT_WIRED = "Guardrail models are not available yet";
    private static final String FILE_DESCRIPTION = "JSON Lines in UTF-8, one {\"text\": \"...\", \"label\": \"yes\" | \"no\"} object per line, yes meaning the text violates: 20 to 50,000 rows, at least 5 per label, up to 12,000 characters each, 50 MB";

    private final @NonNull GuardrailsAccess guardrailsAccess;
    private final @NonNull FeatureFlags featureFlags;
    private final @NonNull SortingFactoryGuardrailModels sortingFactory;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(operationId = "createGuardrailModel", summary = "Create guardrail model", description = "Create a guardrail model and start its first training run from a labelled JSONL file", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/guardrails/models/{id}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "400", description = "Bad Request: the training data's problems, each line's with its number", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN, content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "A model with this name already exists", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "503", description = UNAVAILABLE, content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))})
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.WORKSPACE_SETTINGS_CONFIGURE)
    public Response createGuardrailModel(
            // Dropwizard reports a form field's violation as "The request body ...", without its name, so each message
            // names its field.
            @FormDataParam("name") @NotBlank(message = "name must not be blank") @Size(max = GuardrailModel.NAME_MAX_LENGTH, message = "name cannot exceed {max} characters") String name,
            @FormDataParam("description") @NotBlank(message = "description must not be blank") @Size(max = GuardrailModel.DESCRIPTION_MAX_LENGTH, message = "description cannot exceed {max} characters") String description,
            @FormDataParam("file") @NotNull @Schema(description = FILE_DESCRIPTION) InputStream file) {
        checkAccess();

        GuardrailTrainingDataset.validate(file);
        throw notWired();
    }

    @GET
    @Operation(operationId = "findGuardrailModels", summary = "Find guardrail models", description = "Find the workspace's guardrail models, each with its serving run and its latest run", responses = {
            @ApiResponse(responseCode = "200", description = "Guardrail model page", content = @Content(schema = @Schema(implementation = GuardrailModelPage.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN_READ, content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "503", description = UNAVAILABLE, content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))})
    public Response findGuardrailModels(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("name") @Schema(description = "Filter models by name (partial match, case insensitive)") String name,
            @QueryParam("usable_only") @DefaultValue("false") @Schema(description = "Only the models with a serving run, the ones a rule can use") boolean usableOnly,
            @QueryParam("latest_run_status") @Schema(description = "Filter models by their latest run's status") GuardrailTrainingRunStatus latestRunStatus,
            @QueryParam("sorting") String sorting) {
        checkAccess();

        sortingFactory.newSorting(sorting);
        throw notWired();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getGuardrailModelById", summary = "Get guardrail model by id", description = "Get a guardrail model with its serving run and its latest run", responses = {
            @ApiResponse(responseCode = "200", description = "Guardrail model", content = @Content(schema = @Schema(implementation = GuardrailModel.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN_READ, content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Guardrail model not found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "503", description = UNAVAILABLE, content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))})
    public Response getGuardrailModelById(@PathParam("id") UUID id) {
        checkAccess();

        throw notWired();
    }

    @PATCH
    @Path("/{id}")
    @Operation(operationId = "updateGuardrailModel", summary = "Update guardrail model", description = "Update a guardrail model's description; its name never changes", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN, content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Guardrail model not found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "503", description = UNAVAILABLE, content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))})
    @RequiredPermissions(WorkspaceUserPermission.WORKSPACE_SETTINGS_CONFIGURE)
    public Response updateGuardrailModel(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = GuardrailModelUpdate.class))) @NotNull @Valid GuardrailModelUpdate update) {
        checkAccess();

        throw notWired();
    }

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteGuardrailModel", summary = "Delete guardrail model", description = "Delete a guardrail model and its runs", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN, content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "A policy rule uses the model", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "503", description = UNAVAILABLE, content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))})
    @RequiredPermissions(WorkspaceUserPermission.WORKSPACE_SETTINGS_CONFIGURE)
    public Response deleteGuardrailModel(@PathParam("id") UUID id) {
        checkAccess();

        throw notWired();
    }

    @POST
    @Path("/{id}/runs")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(operationId = "retrainGuardrailModel", summary = "Retrain guardrail model", description = "Start a new training of the model from a labelled JSONL file; the serving run keeps serving until the new one completes", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/guardrails/models/{id}/runs/{runId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "400", description = "Bad Request: the training data's problems, each line's with its number", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN, content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Guardrail model not found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "The model has a run in flight", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "503", description = UNAVAILABLE, content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))})
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.WORKSPACE_SETTINGS_CONFIGURE)
    public Response retrainGuardrailModel(@PathParam("id") UUID id,
            @FormDataParam("file") @NotNull @Schema(description = FILE_DESCRIPTION) InputStream file) {
        checkAccess();

        GuardrailTrainingDataset.validate(file);
        throw notWired();
    }

    @GET
    @Path("/{id}/runs")
    @Operation(operationId = "findGuardrailTrainingRuns", summary = "Find guardrail model training runs", description = "Find a guardrail model's training runs, newest first", responses = {
            @ApiResponse(responseCode = "200", description = "Training run page", content = @Content(schema = @Schema(implementation = GuardrailTrainingRunPage.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN_READ, content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Guardrail model not found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "503", description = UNAVAILABLE, content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))})
    public Response findGuardrailTrainingRuns(@PathParam("id") UUID id,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {
        checkAccess();

        throw notWired();
    }

    @GET
    @Path("/{id}/runs/{runId}")
    @Operation(operationId = "getGuardrailTrainingRunById", summary = "Get guardrail model training run by id", description = "Get one training run of a guardrail model", responses = {
            @ApiResponse(responseCode = "200", description = "Training run", content = @Content(schema = @Schema(implementation = GuardrailTrainingRun.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN_READ, content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Training run not found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "503", description = UNAVAILABLE, content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))})
    public Response getGuardrailTrainingRunById(@PathParam("id") UUID id, @PathParam("runId") UUID runId) {
        checkAccess();

        throw notWired();
    }

    @POST
    @Path("/{id}/runs/{runId}/cancel")
    @Operation(operationId = "cancelGuardrailTrainingRun", summary = "Cancel guardrail model training run", description = "Cancel a training run that is queued or running. Cancelling a cancelled run again changes nothing and answers 204 too", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN, content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Training run not found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "The run has already completed or failed", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "503", description = UNAVAILABLE, content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))})
    @RequiredPermissions(WorkspaceUserPermission.WORKSPACE_SETTINGS_CONFIGURE)
    public Response cancelGuardrailTrainingRun(@PathParam("id") UUID id, @PathParam("runId") UUID runId) {
        checkAccess();

        throw notWired();
    }

    /** The deployment trains guardrail models, and the workspace's organization has guardrails. */
    private void checkAccess() {
        featureFlags.checkGuardrailsTrainingEnabled();
        guardrailsAccess.check();
    }

    private static ServerErrorException notWired() {
        return new ServerErrorException(Response.status(Response.Status.NOT_IMPLEMENTED)
                .entity(new ErrorMessage(List.of(NOT_WIRED)))
                .build());
    }
}
