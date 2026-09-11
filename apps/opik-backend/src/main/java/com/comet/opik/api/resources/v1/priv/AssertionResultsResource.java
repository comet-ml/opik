package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AssertionResultBatch;
import com.comet.opik.domain.AssertionResultService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.comet.opik.utils.RetryUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/assertion-results")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Assertion Results", description = "Assertion result related resources")
public class AssertionResultsResource {

    private final @NonNull AssertionResultService assertionResultService;
    private final @NonNull Provider<RequestContext> requestContext;

    @PUT
    @Operation(operationId = "storeAssertionsBatch", summary = "Batch ingestion of assertion results", description = "Batch ingestion of assertion results for traces or spans", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    @RateLimited
    public Response storeAssertionsBatch(
            @RequestBody(content = @Content(schema = @Schema(implementation = AssertionResultBatch.class))) @NotNull @Valid AssertionResultBatch batch) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Assertion results batch for entityType '{}', size '{}' on workspaceId '{}'",
                batch.entityType(), batch.assertionResults().size(), workspaceId);

        assertionResultService.saveBatch(batch.entityType(), batch.assertionResults())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();

        log.info("Saved assertion results batch for entityType '{}', size '{}' on workspaceId '{}'",
                batch.entityType(), batch.assertionResults().size(), workspaceId);

        return Response.noContent().build();
    }
}
