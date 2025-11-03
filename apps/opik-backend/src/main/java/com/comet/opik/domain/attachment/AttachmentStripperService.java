package com.comet.opik.domain.attachment;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.events.AttachmentUploadRequested;
import com.comet.opik.infrastructure.AttachmentsConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.EventBus;
import com.google.inject.Singleton;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.annotations.WithSpan;
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
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service responsible for detecting and stripping attachments from trace/span payloads.
 *
 * This service uses an optimized JSON tree traversal approach with async upload processing:
 * 1. Recursively walk the JSON tree (objects, arrays, text nodes)
 * 2. For each text/string field, check if it matches base64 pattern
 * 3. Fast-path for small strings (< minBase64Size)
 * 4. Process base64 strings with Tika for MIME type detection
 * 5. Post attachment upload events to EventBus for async processing
 * 6. Replace base64 strings with attachment references
 *
 * This approach dramatically reduces CPU usage compared to string-based regex on large payloads
 * by only processing individual fields that might contain attachments, avoiding the need to
 * convert the entire JSON to string and back.
 *
 * Filenames are generated to include context (input, output, metadata) to prevent naming conflicts.
 * Actual uploads are handled asynchronously by AttachmentUploadListener.
 */
@Singleton
@Slf4j
public class AttachmentStripperService {

    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull AttachmentsConfig attachmentsConfig;
    private final @NonNull EventBus eventBus;

    // OpenTelemetry metrics for monitoring attachment detection and event posting
    // Note: Actual upload success/failure metrics are tracked in AttachmentUploadListener
    private final LongCounter attachmentsProcessed;
    private final LongCounter attachmentsSkipped;
    private final LongCounter attachmentErrors;
    private final LongHistogram processingTimer;

    // Apache Tika for MIME type detection
    private static final Tika tika = new Tika();

    private final Tracer tracer;

    // Base64 pattern compiled once during construction
    private final Pattern base64Pattern;

    // Minimum base64 size to look for
    private final long minBase64Size;

    @Inject
    public AttachmentStripperService(@NonNull ObjectMapper objectMapper,
            @NonNull OpikConfiguration opikConfig,
            @NonNull EventBus eventBus) {
        this.objectMapper = objectMapper;
        this.attachmentsConfig = opikConfig.getAttachmentsConfig();
        this.eventBus = eventBus;

        // Initialize OpenTelemetry tracer and metrics using global instance
        this.tracer = GlobalOpenTelemetry.get().getTracer("opik.attachments");
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
        this.minBase64Size = attachmentsConfig.getStripMinSize();
        this.base64Pattern = Pattern.compile("([A-Za-z0-9+/]{" + minBase64Size + ",}={0,2})");

        log.info("AttachmentStripperService initialized with minBase64Length: {}", minBase64Size);
    }

    /**
     * Strips attachments from a Trace entity.
     */
    @WithSpan
    public Mono<Trace> stripAttachments(Trace trace, String workspaceId, String userName, String projectName) {
        var builder = trace.toBuilder();
        return stripAttachmentsCommon(
                trace.id(),
                EntityType.TRACE,
                workspaceId,
                userName,
                projectName,
                trace.input(),
                trace.output(),
                trace.metadata(),
                builder::input,
                builder::output,
                builder::metadata,
                builder::build);
    }

    /**
     * Strips attachments from a TraceUpdate entity.
     */
    @WithSpan
    public Mono<TraceUpdate> stripAttachments(TraceUpdate traceUpdate, UUID traceId, String workspaceId,
            String userName, String projectName) {
        var builder = traceUpdate.toBuilder();
        return stripAttachmentsCommon(
                traceId,
                EntityType.TRACE,
                workspaceId,
                userName,
                projectName,
                traceUpdate.input(),
                traceUpdate.output(),
                traceUpdate.metadata(),
                builder::input,
                builder::output,
                builder::metadata,
                builder::build);
    }

