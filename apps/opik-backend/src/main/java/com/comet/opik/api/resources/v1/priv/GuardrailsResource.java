package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.GuardrailBatch;
import com.comet.opik.api.GuardrailsValidationRequest;
import com.comet.opik.api.GuardrailsValidationResponse;
import com.comet.opik.domain.GuardrailsService;
import com.comet.opik.domain.GuardrailsValidationService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.comet.opik.utils.RetryUtils;
import com.fasterxml.jackson.annotation.JsonView;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/guardrails")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @jakarta.inject.Inject)
@Tag(name = "Guardrails", description = "Guardrails related resources")
public class GuardrailsResource {
    private final @NonNull GuardrailsService guardrailsService;
    private final @NonNull GuardrailsValidationService guardrailsValidationService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Operation(operationId = "createGuardrails", summary = "Create guardrails for traces in a batch", description = "Batch guardrails for traces", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    @RateLimited
    public Response createGuardrails(
            @RequestBody(content = @Content(schema = @Schema(implementation = GuardrailBatch.class))) @NotNull @Valid @JsonView(Guardrail.View.Write.class) GuardrailBatch batch) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Add guardrails batch, size {} on  workspaceId '{}'", batch.guardrails().size(),
                workspaceId);

        guardrailsService.addTraceGuardrails(batch.guardrails())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();

        log.info("Done adding guardrails batch, size {} on  workspaceId '{}'", batch.guardrails().size(),
                workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/v2/validations")
    @Operation(operationId = "validateGuardrails", summary = "Run guardrail checks on a text", description = "Runs the requested checks on the text through the Opik inference decision service and returns a verdict per check. Writes nothing: the SDK reports the results to the guardrails batch endpoint, as before.", responses = {
            @ApiResponse(responseCode = "200", description = "A verdict per check", content = @Content(schema = @Schema(implementation = GuardrailsValidationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unknown credentials", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Guardrail checks are not enabled for this deployment, or not enabled for the workspace's organization", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content: blank text, or a validations list outside 1 to 32 entries", content = @Content(schema = @Schema(implementation = com.comet.opik.api.error.ErrorMessage.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    public Response validateGuardrails(
            @RequestBody(content = @Content(schema = @Schema(implementation = GuardrailsValidationRequest.class))) @NotNull @Valid GuardrailsValidationRequest request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Validating guardrails, {} checks on workspaceId '{}'", request.validations().size(), workspaceId);
        var response = guardrailsValidationService.validate(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Validated guardrails, passed '{}' on workspaceId '{}'", response.validationPassed(), workspaceId);
        return Response.ok(response).build();
    }
}
