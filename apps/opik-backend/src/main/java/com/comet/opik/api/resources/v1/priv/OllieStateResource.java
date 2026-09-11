package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.ollie.OllieStateService;
import com.comet.opik.infrastructure.OllieStateConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;
import java.io.InputStream;

@Path("/v1/private/ollie/state")
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Ollie State", description = "Ollie pod state persistence")
public class OllieStateResource {

    private static final String OLLIE_STATE_UPLOAD = "ollieStateUpload";

    private final @NonNull OllieStateService ollieStateService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull @Config("ollieStateConfig") OllieStateConfig ollieStateConfig;

    @PUT
    @Consumes("application/gzip")
    @Produces(MediaType.APPLICATION_JSON)
    @RateLimited(value = OLLIE_STATE_UPLOAD
            + ":{apiKey}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    @Operation(operationId = "replaceOllieState", summary = "Replace ollie state", description = "Upload gzip-compressed SQLite DB file, replacing any existing state", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response upload(@HeaderParam(HttpHeaders.CONTENT_LENGTH) Long contentLength,
            InputStream inputStream) throws IOException {
        int maxSize = ollieStateConfig.getMaxUploadSizeBytes();
        if (contentLength != null && contentLength > maxSize) {
            throw new BadRequestException("Upload exceeds maximum size of %d bytes".formatted(maxSize));
        }

        String userName = requestContext.get().getUserName();
        log.info("Upload ollie state for user '{}'", userName);

        ollieStateService.upload(userName, inputStream);

        log.info("Completed upload ollie state for user '{}'", userName);
        return Response.noContent().build();
    }

    @GET
    @Produces("application/gzip")
    @Operation(operationId = "downloadOllieState", summary = "Download ollie state", description = "Download stored ollie state file", responses = {
            @ApiResponse(responseCode = "200", description = "Ollie state file", content = @Content(mediaType = "application/gzip", schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response download() {
        String userName = requestContext.get().getUserName();
        log.info("Download ollie state for user '{}'", userName);

        InputStream file = ollieStateService.download(userName);

        log.info("Returning download stream for ollie state for user '{}'", userName);
        return Response.ok(file)
                .header("Content-Disposition", "attachment; filename=\"ollie.db.gz\"")
                .header("Content-Type", "application/gzip")
                .build();
    }

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "deleteOllieState", summary = "Delete ollie state", description = "Clear stored ollie state", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response delete() {
        String userName = requestContext.get().getUserName();
        log.info("Delete ollie state for user '{}'", userName);

        ollieStateService.delete(userName);

        log.info("Completed delete ollie state for user '{}'", userName);
        return Response.noContent().build();
    }
}