    /**
     * Strips attachments from a Span entity.
     */
    @WithSpan
    public Mono<Span> stripAttachments(Span span, String workspaceId, String userName, String projectName) {
        var builder = span.toBuilder();
        return stripAttachmentsCommon(
                span.id(),
                EntityType.SPAN,
                workspaceId,
                userName,
                projectName,
                span.input(),
                span.output(),
                span.metadata(),
                builder::input,
                builder::output,
                builder::metadata,
                builder::build);
    }

    /**
     * Strips attachments from a SpanUpdate entity.
     */
    @WithSpan
    public Mono<SpanUpdate> stripAttachments(SpanUpdate spanUpdate, UUID spanId, String workspaceId,
            String userName, String projectName) {
        var builder = spanUpdate.toBuilder();
        return stripAttachmentsCommon(
                spanId,
                EntityType.SPAN,
                workspaceId,
                userName,
                projectName,
                spanUpdate.input(),
                spanUpdate.output(),
                spanUpdate.metadata(),
                builder::input,
                builder::output,
                builder::metadata,
                builder::build);
    }

    /**
     * Context for attachment processing operations.
     * Encapsulates the entity and workspace information needed throughout the attachment stripping process.
     */
    private record AttachmentContext(
            UUID entityId,
            EntityType entityType,
            String workspaceId,
            String userName,
            String projectName,
            String fieldContext) {
    }

    /**
     * Common logic for stripping attachments from trace/span entities.
     *
     * This method handles the reactive processing of input, output, and metadata fields,
     * stripping attachments asynchronously and applying the results back via the provided setters.
     *
     * @param <T> the entity type being processed
     * @param entityId the entity ID (trace or span)
     * @param entityType the type of entity (TRACE or SPAN)
     * @param workspaceId the workspace ID
     * @param userName the user name
     * @param projectName the project name
     * @param input the input JSON node
     * @param output the output JSON node
     * @param metadata the metadata JSON node
     * @param inputSetter consumer to set input on the entity builder
     * @param outputSetter consumer to set output on the entity builder
     * @param metadataSetter consumer to set metadata on the entity builder
     * @param buildFunction supplier to build the final entity
     * @return Mono containing the processed entity
     */
    private <T> Mono<T> stripAttachmentsCommon(
            UUID entityId,
            EntityType entityType,
            String workspaceId,
            String userName,
            String projectName,
            JsonNode input,
            JsonNode output,
            JsonNode metadata,
            Consumer<JsonNode> inputSetter,
            Consumer<JsonNode> outputSetter,
            Consumer<JsonNode> metadataSetter,
            Supplier<T> buildFunction) {

        // Create context records for each field (input, output, metadata)
        AttachmentContext inputCtx = new AttachmentContext(entityId, entityType, workspaceId, userName, projectName,
                "input");
        AttachmentContext outputCtx = new AttachmentContext(entityId, entityType, workspaceId, userName, projectName,
                "output");
        AttachmentContext metadataCtx = new AttachmentContext(entityId, entityType, workspaceId, userName, projectName,
                "metadata");

        Mono<Optional<JsonNode>> inputMono = processField(input, inputCtx);
        Mono<Optional<JsonNode>> outputMono = processField(output, outputCtx);
        Mono<Optional<JsonNode>> metadataMono = processField(metadata, metadataCtx);

        return Mono.zip(inputMono, outputMono, metadataMono)
                .map(tuple -> {
                    tuple.getT1().ifPresent(inputSetter);
                    tuple.getT2().ifPresent(outputSetter);
                    tuple.getT3().ifPresent(metadataSetter);

                    return buildFunction.get();
                });
    }

