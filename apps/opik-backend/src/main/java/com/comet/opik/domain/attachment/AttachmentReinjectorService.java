package com.comet.opik.domain.attachment;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service responsible for reinjecting attachment data back into trace/span payloads
 * when truncate=false is requested by the client.
 *
 * This service reverses the work done by AttachmentStripperService by:
 * 1. Finding attachment references like [{context}-attachment-{num}-{timestamp}.{ext}] in JSON payloads
 * 2. Fetching the actual attachment data from S3/MinIO
 * 3. Re-encoding as base64 and injecting back into the payload
 *
 * Example attachment reference: [input-attachment-1-1704067200000.png]
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AttachmentReinjectorService {

    /**
     * Pattern to match attachment references in the format: [{context}-attachment-{num}-{timestamp}.{extension}]
     * Examples:
     * - [input-attachment-1-1704067200000.png]
     * - [output-attachment-2-1704067201000.json]
     * - [metadata-attachment-1-1704067199000.pdf]
     */
    private static final Pattern ATTACHMENT_REF_PATTERN = Pattern.compile("^\\[(\\w+-attachment-\\d+-\\d+\\.\\w+)\\]$");

    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull AttachmentService attachmentService;

    /**
     * Reinjects attachments into a trace object.
     * Only processes if truncate=false.
     */
    @WithSpan
    public Mono<Trace> reinjectAttachments(@NonNull Trace trace, boolean truncate) {
        if (truncate) {
            return Mono.just(trace);
        }

        // Quick check: scan for attachment references before querying DB
        boolean hasRefs = hasAttachmentReferences(trace.input(), trace.output(), trace.metadata());
        if (!hasRefs) {
            return Mono.just(trace);
        }

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            UUID entityId = trace.id();
            UUID projectId = trace.projectId();
            EntityType entityType = EntityType.TRACE;

            // Get attachment info using AttachmentService
            return attachmentService
                    .getAttachmentInfoByEntity(entityId, entityType, projectId)
                    .flatMap(attachments -> Mono.fromCallable(() -> {
                        if (attachments == null || attachments.isEmpty()) {
                            return trace;
                        }

                        // Reinject attachments into input, output, and metadata
                        JsonNode newInput = reinjectIntoJson(trace.input(), attachments, workspaceId);
                        JsonNode newOutput = reinjectIntoJson(trace.output(), attachments, workspaceId);
                        JsonNode newMetadata = reinjectIntoJson(trace.metadata(), attachments, workspaceId);

                        return trace.toBuilder()
                                .input(newInput)
                                .output(newOutput)
                                .metadata(newMetadata)
                                .build();
                    }).subscribeOn(Schedulers.boundedElastic()));
        });
    }

    /**
     * Reinjects attachments into a span object.
     * Only processes if truncate=false.
     */
    @WithSpan
    public Mono<Span> reinjectAttachments(@NonNull Span span, boolean truncate) {
        if (truncate) {
            return Mono.just(span);
        }

        // Quick check: scan for attachment references before querying DB
        boolean hasRefs = hasAttachmentReferences(span.input(), span.output(), span.metadata());
        if (!hasRefs) {
            return Mono.just(span);
        }

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            UUID entityId = span.id();
            UUID projectId = span.projectId();
            EntityType entityType = EntityType.SPAN;

            // Get attachment info using AttachmentService
            return attachmentService
                    .getAttachmentInfoByEntity(entityId, entityType, projectId)
                    .flatMap(attachments -> {
                        if (attachments.isEmpty()) {
                            return Mono.just(span);
                        }

                        return Mono.fromCallable(() -> {
                            // Reinject attachments into input, output, and metadata
                            JsonNode newInput = reinjectIntoJson(span.input(), attachments, workspaceId);
                            JsonNode newOutput = reinjectIntoJson(span.output(), attachments, workspaceId);
                            JsonNode newMetadata = reinjectIntoJson(span.metadata(), attachments, workspaceId);

                            return span.toBuilder()
                                    .input(newInput)
                                    .output(newOutput)
                                    .metadata(newMetadata)
                                    .build();
                        }).subscribeOn(Schedulers.boundedElastic());
                    });
        });
    }

    /**
     * Quick check to see if any of the JSON nodes contain attachment references.
     * This avoids expensive DB queries when there are no attachments to reinject.
     *
     * @return true if any node contains a string matching the pattern [filename]
     */
    private boolean hasAttachmentReferences(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (containsAttachmentReference(node)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recursively scan a JsonNode to check if it contains any attachment references.
     */
    private boolean containsAttachmentReference(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }

        if (node.isTextual()) {
            String text = node.asText();
            // First check if the text itself is an attachment reference
            if (ATTACHMENT_REF_PATTERN.matcher(text).matches()) {
                return true;
            }
            // If it's a JSON string, try to parse it and check recursively
            if (text.startsWith("{") || text.startsWith("[")) {
                try {
                    JsonNode parsed = objectMapper.readTree(text);
                    return containsAttachmentReference(parsed);
                } catch (Exception e) {
                    // Not valid JSON, ignore
                    return false;
                }
            }
            return false;
        }

        if (node.isObject()) {
            var fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (containsAttachmentReference(node.get(fieldName))) {
                    return true;
                }
            }
            return false;
        }

        if (node.isArray()) {
            for (JsonNode element : node) {
                if (containsAttachmentReference(element)) {
                    return true;
                }
            }
            return false;
        }

        return false;
    }

    /**
     * Recursively processes a JsonNode to find and replace attachment references.
     * When a text node contains an attachment reference like [filename.png], it:
     * 1. Downloads the attachment data from S3/MinIO
     * 2. Encodes it as base64
     * 3. Returns a new text node with the data URL format
     */
    private JsonNode reinjectIntoJson(JsonNode node, List<AttachmentInfo> attachments, String workspaceId) {
        if (node == null || node.isNull()) {
            return node;
        }

        // Handle text nodes - check if they contain attachment references or JSON strings
        if (node.isTextual()) {
            String text = node.asText();
            Matcher matcher = ATTACHMENT_REF_PATTERN.matcher(text);

            if (matcher.matches()) {
                // This is an attachment reference like [filename.png]
                String filename = matcher.group(1);

                // Find the matching attachment
                AttachmentInfo matchingAttachment = attachments.stream()
                        .filter(att -> att.fileName().equals(filename))
                        .findFirst()
                        .orElse(null);

                if (matchingAttachment != null) {
                    try {
                        // Download attachment data
                        InputStream inputStream = attachmentService.downloadAttachment(
                                matchingAttachment, workspaceId);

                        // Read into byte array
                        byte[] data = readAllBytes(inputStream);

                        // Convert to base64
                        String base64 = Base64.getEncoder().encodeToString(data);

                        // Return raw base64 (no data URL prefix) - matching the original input format
                        return objectMapper.getNodeFactory().textNode(base64);
                    } catch (Exception e) {
                        log.error("Failed to reinject attachment '{}': {}", filename, e.getMessage(), e);
                        // Return original reference on error
                        return node;
                    }
                }
            } else if (text.startsWith("{") || text.startsWith("[")) {
                // If it's a JSON string, parse it, process recursively, and return as JSON string
                try {
                    JsonNode parsed = objectMapper.readTree(text);
                    JsonNode processed = reinjectIntoJson(parsed, attachments, workspaceId);
                    // Convert back to JSON string
                    String processedJson = objectMapper.writeValueAsString(processed);
                    return objectMapper.getNodeFactory().textNode(processedJson);
                } catch (Exception e) {
                    log.error("Failed to parse and reinject JSON string: {}", e.getMessage(), e);
                    // Return original text node on error
                    return node;
                }
            }
            return node;
        }

        // Handle object nodes - recursively process all fields
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node.deepCopy();
            objectNode.fieldNames().forEachRemaining(fieldName -> {
                JsonNode fieldValue = objectNode.get(fieldName);
                JsonNode reinjectedValue = reinjectIntoJson(fieldValue, attachments, workspaceId);
                objectNode.set(fieldName, reinjectedValue);
            });
            return objectNode;
        }

        // Handle array nodes - recursively process all elements
        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node.deepCopy();
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode reinjectedValue = reinjectIntoJson(arrayNode.get(i), attachments, workspaceId);
                arrayNode.set(i, reinjectedValue);
            }
            return arrayNode;
        }

        return node;
    }

    /**
     * Read all bytes from an InputStream.
     */
    private byte[] readAllBytes(InputStream inputStream) throws Exception {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] data = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, bytesRead);
            }
            return buffer.toByteArray();
        } finally {
            inputStream.close();
        }
    }
}