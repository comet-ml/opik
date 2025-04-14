package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.attachment.Attachment;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.AttachmentSearchCriteria;
import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.DeleteAttachmentsRequest;
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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
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

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_QUERY_PARAM;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Path("/v1/private/attachment")
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
    @Produces(MediaType.APPLICATION_JSON)
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
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "completeMultiPartUpload", summary = "Complete multipart attachment upload", description = "Complete multipart attachment upload", responses = {
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
    @Produces(MediaType.APPLICATION_JSON)
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

        log.info("Completed upload attachment for file '{}', on workspace_id '{}' for user '{}'", fileName,
                workspaceId, userName);

        return Response.noContent().build();
    }

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "attachmentList", summary = "Attachments list for entity", description = "Attachments list for entity", responses = {
            @ApiResponse(responseCode = "200", description = "Attachment Resource", content = @Content(schema = @Schema(implementation = Attachment.AttachmentPage.class))),
            @ApiResponse(responseCode = "401", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response attachmentList(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("100") int size,
            @QueryParam("project_id") @NotNull UUID projectId,
            @QueryParam("entity_type") @NotNull EntityType entityType,
            @QueryParam("entity_id") @NotNull UUID entityId,
            @QueryParam("path") @NotBlank String path) {

        String workspaceId = requestContext.get().getWorkspaceId();
        var searchCriteria = AttachmentSearchCriteria.builder()
                .containerId(projectId)
                .entityType(entityType)
                .entityId(entityId)
                .build();

        log.info("Attachment list for workspace_id '{}', by '{}'", workspaceId, searchCriteria);

        var attachmentPage = attachmentService.list(page, size, searchCriteria, path)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Found '{}' attachments for workspace_id '{}', by '{}'", attachmentPage.size(), workspaceId,
                searchCriteria);

        return Response.ok(attachmentPage).build();
    }

    @GET
    @Path("/download")
    @Produces("*/*")
    @Operation(operationId = "downloadAttachment", summary = "Download attachment from MinIO", description = "Download attachment from MinIO", responses = {
            @ApiResponse(responseCode = "200", description = "Attachment Resource", content = @Content(schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "401", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response downloadAttachment(
            @QueryParam(WORKSPACE_QUERY_PARAM) String workspaceName,
            @QueryParam("container_id") @NotNull UUID containerId,
            @QueryParam("entity_type") @NotNull EntityType entityType,
            @QueryParam("entity_id") @NotNull UUID entityId,
            @QueryParam("file_name") @NotBlank String fileName,
            @QueryParam("mime_type") @NotBlank String mimeType) {

        String workspaceId = requestContext.get().getWorkspaceId();

        AttachmentInfo attachmentInfo = AttachmentInfo.builder()
                .fileName(fileName)
                .containerId(containerId)
                .entityType(entityType)
                .entityId(entityId)
                .mimeType(mimeType)
                .build();

        log.info("Download attachment for workspace_id '{}' attach,ent info '{}'", workspaceId, attachmentInfo);

        InputStream file = attachmentService.downloadAttachment(attachmentInfo, workspaceId);

        log.info("Completed download attachment for workspace_id '{}' attachment info '{}'", workspaceId,
                attachmentInfo);

        return Response.ok(file)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .header("Content-Type", mimeType)
                .build();
    }

    @POST
    @Path("/delete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "deleteAttachments", summary = "Delete attachments", description = "Delete attachments", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "401", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response deleteAttachments(
            @RequestBody(content = @Content(schema = @Schema(implementation = CompleteMultipartUploadRequest.class))) @Valid DeleteAttachmentsRequest request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Delete attachments on workspace_id '{}' with request '{}'", workspaceId, request);

        attachmentService.delete(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Complete Delete attachments on workspace_id '{}' with request '{}'", workspaceId, request);

        return Response.noContent().build();
    }
}