    private Mono<Optional<JsonNode>> processField(JsonNode value, AttachmentContext ctx) {

        io.opentelemetry.api.trace.Span.current().setAttribute("entity_field", ctx.fieldContext());

        return Mono.justOrEmpty(value)
                .flatMap(it -> Mono.fromCallable(() -> stripAttachments(it, ctx))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    /**
     * Strips attachments from a JSON payload and returns the processed JSON with attachment references.
     *
     * Public API for external callers that creates the attachment context internally.
     */
    @WithSpan
    public JsonNode stripAttachments(JsonNode node,
            UUID entityId,
            EntityType entityType,
            String workspaceId,
            String userName,
            String projectName,
            String context) {
        AttachmentContext ctx = new AttachmentContext(entityId, entityType, workspaceId, userName, projectName,
                context);
        return stripAttachments(node, ctx);
    }

    /**
     * Strips attachments from a JSON payload and returns the processed JSON with attachment references.
     *
     * Uses an optimized field-by-field approach that avoids converting the entire JSON to string:
     * 1. Recursively walks the JSON tree (objects, arrays, text nodes)
     * 2. For each text/string field, checks if it matches base64 pattern
     * 3. Fast-path for small strings (< minBase64Size)
     * 4. Only processes individual fields that might contain attachments
     *
     * This approach dramatically reduces CPU usage compared to string-based regex on large payloads.
     * Generated filenames include context to avoid conflicts between input, output, and metadata attachments.
     */
    @WithSpan
    private JsonNode stripAttachments(JsonNode node, AttachmentContext ctx) {
        if (node == null || node.isNull()) {
            return node;
        }

        long startTime = System.currentTimeMillis();

        try {
            // Start at 0, will be incremented to 1 for first attachment (using incrementAndGet pattern)
            AtomicInteger attachmentCounter = new AtomicInteger(0);

            // Process the JSON tree recursively, returning potentially modified node
            JsonNode processed = wrapWithSpan("processJsonNode",
                    () -> processJsonNode(node, attachmentCounter, ctx));

            return processed;
        } catch (Exception e) {
            log.error("Failed to process JSON for attachment stripping", e);
            // We cannot return the original large payload to ClickHouse
            throw new InternalServerErrorException("Failed to process attachments in payload", e);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            processingTimer.record(duration);
        }
    }

    private <T> T wrapWithSpan(String spanName, Supplier<T> action) {
        io.opentelemetry.api.trace.Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            return action.get();
        } finally {
            span.end();
        }
    }

    private void wrapWithSpanVoid(String spanName, Runnable action) {
        io.opentelemetry.api.trace.Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            action.run();
        } finally {
            span.end();
        }
    }

    /**
     * Recursively processes a JSON node, checking each text field for potential attachments.
     *
     * This method walks the JSON tree without converting to string, which is much more efficient
     * for large payloads. It handles:
     * - Object nodes: recursively processes each field
     * - Array nodes: recursively processes each element
     * - Text nodes: checks if content matches base64 pattern and processes as attachment
     * - Other nodes: returns as-is (numbers, booleans, null, etc.)
     *
     * @return the processed JsonNode (may be the same instance if no attachments found)
     */
    private JsonNode processJsonNode(
            JsonNode node,
            AtomicInteger attachmentCounter,
            AttachmentContext ctx) {

        if (node == null || node.isNull()) {
            return node;
        }

        return switch (node.getNodeType()) {
            case OBJECT -> processObjectNode(node, attachmentCounter, ctx);
            case ARRAY -> processArrayNode(node, attachmentCounter, ctx);
            case STRING -> processTextNode(node, attachmentCounter, ctx);
            default -> node; // Numbers, booleans, null, etc. pass through unchanged
        };
    }

    /**
     * Processes a JSON object node by recursively processing each field.
     *
     * @return the processed object node, or the original if no changes were made
     */
    private JsonNode processObjectNode(
            JsonNode node,
            AtomicInteger attachmentCounter,
            AttachmentContext ctx) {

        var objectNode = objectMapper.createObjectNode();
        boolean hasChanges = false;

        // Iterate over fields using while loop
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fieldName = entry.getKey();
            JsonNode fieldValue = entry.getValue();

            // Recursively process the field value
            JsonNode processed = processJsonNode(fieldValue, attachmentCounter, ctx);

            objectNode.set(fieldName, processed);

            // Track if we made any changes
            if (processed != fieldValue) {
                hasChanges = true;
            }
        }

