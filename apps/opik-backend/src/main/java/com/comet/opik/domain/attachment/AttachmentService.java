package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.AttachmentInfoHolder;
import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadResponse;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;

import java.util.List;

import static com.comet.opik.domain.attachment.AttachmentUtils.KEY_TEMPLATE;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@ImplementedBy(AttachmentServiceImpl.class)
public interface AttachmentService {
    StartMultipartUploadResponse startMultiPartUpload(StartMultipartUploadRequest request, String workspaceId);

    void completeMultiPartUpload(CompleteMultipartUploadRequest request, String workspaceId, String userName);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AttachmentServiceImpl implements AttachmentService {

    private final @NonNull FileUploadService fileUploadService;
    private final @NonNull PreSignerService preSignerService;
    private final @NonNull AttachmentDAO attachmentDAO;

    @Override
    public StartMultipartUploadResponse startMultiPartUpload(StartMultipartUploadRequest startUploadRequest,
            String workspaceId) {
        String key = prepareKey(startUploadRequest, workspaceId);

        CreateMultipartUploadResponse createResponse = fileUploadService.createMultipartUpload(key,
                startUploadRequest.mimeType());
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
        String key = prepareKey(completeUploadRequest, workspaceId);
        fileUploadService.completeMultipartUpload(key,
                completeUploadRequest.uploadId(), completeUploadRequest.uploadedFileParts());

        attachmentDAO.addAttachment(completeUploadRequest)
                .contextWrite(ctx -> setRequestContext(ctx, userName, workspaceId))
                .block();
    }

    String prepareKey(AttachmentInfoHolder infoHolder, String workspaceId) {
        return KEY_TEMPLATE.replace("{workspaceId}", workspaceId)
                .replace("{projectId}", infoHolder.containerId().toString())
                .replace("{entity_type}", infoHolder.entityType().getValue())
                .replace("{entity_id}", infoHolder.entityId().toString())
                .replace("{file_name}", infoHolder.fileName());
    }
}
