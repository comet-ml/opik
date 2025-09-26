package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.MultipartUploadPart;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadResponse;
import com.comet.opik.api.events.AttachmentUploadRequested;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.common.eventbus.Subscribe;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;

/**
 * Listens for attachment upload events and processes them asynchronously.
 * This decouples attachment processing from the main request flow.
 *
 * Supports both storage backends:
 * - MinIO: Direct upload via AttachmentService.uploadAttachment()
 * - S3: Multipart upload with presigned URLs (start → HTTP PUT → complete)
 *
 * Provides OpenTelemetry metrics for monitoring async upload performance:
 * - opik.attachments.upload.attempts: Total upload attempts
 * - opik.attachments.upload.successes: Successful uploads
 * - opik.attachments.upload.failures: Failed uploads
 * - opik.attachments.upload.bytes: Total bytes uploaded (including failed attempts)
 * - opik.attachments.upload.duration.ms: Upload processing time
 */
@Slf4j
@EagerSingleton
public class AttachmentUploadListener {

    private final @NonNull AttachmentService attachmentService;
    private final @NonNull OpikConfiguration config;
    private final @NonNull HttpClient httpClient;

    // OpenTelemetry metrics
    private final LongCounter uploadAttempts;
    private final LongCounter uploadSuccesses;
    private final LongCounter uploadFailures;
    private final LongCounter uploadBytes;
    private final LongHistogram uploadDuration;

    @Inject
    public AttachmentUploadListener(@NonNull AttachmentService attachmentService,
            @NonNull OpikConfiguration config) {
        this.attachmentService = attachmentService;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();

        // Initialize OpenTelemetry metrics using global instance
        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.attachments");

        this.uploadAttempts = meter
                .counterBuilder("opik.attachments.upload.attempts")
                .setDescription("Number of async attachment upload attempts")
                .build();
        this.uploadSuccesses = meter
                .counterBuilder("opik.attachments.upload.successes")
                .setDescription("Number of successful async attachment uploads")
                .build();
        this.uploadFailures = meter
                .counterBuilder("opik.attachments.upload.failures")
                .setDescription("Number of failed async attachment uploads")
                .build();
        this.uploadBytes = meter
                .counterBuilder("opik.attachments.upload.bytes")
                .setDescription("Total bytes uploaded for attachments")
                .build();
        this.uploadDuration = meter
                .histogramBuilder("opik.attachments.upload.duration.ms")
                .setDescription("Time spent uploading attachments in milliseconds")
                .ofLongs()
                .build();
    }

    /**
     * Processes attachment upload requests asynchronously.
     * This method is called when AttachmentUploadRequested events are posted to the EventBus.
     */
    @Subscribe
    public void processAttachmentUpload(AttachmentUploadRequested event) {
        log.debug("Processing async attachment upload for file: '{}'", event.fileName());

        long startTime = System.currentTimeMillis();
        uploadAttempts.add(1);

        try {
            // Decode base64 data
            byte[] fileData = Base64.getDecoder().decode(event.base64Data());

            // Create AttachmentInfo for the upload
            AttachmentInfo attachmentInfo = AttachmentInfo.builder()
                    .fileName(event.fileName())
                    .mimeType(event.mimeType())
                    .entityType(event.entityType())
                    .entityId(event.entityId())
                    .projectName(event.projectName())
                    .build();

            // Handle upload based on configuration
            if (config.getS3Config().isMinIO()) {
                // MinIO: Use direct upload
                attachmentService.uploadAttachment(attachmentInfo, fileData, event.workspaceId(), event.userName());
                log.debug("MinIO direct upload completed for file: '{}'", attachmentInfo.fileName());

            } else {
                // S3: Use multipart upload with presigned URLs
                uploadToS3(attachmentInfo, fileData, event.workspaceId(), event.userName());
            }

            // Record success metrics
            long duration = System.currentTimeMillis() - startTime;
            uploadDuration.record(duration);
            uploadSuccesses.add(1);
            uploadBytes.add(fileData.length);

            log.debug("Successfully uploaded attachment: '{}' ({} bytes) to S3 in {}ms",
                    event.fileName(), fileData.length, duration);

        } catch (Exception e) {
            // Record failure metrics
            long duration = System.currentTimeMillis() - startTime;
            uploadDuration.record(duration);
            uploadFailures.add(1);

            // Still record bytes attempted even on failure (for monitoring bandwidth usage)
            try {
                byte[] fileData = Base64.getDecoder().decode(event.base64Data());
                uploadBytes.add(fileData.length);
                log.error("Failed to process attachment upload for file: '{}' ({} bytes) after {}ms, error: {}",
                        event.fileName(), fileData.length, duration, e.getMessage(), e);
            } catch (Exception decodeError) {
                log.error("Failed to process attachment upload for file: '{}' after {}ms, error: {}",
                        event.fileName(), duration, e.getMessage(), e);
            }
            // TODO: Consider implementing retry logic or dead letter queue
        }
    }

    /**
     * Uploads attachment to S3 using multipart upload with presigned URLs.
     */
    private void uploadToS3(AttachmentInfo attachmentInfo, byte[] fileData,
            String workspaceId, String userName) throws IOException, InterruptedException {

        // Step 1: Start multipart upload to get presigned URL
        StartMultipartUploadRequest startRequest = StartMultipartUploadRequest.builder()
                .fileName(attachmentInfo.fileName())
                .mimeType(attachmentInfo.mimeType())
                .entityType(attachmentInfo.entityType())
                .entityId(attachmentInfo.entityId())
                .projectName(attachmentInfo.projectName())
                .numOfFileParts(1) // Single part upload
                .build();

        StartMultipartUploadResponse startResponse = attachmentService.startMultiPartUpload(
                startRequest, workspaceId, userName);

        // Step 2: Upload data to presigned URL
        String presignedUrl = startResponse.preSignUrls().get(0);
        HttpRequest uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create(presignedUrl))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(fileData))
                .header("Content-Type", attachmentInfo.mimeType())
                .build();

        HttpResponse<String> uploadResponse = httpClient.send(uploadRequest,
                HttpResponse.BodyHandlers.ofString());

        if (uploadResponse.statusCode() != 200) {
            throw new IOException("S3 upload failed with status: " + uploadResponse.statusCode() +
                    ", body: " + uploadResponse.body());
        }

        // Extract ETag from response headers
        String eTag = uploadResponse.headers().firstValue("ETag")
                .orElseThrow(() -> new IOException("Missing ETag in S3 upload response"));

        // Step 3: Complete multipart upload
        MultipartUploadPart uploadPart = MultipartUploadPart.builder()
                .partNumber(1)
                .eTag(eTag.replaceAll("\"", "")) // Remove quotes from ETag
                .build();

        CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                .fileName(attachmentInfo.fileName())
                .mimeType(attachmentInfo.mimeType())
                .entityType(attachmentInfo.entityType())
                .entityId(attachmentInfo.entityId())
                .projectName(attachmentInfo.projectName())
                .uploadId(startResponse.uploadId())
                .uploadedFileParts(List.of(uploadPart))
                .fileSize((long) fileData.length)
                .build();

        attachmentService.completeMultiPartUpload(completeRequest, workspaceId, userName);

        log.debug("S3 multipart upload completed for file: '{}', uploadId: '{}'",
                attachmentInfo.fileName(), startResponse.uploadId());
    }

}
