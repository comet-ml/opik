package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.AttachmentInfoHolder;
import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadResponse;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.Tika;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.domain.attachment.AttachmentUtils.KEY_TEMPLATE;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@ImplementedBy(AttachmentServiceImpl.class)
public interface AttachmentService {
    StartMultipartUploadResponse startMultiPartUpload(StartMultipartUploadRequest request, String workspaceId,
            String userName);

    void completeMultiPartUpload(CompleteMultipartUploadRequest request, String workspaceId, String userName);

    void uploadAttachment(AttachmentInfo attachmentInfo, byte[] data, String workspaceId, String userName);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AttachmentServiceImpl implements AttachmentService {

    private final @NonNull FileUploadService fileUploadService;
    private final @NonNull PreSignerService preSignerService;
    private final @NonNull AttachmentDAO attachmentDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull OpikConfiguration config;
    private static final Tika tika = new Tika();

    @Override
    public StartMultipartUploadResponse startMultiPartUpload(StartMultipartUploadRequest startUploadRequest,
            String workspaceId, String userName) {
        if (config.getS3Config().isMinIO()) {
            return prepareMinIOUploadResponse(startUploadRequest);
        }

        UUID projectId = getProjectIdByName(startUploadRequest.projectName(), workspaceId, userName);
        String key = prepareKey(startUploadRequest, workspaceId, projectId);

        CreateMultipartUploadResponse createResponse = fileUploadService.createMultipartUpload(key,
                getMimeType(startUploadRequest));
        List<String> presignedUrls = preSignerService.generatePresignedUrls(key, startUploadRequest.numOfFileParts(),
                createResponse.uploadId());

        return StartMultipartUploadResponse.builder()
                .preSignUrls(presignedUrls)
                .uploadId(createResponse.uploadId())
                .build();
    }

    @Override
    public void completeMultiPartUpload(CompleteMultipartUploadRequest completeUploadRequest, String workspaceId,
            String userName) {
        // In case of MinIO complete is not needed, file is uploaded directly via BE
        if (config.getS3Config().isMinIO()) {
            log.info("Skipping completeMultiPartUpload for MinIO");
            return;
        }

        UUID projectId = getProjectIdByName(completeUploadRequest.projectName(), workspaceId, userName);
        String key = prepareKey(completeUploadRequest, workspaceId, projectId);
        fileUploadService.completeMultipartUpload(key,
                completeUploadRequest.uploadId(), completeUploadRequest.uploadedFileParts());

        attachmentDAO
                .addAttachment(completeUploadRequest, projectId, getMimeType(completeUploadRequest),
                        completeUploadRequest.fileSize())
                .contextWrite(ctx -> setRequestContext(ctx, userName, workspaceId))
                .block();
    }

    @Override
    public void uploadAttachment(AttachmentInfo attachmentInfo, byte[] data, String workspaceId, String userName) {
        if (!config.getS3Config().isMinIO()) {
            log.warn("uploadAttachment is forbidden for S3");
            throw new ClientErrorException(
                    "Direct attachment upload is forbidden for S3, please use multi-part upload with presigned urls",
                    Response.Status.FORBIDDEN);
        }

        UUID projectId = getProjectIdByName(attachmentInfo.projectName(), workspaceId, userName);
        String key = prepareKey(attachmentInfo, workspaceId, projectId);

        fileUploadService.upload(key, data, getMimeType(attachmentInfo));

        attachmentDAO.addAttachment(attachmentInfo, projectId, getMimeType(attachmentInfo), data.length)
                .contextWrite(ctx -> setRequestContext(ctx, userName, workspaceId))
                .block();
    }

    String prepareKey(AttachmentInfoHolder infoHolder, String workspaceId, UUID projectId) {
        return KEY_TEMPLATE.replace("{workspaceId}", workspaceId)
                .replace("{projectId}", projectId.toString())
                .replace("{entity_type}", infoHolder.entityType().getValue())
                .replace("{entity_id}", infoHolder.entityId().toString())
                .replace("{file_name}", infoHolder.fileName());
    }

    private UUID getProjectIdByName(String inputProjectName, String workspaceId, String userName) {
        String projectName = WorkspaceUtils.getProjectName(inputProjectName);
        return projectService.getOrCreate(workspaceId, projectName, userName).id();
    }

    private String getMimeType(AttachmentInfoHolder infoHolder) {
        return Optional.ofNullable(infoHolder.mimeType())
                .filter(StringUtils::isNotBlank)
                .orElseGet(() -> tika.detect(infoHolder.fileName()));
    }

    private StartMultipartUploadResponse prepareMinIOUploadResponse(StartMultipartUploadRequest uploadRequest) {

        String baseUrl = new String(Base64.getUrlDecoder().decode(uploadRequest.path()));

        URI uploadUrl = UriBuilder.fromUri(baseUrl)
                .path("v1/private/attachment/upload")
                .queryParam("file_name", uploadRequest.fileName())
                .queryParam("project_name", uploadRequest.projectName())
                .queryParam("mime_type", getMimeType(uploadRequest))
                .queryParam("entity_type", uploadRequest.entityType().getValue())
                .queryParam("entity_id", uploadRequest.entityId())
                .build();

        return StartMultipartUploadResponse.builder()
                .preSignUrls(List.of(uploadUrl.toASCIIString()))
                .uploadId("BEMinIO")
                .build();
    }
}
