package com.comet.opik.domain.attachment;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.events.AttachmentUploadRequested;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.S3Config;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.EventBus;
import com.google.inject.Singleton;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service responsible for detecting and stripping attachments from trace/span payloads.
 *
 * This service uses a simplified string-based approach with async upload processing:
 * 1. Convert JSON to string
 * 2. Find all base64 strings using regex
 * 3. Process each base64 string with Tika for MIME type detection
 * 4. Post attachment upload events to EventBus for async processing
 * 5. Replace base64 strings in the JSON with references to the attachments
 *
 * Filenames are generated to include context (input, output, metadata) to prevent naming conflicts.
 * Actual uploads are handled asynchronously by AttachmentUploadListener.
 */
@Singleton
@Slf4j
public class AttachmentStripperService {

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull S3Config s3Config;
    private final @NonNull EventBus eventBus;

    // OpenTelemetry metrics for monitoring attachment detection and event posting
    // Note: Actual upload success/failure metrics are tracked in AttachmentUploadListener
    private final LongCounter attachmentsProcessed;
    private final LongCounter attachmentsSkipped;
    private final LongCounter attachmentErrors;
    private final LongHistogram processingTimer;

    // Apache Tika for MIME type detection
    private static final Tika tika = new Tika();

    // Base64 pattern compiled once during construction
    private final Pattern base64Pattern;

    // Minimum base64 size to look for
    private final int minBase64Size;

    @Inject
    public AttachmentStripperService(@NonNull IdGenerator idGenerator,
            @NonNull ObjectMapper objectMapper,
            @NonNull OpikConfiguration opikConfig,
            @NonNull EventBus eventBus) {
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.s3Config = opikConfig.getS3Config();
        this.eventBus = eventBus;

        // Initialize OpenTelemetry metrics using global instance
        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.attachments");

        this.attachmentsProcessed = meter
                .counterBuilder("opik.attachments.processed")
                .setDescription("Number of attachment upload events posted to EventBus")
                .build();

        this.attachmentsSkipped = meter
                .counterBuilder("opik.attachments.skipped")
                .setDescription("Number of base64 strings skipped (not valid attachments)")
                .build();
        this.attachmentErrors = meter
                .counterBuilder("opik.attachments.errors")
                .setDescription("Number of errors during attachment detection/processing")
                .build();
        this.processingTimer = meter
                .histogramBuilder("opik.attachments.processing.duration.ms")
                .setDescription("Time spent detecting and processing attachments in milliseconds")
                .ofLongs()
                .build();

        // Compile the regex pattern once during construction based on configuration
        int minLength = s3Config.getStripAttachmentsMinSize();
        this.minBase64Size = minLength;
        this.base64Pattern = Pattern.compile("([A-Za-z0-9+/]{" + minLength + ",}={0,2})");

        log.info("AttachmentStripperService initialized with minBase64Length: {}", minLength);
    }

