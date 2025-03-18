package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadResponse;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/private/attachment")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Attachments", description = "Attachments related resources")
public class AttachmentResource {

    private final @NonNull AttachmentService attachmentService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/upload-start")
    @Operation(operationId = "startMultiPartUpload", summary = "Start multipart attachment upload", description = "Start multipart attachment upload", responses = {
            @ApiResponse(responseCode = "200", description = "MultipartUploadResponse", content = @Content(schema = @Schema(implementation = StartMultipartUploadResponse.class))),
            @ApiResponse(responseCode = "401", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response startMultiPartUpload(
            @RequestBody(content = @Content(schema = @Schema(implementation = StartMultipartUploadRequest.class))) @Valid StartMultipartUploadRequest request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        log.info("Start MultiPart Upload for file '{}', on workspace_id '{}' for user '{}'", request.fileName(),
                workspaceId, userName);
        StartMultipartUploadResponse response = attachmentService.startMultiPartUpload(request, workspaceId, userName);
        log.info("Start MultiPart Upload for file '{}', on workspace_id '{}' for user '{}' done", request.fileName(),
                workspaceId, userName);

        return Response.ok(response).build();
    }

    @POST
    @Path("/upload-complete")
    @Operation(operationId = "startMultiPartUpload", summary = "Start multipart attachment upload", description = "Start multipart attachment upload", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "401", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response completeMultiPartUpload(
            @RequestBody(content = @Content(schema = @Schema(implementation = CompleteMultipartUploadRequest.class))) @Valid CompleteMultipartUploadRequest request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        log.info("Complete MultiPart Upload for file '{}', on workspace_id '{}' for user '{}'", request.fileName(),
                workspaceId, userName);
        attachmentService.completeMultiPartUpload(request, workspaceId, userName);
        log.info("Complete MultiPart Upload for file '{}', on workspace_id '{}' for user '{}' done", request.fileName(),
                workspaceId, userName);

        return Response.noContent().build();
    }
}