        // Return original if no changes were made (avoid unnecessary object creation)
        return hasChanges ? objectNode : node;
    }

    /**
     * Processes a JSON array node by recursively processing each element.
     *
     * @return the processed array node, or the original if no changes were made
     */
    private JsonNode processArrayNode(
            JsonNode node,
            AtomicInteger attachmentCounter,
            AttachmentContext ctx) {

        var arrayNode = objectMapper.createArrayNode();
        boolean hasChanges = false;

        for (JsonNode element : node) {
            JsonNode processed = processJsonNode(element, attachmentCounter, ctx);
            arrayNode.add(processed);

            // Track if we made any changes
            if (processed != element) {
                hasChanges = true;
            }
        }

        // Return original if no changes were made (avoid unnecessary array creation)
        return hasChanges ? arrayNode : node;
    }

    /**
     * Processes a JSON text node by searching for base64 attachments and replacing them with references.
     *
     * @return the processed text node with attachment references, or the original if no attachments found
     */
    private JsonNode processTextNode(
            JsonNode node,
            AtomicInteger attachmentCounter,
            AttachmentContext ctx) {

        String text = node.asText();

        // Fast path: skip small strings that can't possibly contain attachments
        if (text.length() < minBase64Size) {
            return node;
        }

        // Search for base64 patterns within the text (not just exact matches)
        Matcher matcher = wrapWithSpan("regex.match", () -> base64Pattern.matcher(text));
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        boolean foundAttachment = false;

        while (wrapWithSpan("regex.find", matcher::find)) {
            String base64Data = matcher.group(1);

            // Increment counter first (starts at 0, so first attachment becomes 1)
            int currentAttachmentNumber = attachmentCounter.incrementAndGet();

            // Try to process as attachment
            String attachmentReference = processBase64Attachment(base64Data, currentAttachmentNumber, ctx);

            if (attachmentReference != null) {
                // Append text before the match
                result.append(text, lastEnd, matcher.start());
                // Append the attachment reference
                result.append(attachmentReference);
                lastEnd = matcher.end();
                foundAttachment = true;
            }
        }

        // If we found and replaced attachments, return new node with modified text
        if (foundAttachment) {
            result.append(text, lastEnd, text.length());
            return objectMapper.getNodeFactory().textNode(result.toString());
        }

        // No attachments found, return original node
        return node;
    }

    /**
     * Processes a potential base64 attachment string.
     *
     * Decodes the base64 data, uses Apache Tika to detect the MIME type, and posts an async upload event
     * to the EventBus for background processing. The generated filename includes the context prefix to avoid conflicts.
     *
     * @param base64Data the base64 string to process
     * @param attachmentNumber the sequential attachment number for this request (1-based)
     * @param ctx the attachment context containing entity and workspace information
     * @return attachment reference string if processed, null if not a valid attachment
     */
    @WithSpan
    private String processBase64Attachment(String base64Data, int attachmentNumber, AttachmentContext ctx) {
        try {
            // Decode base64 and detect MIME type using Tika
            byte[] bytes = wrapWithSpan("base64.decode", () -> Base64.getDecoder().decode(base64Data));
            String mimeType = wrapWithSpan("tika.detect", () -> tika.detect(bytes));

            // Skip if not a recognizable file type (Tika returns these for non-binary data)
            if ("application/octet-stream".equals(mimeType) || "text/plain".equals(mimeType)) {
                attachmentsSkipped.add(1);
                return null;
            }

            // Generate attachment info with appropriate extension and context
            String extension = wrapWithSpan("getFileExtension", () -> getFileExtension(mimeType));

            String fileName = ctx.fieldContext() + "-attachment-" + attachmentNumber + "-"
                    + System.currentTimeMillis() + "." + extension;

            // Post event for async attachment upload
            AttachmentUploadRequested uploadEvent = new AttachmentUploadRequested(
                    fileName,
                    mimeType,
                    base64Data, // Keep original base64 data for async processing
                    ctx.workspaceId(),
                    ctx.userName(),
                    ctx.projectName(),
                    ctx.entityId(),
                    ctx.entityType());

            // Post event to EventBus for async processing
            wrapWithSpanVoid("eventBus.post", () -> eventBus.post(uploadEvent));

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
                    attachmentNumber, ctx.fieldContext(), e.getMessage(), e);
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
