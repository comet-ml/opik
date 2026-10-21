package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.MediaUrlCheck;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.MediaUrlCheckService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/private/media-urls")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Media URLs", description = "Media URL related resources")
public class MediaUrlsResource {

    private final @NonNull MediaUrlCheckService mediaUrlCheckService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/check")
    @RateLimited
    @Operation(operationId = "checkMediaUrls", summary = "Check media URLs", description = "Requests the first byte of each URL from the backend and reports whether it answers with an image, video or audio file. "
            + "Response bodies are never returned. data: URIs are skipped.", responses = {
                    @ApiResponse(responseCode = "200", description = "One result per URL, in request order", content = @Content(schema = @Schema(implementation = MediaUrlCheck.Results.class))),
                    @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
            })
    public Response check(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = MediaUrlCheck.class))) @Valid MediaUrlCheck mediaUrlCheck) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Checking media URLs, count '{}', workspaceId '{}'", mediaUrlCheck.urls().size(), workspaceId);
        var results = mediaUrlCheckService.check(mediaUrlCheck.urls());
        log.info("Checked media URLs, count '{}', not ok '{}', workspaceId '{}'", results.size(),
                results.stream().filter(result -> result.status() != MediaUrlCheck.Status.OK).count(), workspaceId);

        return Response.ok(MediaUrlCheck.Results.builder().results(results).build()).build();
    }
}