    /**
     * Strips attachments from a Trace entity.
     *
     * TODO: This one and the similar ones below should be refactored to use the same method if
     * we can make all these types to share the same interface (shouldn't they?)
     */
    public Mono<Trace> stripAttachments(Trace trace, String workspaceId, String userName, String projectName) {
        return Mono.fromCallable(() -> {
            Trace.TraceBuilder builder = trace.toBuilder();

            if (trace.input() != null) {
                JsonNode processedInput = stripAttachments(trace.input(), trace.id(), EntityType.TRACE,
                        workspaceId, userName, projectName, "input");
                builder.input(processedInput);
            }

            if (trace.output() != null) {
                JsonNode processedOutput = stripAttachments(trace.output(), trace.id(), EntityType.TRACE,
                        workspaceId, userName, projectName, "output");
                builder.output(processedOutput);
            }

            if (trace.metadata() != null) {
                JsonNode processedMetadata = stripAttachments(trace.metadata(), trace.id(), EntityType.TRACE,
                        workspaceId, userName, projectName, "metadata");
                builder.metadata(processedMetadata);
            }

            return builder.build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Strips attachments from a TraceUpdate entity.
     *
     * TODO: This one and the similar ones below should be refactored to use the same method if
     * we can make all these types to share the same interface (shouldn't they?)
     */
    public Mono<TraceUpdate> stripAttachments(TraceUpdate traceUpdate, UUID traceId, String workspaceId,
            String userName, String projectName) {
        return Mono.fromCallable(() -> {
            TraceUpdate.TraceUpdateBuilder builder = traceUpdate.toBuilder();

            if (traceUpdate.input() != null) {
                JsonNode processedInput = stripAttachments(traceUpdate.input(), traceId, EntityType.TRACE,
                        workspaceId, userName, projectName, "input");
                builder.input(processedInput);
            }

            if (traceUpdate.output() != null) {
                JsonNode processedOutput = stripAttachments(traceUpdate.output(), traceId, EntityType.TRACE,
                        workspaceId, userName, projectName, "output");
                builder.output(processedOutput);
            }

            if (traceUpdate.metadata() != null) {
                JsonNode processedMetadata = stripAttachments(traceUpdate.metadata(), traceId, EntityType.TRACE,
                        workspaceId, userName, projectName, "metadata");
                builder.metadata(processedMetadata);
            }

            return builder.build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Strips attachments from a Span entity.
     *
     * TODO: This one and the similar ones below should be refactored to use the same method if
     * we can make all these types to share the same interface (shouldn't they?)
     */
    public Mono<Span> stripAttachments(Span span, String workspaceId, String userName, String projectName) {
        return Mono.fromCallable(() -> {
            Span.SpanBuilder builder = span.toBuilder();

            if (span.input() != null) {
                JsonNode processedInput = stripAttachments(span.input(), span.id(), EntityType.SPAN,
                        workspaceId, userName, projectName, "input");
                builder.input(processedInput);
            }

            if (span.output() != null) {
                JsonNode processedOutput = stripAttachments(span.output(), span.id(), EntityType.SPAN,
                        workspaceId, userName, projectName, "output");
                builder.output(processedOutput);
            }

            if (span.metadata() != null) {
                JsonNode processedMetadata = stripAttachments(span.metadata(), span.id(), EntityType.SPAN,
                        workspaceId, userName, projectName, "metadata");
                builder.metadata(processedMetadata);
            }

            return builder.build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Strips attachments from a SpanUpdate entity.
     *
     * TODO: This one and the similar ones below should be refactored to use the same method if
     * we can make all these types to share the same interface (shouldn't they?)
     */
    public Mono<SpanUpdate> stripAttachments(SpanUpdate spanUpdate, UUID spanId, String workspaceId,
            String userName, String projectName) {
        return Mono.fromCallable(() -> {
            SpanUpdate.SpanUpdateBuilder builder = spanUpdate.toBuilder();

            if (spanUpdate.input() != null) {
                JsonNode processedInput = stripAttachments(spanUpdate.input(), spanId, EntityType.SPAN,
                        workspaceId, userName, projectName, "input");
                builder.input(processedInput);
            }

            if (spanUpdate.output() != null) {
                JsonNode processedOutput = stripAttachments(spanUpdate.output(), spanId, EntityType.SPAN,
                        workspaceId, userName, projectName, "output");
                builder.output(processedOutput);
            }

            if (spanUpdate.metadata() != null) {
                JsonNode processedMetadata = stripAttachments(spanUpdate.metadata(), spanId, EntityType.SPAN,
                        workspaceId, userName, projectName, "metadata");
                builder.metadata(processedMetadata);
            }

            return builder.build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Strips attachments from a JSON payload and returns the processed JSON with attachment references.
     *
     * Uses a simplified string-based approach for maximum simplicity and robustness.
     * Attachments are uploaded using either direct upload (MinIO) or multipart upload (S3) based on configuration.
     * Generated filenames include context to avoid conflicts between input, output, and metadata attachments.
     */
    public JsonNode stripAttachments(JsonNode node,
            UUID entityId,
            EntityType entityType,
            String workspaceId,
            String userName,
            String projectName,
            String context) {
        if (node == null || node.isNull()) {
            return node;
        }

        long startTime = System.currentTimeMillis();
        try {
            // Step 1: Convert JSON to string
            String jsonString = objectMapper.writeValueAsString(node);

            // Step 2: Process all base64 strings in the JSON
            String processedJson = processBase64InJsonString(
                    jsonString, entityId, entityType, workspaceId, userName, projectName, context);

            // Step 3: Only create new JsonNode if changes were made (avoid unnecessary object creation)
            if (jsonString.equals(processedJson)) {
                return node; // No attachments found, return original node
            }

            // Convert back to JSON only if we made changes
            return objectMapper.readTree(processedJson);

        } catch (JsonProcessingException e) {
            log.error("Failed to process JSON for attachment stripping", e);
            // We cannot return the original large payload to ClickHouse
            throw new InternalServerErrorException("Failed to process attachments in payload", e);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            processingTimer.record(duration);
        }
    }

    /**
     * Processes all base64 strings found in a JSON string and replaces them with attachment references.
     *
     * Uses regex to find base64 strings longer than the minimum threshold, processes each one
     * as a potential attachment, and replaces valid attachments with reference strings.
     */
    private String processBase64InJsonString(String jsonString,
            UUID entityId,
            EntityType entityType,
            String workspaceId,
            String userName,
            String projectName,
            String context) {

        // Early exit: if the entire JSON string is smaller than our minimum base64 size,
        // it can't possibly contain any base64 attachments worth processing
        if (jsonString.length() < minBase64Size) {
            return jsonString;
        }

        // Use the pre-compiled pattern from construction
        Matcher matcher = base64Pattern.matcher(jsonString);
        StringBuilder result = new StringBuilder();
        int attachmentCounter = 1;

        while (matcher.find()) {
            String base64Data = matcher.group(1); // Extract base64 without quotes

            // Try to process as attachment
            String attachmentReference = processBase64Attachment(
                    base64Data, attachmentCounter,
                    entityId, entityType, workspaceId, userName, projectName, context);

            if (attachmentReference != null) {
                // Replace the base64 string with the reference
                matcher.appendReplacement(result, attachmentReference);
                attachmentCounter++; // Only increment if we actually processed an attachments
            }
            // If not an attachment, matcher.appendTail() will handle keeping the original
        }

        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Processes a potential base64 attachment string.
     *
     * Decodes the base64 data, uses Apache Tika to detect the MIME type, and posts an async upload event
     * to the EventBus for background processing. The generated filename includes the context prefix to avoid conflicts.
     *
     * @param base64Data the base64 string to process
     * @param attachmentNumber the sequential attachment number for this request
     * @param entityId the entity ID (trace or span ID) to link the attachment to
     * @param entityType the entity type (TRACE or SPAN)
     * @param workspaceId the workspace ID
     * @param userName the user name
     * @param projectName the project name
     * @param context the context where the attachment was found (input, output, metadata)
     * @return attachment reference string if processed, null if not a valid attachment
     */
    private String processBase64Attachment(String base64Data,
            int attachmentNumber,
            UUID entityId,
            EntityType entityType,
            String workspaceId,
            String userName,
            String projectName,
            String context) {
        try {
            // Decode base64 and detect MIME type using Tika
            byte[] bytes = Base64.getDecoder().decode(base64Data);
            String mimeType = tika.detect(bytes);

            // Skip if not a recognizable file type (Tika returns these for non-binary data)
            if ("application/octet-stream".equals(mimeType) || "text/plain".equals(mimeType)) {
                attachmentsSkipped.add(1);
                return null;
            }

            // Generate attachment info with appropriate extension and context
            String extension = getFileExtension(mimeType);
            String fileName = context + "-attachment-" + attachmentNumber + "-" + System.currentTimeMillis() + "."
                    + extension;

            // Post event for async attachment upload
            AttachmentUploadRequested uploadEvent = new AttachmentUploadRequested(
                    fileName,
                    mimeType,
                    base64Data, // Keep original base64 data for async processing
                    workspaceId,
                    userName,
                    projectName,
                    entityId,
                    entityType);

            // Post event to EventBus for async processing
            eventBus.post(uploadEvent);

            log.info("Posted async upload event for attachment: '{}'", fileName);

            // Record that we posted an upload event (compare with uploadSuccesses/uploadFailures in AttachmentUploadListener)
            attachmentsProcessed.add(1);

            return "[" + fileName + "]";

        } catch (IllegalArgumentException e) {
            // Not valid base64, ignore silently
            attachmentsSkipped.add(1);
            return null;
        } catch (Exception e) {
            // Unexpected errors during attachment processing
            log.error("Error processing attachment #{} in context '{}': {}",
                    attachmentNumber, context, e.getMessage(), e);
            attachmentErrors.add(1);
            return null; // Graceful degradation - continue without this attachment
        }
    }

    /**
     * Converts MIME type to appropriate file extension.
     *
     * @param mimeType the MIME type (e.g., "image/png", "application/pdf")
     * @return the file extension (e.g., "png", "pdf")
     */
    private String getFileExtension(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return "bin";
        }

        try {
            // Use Apache Tika's built-in MIME type to extension mapping
            MimeTypes allTypes = MimeTypes.getDefaultMimeTypes();
            String extension = allTypes.forName(mimeType).getExtension();

            // Remove the leading dot if present (Tika returns ".png", we want "png")
            if (extension.startsWith(".")) {
                extension = extension.substring(1);
            }

            return extension.isEmpty() ? "bin" : extension;

        } catch (MimeTypeException e) {
            // Fallback: extract from MIME type (e.g., "image/png" -> "png")
            if (mimeType.contains("/")) {
                String subtype = mimeType.substring(mimeType.indexOf("/") + 1);
                // Handle special cases like "svg+xml" -> "svg"
                if (subtype.contains("+")) {
                    subtype = subtype.substring(0, subtype.indexOf("+"));
                }
                // Remove any parameters (e.g., "jpeg; charset=utf-8" -> "jpeg")
                return subtype.split(";")[0].trim();
            }

            return "bin";
        }
    }

    /**
     * Detects MIME type of base64 data for testing purposes.
     */
    String detectMimeType(String base64Data) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Data);
            return tika.detect(bytes);
        } catch (Exception e) {
            return "unknown";
        }
    }

}
