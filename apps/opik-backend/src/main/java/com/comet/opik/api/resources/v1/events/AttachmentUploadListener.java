package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.events.AttachmentUploadRequested;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.attachment.AttachmentUtils;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.common.eventbus.Subscribe;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.Base64;

/**
 * Listens for attachment upload events and processes them asynchronously.
 * This decouples attachment processing from the main request flow.
 *
 * Provides OpenTelemetry metrics with status, mime_category, and mime_subtype tags:
 * - opik.attachments.upload.processed: Upload attempts (e.g. status=success, mime_category=image, mime_subtype=png)
 * - opik.attachments.upload.bytes: Bytes processed (e.g. status=success, mime_category=video, mime_subtype=mp4)
 * - opik.attachments.upload.duration.ms: Processing duration (e.g. status=failure, mime_category=application, mime_subtype=pdf)
 */
@Slf4j
@EagerSingleton
@AllArgsConstructor(onConstructor_ = @Inject)
public class AttachmentUploadListener {

    // OpenTelemetry metrics - initialized once at class loading
    private static final Meter METER = GlobalOpenTelemetry.get().getMeter("opik.attachments");
    private static final AttributeKey<String> STATUS_KEY = AttributeKey.stringKey("status");
    private static final AttributeKey<String> MIME_CATEGORY_KEY = AttributeKey.stringKey("mime_category");
    private static final AttributeKey<String> MIME_SUBTYPE_KEY = AttributeKey.stringKey("mime_subtype");
    private static final AttributeKey<String> WORKSPACE_ID_KEY = AttributeKey.stringKey("workspace_id");

    private static final LongCounter UPLOAD_PROCESSED = METER
            .counterBuilder("opik.attachments.upload.processed")
            .setDescription(
                    "Number of attachment uploads processed (tagged by status, mime_category, mime_subtype, workspace_id)")
            .build();
    private static final LongCounter UPLOAD_BYTES = METER
            .counterBuilder("opik.attachments.upload.bytes")
            .setDescription(
                    "Bytes processed for attachment uploads (tagged by status, mime_category, mime_subtype, workspace_id)")
            .build();
    private static final LongHistogram UPLOAD_DURATION = METER
            .histogramBuilder("opik.attachments.upload.duration.ms")
            .setDescription(
                    "Duration of attachment uploads in milliseconds (tagged by status, mime_category, mime_subtype, workspace_id)")
            .ofLongs()
            .build();
    private static final LongHistogram UPLOAD_BYTES_DISTRIBUTION = METER
            .histogramBuilder("opik.attachments.upload.bytes.distribution")
            .setDescription(
                    "Distribution of attachment upload sizes in bytes (tagged by status, mime_category, mime_subtype, workspace_id)")
            .ofLongs()
            .build();

    private final @NonNull AttachmentService attachmentService;
    private final @NonNull OpikConfiguration config;

    /**
     * Processes attachment upload requests asynchronously.
     * This method is called when AttachmentUploadRequested events are posted to the EventBus.
     */
    @Subscribe
    @WithSpan
    public void processAttachmentUpload(@NonNull AttachmentUploadRequested event) {
        long startTime = System.currentTimeMillis();
        String status = "failure";

        // Parse MIME type into category and subtype (e.g. "image/png" -> category="image", subtype="png")
        String[] mimeParts = AttachmentUtils.parseMimeType(event.mimeType());
        String mimeCategory = mimeParts[0];
        String mimeSubtype = mimeParts[1];
        // Calculate estimated byte size from base64 string (works even if decode fails)
        long estimatedBytes = AttachmentUtils.calculateBase64DecodedSize(event.base64Data());

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

            // Use the internal method that works for both MinIO and S3
            // This bypasses the frontend presigned URL restriction for backend async uploads
            attachmentService.uploadAttachmentInternal(attachmentInfo, fileData, event.workspaceId(), event.userName());

            status = "success";
            log.info("Successfully uploaded attachment: '{}' (type: {}/{}, {} bytes) in {}ms",
                    event.fileName(), mimeCategory, mimeSubtype, fileData.length,
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("Failed to process attachment upload for file: '{}' (type: {}/{}) after {}ms",
                    event.fileName(), mimeCategory, mimeSubtype, System.currentTimeMillis() - startTime, e);
        } finally {
            // Record metrics with status, mime_category, mime_subtype, and workspace_id tags
            Attributes attributes = Attributes.of(
                    STATUS_KEY, status,
                    WORKSPACE_ID_KEY, event.workspaceId(),
                    MIME_CATEGORY_KEY, mimeCategory,
                    MIME_SUBTYPE_KEY, mimeSubtype);

            long duration = System.currentTimeMillis() - startTime;
            UPLOAD_PROCESSED.add(1, attributes);
            UPLOAD_DURATION.record(duration, attributes);
            UPLOAD_BYTES.add(estimatedBytes, attributes);
            UPLOAD_BYTES_DISTRIBUTION.record(estimatedBytes, attributes);
        }
    }

}
