package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.AttachmentInfoHolder;
import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadResponse;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.Tika;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;

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
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AttachmentServiceImpl implements AttachmentService {

    private final @NonNull FileUploadService fileUploadService;
    private final @NonNull PreSignerService preSignerService;
    private final @NonNull AttachmentDAO attachmentDAO;
    private final @NonNull ProjectService projectService;
    private static final Tika tika = new Tika();

    @Override
    public StartMultipartUploadResponse startMultiPartUpload(StartMultipartUploadRequest startUploadRequest,
            String workspaceId, String userName) {
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
        UUID projectId = getProjectIdByName(completeUploadRequest.projectName(), workspaceId, userName);
        String key = prepareKey(completeUploadRequest, workspaceId, projectId);
        fileUploadService.completeMultipartUpload(key,
                completeUploadRequest.uploadId(), completeUploadRequest.uploadedFileParts());

        attachmentDAO.addAttachment(completeUploadRequest, projectId, getMimeType(completeUploadRequest))
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
}
