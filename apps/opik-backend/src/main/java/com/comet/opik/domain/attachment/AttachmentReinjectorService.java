package com.comet.opik.domain.attachment;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.IOUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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

    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull AttachmentService attachmentService;

    /**
     * Reinjects attachments into a trace object.
     * Only processes if truncate=false.
     */
    @WithSpan
    public Mono<Trace> reinjectAttachments(@NonNull Trace trace, boolean reinjectAttachments) {
        if (!reinjectAttachments) {
            return Mono.just(trace);
        }

        // Quick check: scan for attachment references before querying DB
        boolean hasRefs = AttachmentUtils.hasAttachmentReferences(objectMapper, trace.input(), trace.output(),
                trace.metadata());
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
                    .flatMap(attachments -> {
                        if (CollectionUtils.isEmpty(attachments)) {
                            return Mono.just(trace);
                        }

                        // Reinject attachments into input, output, and metadata in parallel
                        Mono<JsonNode> inputMono = reinjectIntoJson(trace.input(), attachments, workspaceId);
                        Mono<JsonNode> outputMono = reinjectIntoJson(trace.output(), attachments, workspaceId);
                        Mono<JsonNode> metadataMono = reinjectIntoJson(trace.metadata(), attachments, workspaceId);

                        return Mono.zip(inputMono, outputMono, metadataMono)
                                .map(tuple -> trace.toBuilder()
                                        .input(tuple.getT1())
                                        .output(tuple.getT2())
                                        .metadata(tuple.getT3())
                                        .build());
                    });
        });
    }

    /**
     * Reinjects attachments into a span object.
     * Only processes if truncate=false.
     */
    @WithSpan
    public Mono<Span> reinjectAttachments(@NonNull Span span, boolean reinjectAttachments) {
        if (!reinjectAttachments) {
            return Mono.just(span);
        }

        // Quick check: scan for attachment references before querying DB
        boolean hasRefs = AttachmentUtils.hasAttachmentReferences(objectMapper, span.input(), span.output(),
                span.metadata());
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
                        if (CollectionUtils.isEmpty(attachments)) {
                            return Mono.just(span);
                        }

                        // Reinject attachments into input, output, and metadata in parallel
                        Mono<JsonNode> inputMono = reinjectIntoJson(span.input(), attachments, workspaceId);
                        Mono<JsonNode> outputMono = reinjectIntoJson(span.output(), attachments, workspaceId);
                        Mono<JsonNode> metadataMono = reinjectIntoJson(span.metadata(), attachments, workspaceId);

                        return Mono.zip(inputMono, outputMono, metadataMono)
                                .map(tuple -> span.toBuilder()
                                        .input(tuple.getT1())
                                        .output(tuple.getT2())
                                        .metadata(tuple.getT3())
                                        .build());
                    });
        });
    }

    /**
     * Async version: Find and replace attachment references in a JSON string.
     * Converts JsonNode to string, finds all [filename.ext] references, downloads
     * attachments asynchronously, and replaces references with base64 data.
     * Much simpler than recursive approach - just string find-and-replace!
     */
    private Mono<JsonNode> reinjectIntoJson(JsonNode node, List<AttachmentInfo> attachments,
            String workspaceId) {
        if (node == null || node.isNull()) {
            // Return a NullNode instead of null to avoid NPE in Mono.just()
            return Mono.just(objectMapper.getNodeFactory().nullNode());
        }

        // Convert JsonNode to string
        String jsonString = node.toString();

        // Extract all attachment references using AttachmentUtils
        List<String> references = AttachmentUtils.extractAttachmentReferences(jsonString);

        // If no references found, return original node
        if (references.isEmpty()) {
            return Mono.just(node);
        }

        // Download all attachments asynchronously in parallel
        List<Mono<Tuple2<String, String>>> downloadMonos = references.stream()
                .map(filename -> {
                    // Find matching attachment
                    AttachmentInfo attachInfo = attachments.stream()
                            .filter(att -> att.fileName().equals(filename))
                            .findFirst()
                            .orElse(null);

                    if (attachInfo == null) {
                        // Return reference as-is if attachment not found
                        log.warn("[Attachment not found] filename {} not found in attachments", filename);
                        String wrappedRef = AttachmentUtils.wrapReference(filename);
                        return Mono.just(Tuples.of(wrappedRef, wrappedRef));
                    }

                    // Async download and conversion to base64
                    return Mono.fromCallable(() -> attachmentService.downloadAttachment(attachInfo, workspaceId))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(this::readAllBytesAsync)
                            .map(data -> {
                                String base64 = Base64.getEncoder().encodeToString(data);
                                String wrappedRef = AttachmentUtils.wrapReference(filename);
                                return Tuples.of(wrappedRef, base64);
                            })
                            .onErrorResume(e -> {
                                log.error("Failed to download attachment: {}", filename, e);
                                // Return reference as-is if download fails
                                String wrappedRef = AttachmentUtils.wrapReference(filename);
                                return Mono.just(Tuples.of(wrappedRef, wrappedRef));
                            });
                })
                .collect(Collectors.toList());

        // Wait for all downloads to complete and replace references
        return Mono.zip(downloadMonos, results -> {
            String modifiedJson = jsonString;

            for (Object result : results) {
                @SuppressWarnings("unchecked")
                Tuple2<String, String> tuple = (Tuple2<String, String>) result;
                String reference = tuple.getT1();
                String base64 = tuple.getT2();

                // Replace all occurrences of this reference with base64
                // The reference is already wrapped in brackets, just replace it with the base64 data
                modifiedJson = modifiedJson.replace(reference, base64);
            }

            try {
                // Parse the modified JSON string back to JsonNode
                return objectMapper.readTree(modifiedJson);
            } catch (Exception e) {
                log.error("Failed to parse modified JSON, returning original", e);
                return node;
            }
        });
    }

    /**
     * Asynchronously read all bytes from an InputStream.
     * Offloads the blocking I/O operation to the boundedElastic scheduler.
     */
    private Mono<byte[]> readAllBytesAsync(InputStream inputStream) {
        return Mono.fromCallable(() -> IOUtils.toByteArray(inputStream))
                .subscribeOn(Schedulers.boundedElastic());
    }
}