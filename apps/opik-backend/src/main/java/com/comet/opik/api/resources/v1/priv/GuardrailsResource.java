package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.GuardrailBatch;
import com.comet.opik.domain.GuardrailsService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.comet.opik.utils.AsyncUtils;
import com.fasterxml.jackson.annotation.JsonView;
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
                .retryWhen(AsyncUtils.handleConnectionError())
                .block();

        log.info("Done adding guardrails batch, size {} on  workspaceId '{}'", batch.guardrails().size(),
                workspaceId);

        return Response.noContent().build();
    }
}
