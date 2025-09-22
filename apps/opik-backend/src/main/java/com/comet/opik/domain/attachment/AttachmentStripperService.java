package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.S3Config;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;

import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service responsible for detecting and stripping attachments from trace/span payloads.
 *
 * This service uses a simplified string-based approach:
 * 1. Convert JSON to string
 * 2. Find all base64 strings using regex
 * 3. Process each base64 string with Tika for MIME type detection
 * 4. Replace base64 with attachment references: [attachment-N.mime/type]
 * 5. Convert back to JSON
 *
 * Phase 1: Synchronous processing during trace/span ingestion
 * Phase 2: Asynchronous processing via EventBus (future enhancement)
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class AttachmentStripperService {

    private final @NonNull AttachmentService attachmentService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull S3Config s3Config;

    // Apache Tika for MIME type detection
    private static final Tika tika = new Tika();

    // Lazy-initialized pattern to avoid recompilation
    private volatile Pattern base64Pattern;

    /**
     * Gets the minimum base64 length threshold from configuration.
     */
    private int getMinBase64Length() {
        return s3Config.getStripAttachmentsMinSize();
    }

    /**
     * Gets the base64 pattern, compiling it only once.
     */
    private Pattern getBase64Pattern() {
        if (base64Pattern == null) {
            synchronized (this) {
                if (base64Pattern == null) {
                    int minLength = getMinBase64Length();
                    base64Pattern = Pattern.compile("\"([A-Za-z0-9+/]{" + minLength + ",}={0,2})\"");
                }
            }
        }
        return base64Pattern;
    }

    /**
     * Strips attachments from a JSON payload and returns the processed JSON with attachment references.
     *
     * Uses a simplified string-based approach for maximum simplicity and robustness.
     *
     * @param node the JSON node to process
     * @param entityId the entity ID (trace or span ID) to link attachments to
     * @param entityType the entity type (TRACE or SPAN)
     * @param workspaceId the workspace ID
     * @param userName the user name
     * @param projectName the project name
     * @return processed JSON node with attachments replaced by references
     */
    public JsonNode stripAttachments(JsonNode node,
            UUID entityId,
            EntityType entityType,
            String workspaceId,
            String userName,
            String projectName) {
        if (node == null || node.isNull()) {
            return node;
        }

        try {
            // Step 1: Convert JSON to string
            String jsonString = objectMapper.writeValueAsString(node);

            // Step 2: Process all base64 strings in the JSON
            String processedJson = processBase64InJsonString(
                    jsonString, entityId, entityType, workspaceId, userName, projectName);

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
        }
    }

    /**
     * Processes all base64 strings found in a JSON string and replaces them with attachment references.
     */
    private String processBase64InJsonString(String jsonString,
            UUID entityId,
            EntityType entityType,
            String workspaceId,
            String userName,
            String projectName) {

        // Use the lazy-initialized pattern (compiled only once)
        Matcher matcher = getBase64Pattern().matcher(jsonString);
        StringBuffer result = new StringBuffer();
        int attachmentCounter = 1; // Simple counter, no need for AtomicInteger in single-threaded context

        while (matcher.find()) {
            String base64Data = matcher.group(1); // Extract base64 without quotes

            // Try to process as attachment
            String attachmentReference = processBase64Attachment(
                    base64Data, attachmentCounter,
                    entityId, entityType, workspaceId, userName, projectName);

            if (attachmentReference != null) {
                // Replace the entire quoted base64 string with the reference
                matcher.appendReplacement(result, "\"" + attachmentReference + "\"");
                log.debug("Replaced base64 attachment with reference: {}", attachmentReference);
                attachmentCounter++; // Only increment if we actually processed an attachment
            }
            // If not an attachment, matcher.appendTail() will handle keeping the original
        }

        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Processes a potential base64 attachment string.
     *
     * @param base64Data the base64 string to process
     * @param attachmentNumber the sequential attachment number for this request
     * @return attachment reference string if processed, null if not an attachment
     */
    private String processBase64Attachment(String base64Data,
            int attachmentNumber,
            UUID entityId,
            EntityType entityType,
            String workspaceId,
            String userName,
            String projectName) {
        try {
            // Decode base64 and detect MIME type using Tika
            byte[] bytes = Base64.getDecoder().decode(base64Data);
            String mimeType = tika.detect(bytes);

            // Skip if not a recognizable file type (Tika returns these for non-binary data)
            if ("application/octet-stream".equals(mimeType) || "text/plain".equals(mimeType)) {
                log.debug("Skipping base64 string - detected as {} (not an attachment)", mimeType);
                return null;
            }

            // Generate attachment info
            String fileName = "attachment-" + attachmentNumber;

            // Create AttachmentInfo for upload
            AttachmentInfo attachmentInfo = AttachmentInfo.builder()
                    .fileName(fileName)
                    .mimeType(mimeType)
                    .entityType(entityType)
                    .entityId(entityId)
                    .projectName(projectName)
                    .build();

            // Upload attachment synchronously (Phase 1)
            attachmentService.uploadAttachment(attachmentInfo, bytes, workspaceId, userName);

            log.info("Successfully processed attachment: fileName='{}', type='{}', size='{}' bytes",
                    fileName, mimeType, bytes.length);

            // Return attachment reference in the format: [fileName.mimeType]
            return "[" + fileName + "." + mimeType + "]";

        } catch (IllegalArgumentException e) {
            // Not valid base64, ignore silently
            log.debug("String is not valid base64, skipping attachment processing: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Error processing potential attachment (attachment #{}): {}", attachmentNumber, e.getMessage());
            return null;
        }
    }

    /**
     * Detects MIME type of base64 data for testing purposes.
     */
    public String detectMimeType(String base64Data) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Data);
            return tika.detect(bytes);
        } catch (Exception e) {
            return "unknown";
        }
    }
}