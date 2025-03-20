package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.EntityType;
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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Path("/v1/private/attachment")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Attachments", description = "Attachments related resources")
public class AttachmentResource {

    private final @NonNull AttachmentService attachmentService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/upload-start")
    @Consumes(MediaType.APPLICATION_JSON)
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
    @Consumes(MediaType.APPLICATION_JSON)
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

    @PUT
    @Path("/upload")
    @Consumes("*/*")
    @Operation(operationId = "uploadAttachment", summary = "Upload attachment to MinIO", description = "Upload attachment to MinIO", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "401", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response uploadAttachment(
            InputStream inputStream,
            @QueryParam("file_name") @NotNull String fileName,
            @QueryParam("project_name") @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "If null, the default project is used") String projectName,
            @QueryParam("mime_type") String mimeType,
            @QueryParam("entity_type") @NotNull EntityType entityType,
            @QueryParam("entity_id") @NotNull UUID entityId) throws IOException {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Upload attachment for file '{}', on workspace_id '{}' for user '{}'", fileName,
                workspaceId, userName);

        AttachmentInfo attachmentInfo = AttachmentInfo.builder()
                .fileName(fileName)
                .projectName(projectName)
                .entityType(entityType)
                .entityId(entityId)
                .mimeType(mimeType)
                .build();
        attachmentService.uploadAttachment(attachmentInfo, IOUtils.toByteArray(inputStream), workspaceId, userName);

        log.info("Upload attachment for file '{}', on workspace_id '{}' for user '{}' done", fileName,
                workspaceId, userName);

        return Response.noContent().build();
    }
}
