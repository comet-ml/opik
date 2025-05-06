package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.Attachment;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.AttachmentInfoHolder;
import com.comet.opik.api.attachment.AttachmentSearchCriteria;
import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.DeleteAttachmentsRequest;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadResponse;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.Tika;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.domain.attachment.AttachmentUtils.KEY_TEMPLATE;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@ImplementedBy(AttachmentServiceImpl.class)
public interface AttachmentService {
    StartMultipartUploadResponse startMultiPartUpload(StartMultipartUploadRequest request, String workspaceId,
            String userName);

    void completeMultiPartUpload(CompleteMultipartUploadRequest request, String workspaceId, String userName);

    void uploadAttachment(AttachmentInfo attachmentInfo, byte[] data, String workspaceId, String userName);

    InputStream downloadAttachment(AttachmentInfo attachmentInfo, String workspaceId);

    Mono<Attachment.AttachmentPage> list(int page, int size, AttachmentSearchCriteria criteria, String baseUrlEncoded);

    Mono<Long> delete(DeleteAttachmentsRequest request);

    Mono<Long> deleteByEntityIds(EntityType entityType, Set<UUID> entityIds);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AttachmentServiceImpl implements AttachmentService {

    private final @NonNull FileService fileService;
    private final @NonNull PreSignerService preSignerService;
    private final @NonNull AttachmentDAO attachmentDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull OpikConfiguration config;
    private final @NonNull Provider<RequestContext> requestContext;
    private static final Tika tika = new Tika();

    @Override
    public StartMultipartUploadResponse startMultiPartUpload(@NonNull StartMultipartUploadRequest startUploadRequest,
            @NonNull String workspaceId, @NonNull String userName) {
        if (config.getS3Config().isMinIO()) {
            return prepareMinIOUploadResponse(startUploadRequest);
        }

        startUploadRequest = startUploadRequest.toBuilder()
                .containerId(getProjectIdByName(startUploadRequest.projectName(), workspaceId, userName))
                .build();
        String key = prepareKey(startUploadRequest, workspaceId);

        CreateMultipartUploadResponse createResponse = fileService.createMultipartUpload(key,
                getMimeType(startUploadRequest));
        List<String> presignedUrls = preSignerService.generatePresignedUrls(key, startUploadRequest.numOfFileParts(),
                createResponse.uploadId());

        return StartMultipartUploadResponse.builder()
                .preSignUrls(presignedUrls)
                .uploadId(createResponse.uploadId())
                .build();
    }

    @Override
    public void completeMultiPartUpload(@NonNull CompleteMultipartUploadRequest completeUploadRequest,
            @NonNull String workspaceId,
            @NonNull String userName) {
        // In case of MinIO complete is not needed, file is uploaded directly via BE
        if (config.getS3Config().isMinIO()) {
            log.info("Skipping completeMultiPartUpload for MinIO");
            return;
        }

        completeUploadRequest = completeUploadRequest.toBuilder()
                .containerId(getProjectIdByName(completeUploadRequest.projectName(), workspaceId, userName))
                .build();
        String key = prepareKey(completeUploadRequest, workspaceId);
        fileService.completeMultipartUpload(key,
                completeUploadRequest.uploadId(), completeUploadRequest.uploadedFileParts());

        attachmentDAO
                .addAttachment(completeUploadRequest, getMimeType(completeUploadRequest),
                        completeUploadRequest.fileSize())
                .contextWrite(ctx -> setRequestContext(ctx, userName, workspaceId))
                .block();
    }

    @Override
    public void uploadAttachment(@NonNull AttachmentInfo attachmentInfo, byte[] data, @NonNull String workspaceId,
            @NonNull String userName) {
        if (!config.getS3Config().isMinIO()) {
            log.warn("uploadAttachment is forbidden for S3");
            throw new ClientErrorException(
                    "Direct attachment upload is forbidden for S3, please use multi-part upload with presigned urls",
                    Response.Status.FORBIDDEN);
        }

        attachmentInfo = attachmentInfo.toBuilder()
                .containerId(getProjectIdByName(attachmentInfo.projectName(), workspaceId, userName))
                .build();
        String key = prepareKey(attachmentInfo, workspaceId);

        fileService.upload(key, data, getMimeType(attachmentInfo));

        attachmentDAO.addAttachment(attachmentInfo, getMimeType(attachmentInfo), data.length)
                .contextWrite(ctx -> setRequestContext(ctx, userName, workspaceId))
                .block();
    }

    @Override
    public InputStream downloadAttachment(@NonNull AttachmentInfo attachmentInfo, @NonNull String workspaceId) {
        if (!config.getS3Config().isMinIO()) {
            log.warn("downloadAttachment is forbidden for S3");
            throw new ClientErrorException(
                    "Direct attachment download is forbidden for S3, please use presigned url",
                    Response.Status.FORBIDDEN);
        }
        String key = prepareKey(attachmentInfo, workspaceId);

        return fileService.download(key);
    }

    @Override
    public Mono<Attachment.AttachmentPage> list(int page, int size, @NonNull AttachmentSearchCriteria criteria,
            @NonNull String baseUrlEncoded) {
        String baseUrl = decodeBaseUrl(baseUrlEncoded);

        return attachmentDAO.list(page, size, criteria)
                .flatMap(attachmentPage -> Mono.deferContextual(ctx -> {
                    String workspaceName = ctx.get(RequestContext.WORKSPACE_NAME);
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

                    return Mono.just(attachmentPage.toBuilder()
                            .content(enhanceWithDownloadUrl(attachmentPage.content(), criteria, baseUrl, workspaceName,
                                    workspaceId))
                            .build());
                }));
    }

    @Override
    public Mono<Long> delete(@NonNull DeleteAttachmentsRequest request) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            Set<String> keys = request.fileNames().stream()
                    .map(fileName -> prepareKey(AttachmentInfo.builder()
                            .fileName(fileName)
                            .containerId(request.containerId())
                            .entityType(request.entityType())
                            .entityId(request.entityId())
                            .build(), workspaceId))
                    .collect(Collectors.toSet());

            return Mono.fromRunnable(() -> fileService.deleteObjects(keys));
        }).then(Mono.defer(() -> attachmentDAO.delete(request)));
    }

    @Override
    public Mono<Long> deleteByEntityIds(@NonNull EntityType entityType, @NonNull Set<UUID> entityIds) {
        if (entityIds.isEmpty()) {
            return Mono.just(0L);
        }

        return attachmentDAO.getAttachmentsByEntityIds(entityType, entityIds)
                .flatMap(attachments -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    Set<String> keys = attachments.stream()
                            .map(attachment -> prepareKey(attachment, workspaceId))
                            .collect(Collectors.toSet());

                    return Mono.fromRunnable(() -> fileService.deleteObjects(keys));
                }))
                .then(attachmentDAO.deleteByEntityIds(entityType, entityIds));
    }

    private List<Attachment> enhanceWithDownloadUrl(List<Attachment> attachments, AttachmentSearchCriteria criteria,
            String baseUrl, String workspaceName, String workspaceId) {
        return attachments.stream()
                .map(attachment -> {
                    AttachmentInfo attachmentInfo = AttachmentInfo.builder()
                            .fileName(attachment.fileName())
                            .containerId(criteria.containerId())
                            .entityType(criteria.entityType())
                            .entityId(criteria.entityId())
                            .mimeType(attachment.mimeType())
                            .build();

                    String downloadUrl = config.getS3Config().isMinIO()
                            ? prepareMinIODownloadUrl(attachmentInfo, baseUrl, workspaceName)
                            : prepareDownloadPresignUrl(attachmentInfo, workspaceId);

                    return attachment.toBuilder()
                            .link(downloadUrl)
                            .build();
                })
                .toList();
    }

    private String prepareKey(AttachmentInfoHolder infoHolder, String workspaceId) {
        return KEY_TEMPLATE.replace("{workspaceId}", workspaceId)
                .replace("{projectId}", infoHolder.containerId().toString())
                .replace("{entity_type}", infoHolder.entityType().getValue())
                .replace("{entity_id}", infoHolder.entityId().toString())
                .replace("{file_name}", infoHolder.fileName());
    }

    private UUID getProjectIdByName(String inputProjectName, String workspaceId, String userName) {
        String projectName = WorkspaceUtils.getProjectName(inputProjectName);

        var project = projectService.getOrCreate(projectName)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return project.id();
    }

    private String getMimeType(AttachmentInfoHolder infoHolder) {
        return Optional.ofNullable(infoHolder.mimeType())
                .filter(StringUtils::isNotBlank)
                .orElseGet(() -> tika.detect(infoHolder.fileName()));
    }

    private StartMultipartUploadResponse prepareMinIOUploadResponse(StartMultipartUploadRequest uploadRequest) {

        String baseUrl = decodeBaseUrl(uploadRequest.path());

        UriBuilder uriBuilder = UriBuilder.fromUri(baseUrl)
                .path("v1/private/attachment/upload")
                .queryParam("file_name", uploadRequest.fileName())
                .queryParam("mime_type", getMimeType(uploadRequest))
                .queryParam("entity_type", uploadRequest.entityType().getValue())
                .queryParam("entity_id", uploadRequest.entityId());

        if (uploadRequest.projectName() != null) {
            uriBuilder.queryParam("project_name", uploadRequest.projectName());
        }

        return StartMultipartUploadResponse.builder()
                .preSignUrls(List.of(uriBuilder.build().toASCIIString()))
                .uploadId("BEMinIO")
                .build();
    }

    private String prepareDownloadPresignUrl(AttachmentInfo attachmentInfo, String workspaceId) {
        String key = prepareKey(attachmentInfo, workspaceId);
        return preSignerService.presignDownloadUrl(key);
    }

    private String prepareMinIODownloadUrl(AttachmentInfo attachmentInfo, String baseUrl, String workspaceName) {
        var uri = UriBuilder.fromUri(baseUrl)
                .path("v1/private/attachment/download")
                .queryParam("workspace_name", workspaceName)
                .queryParam("container_id", attachmentInfo.containerId())
                .queryParam("file_name", attachmentInfo.fileName())
                .queryParam("mime_type", attachmentInfo.mimeType())
                .queryParam("entity_type", attachmentInfo.entityType().getValue())
                .queryParam("entity_id", attachmentInfo.entityId())
                .build();

        return uri.toASCIIString();
    }

    private String decodeBaseUrl(String baseUrlEncoded) {
        return new String(Base64.getUrlDecoder().decode(baseUrlEncoded), StandardCharsets.UTF_8);
    }
}
