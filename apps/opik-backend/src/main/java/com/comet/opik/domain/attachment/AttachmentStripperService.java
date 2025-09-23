package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.attachment.MultipartUploadPart;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadResponse;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.S3Config;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Collections;
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
 * 4. Upload valid attachments to S3/MinIO (using multipart upload for S3, direct for MinIO)
 * 5. Replace base64 strings in the JSON with references to the uploaded attachments
 *
 * Filenames are generated to include context (input, output, metadata) to prevent naming conflicts.
 */
@Singleton
@Slf4j
public class AttachmentStripperService {

    private final @NonNull AttachmentService attachmentService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull S3Config s3Config;

    // Apache Tika for MIME type detection
    private static final Tika tika = new Tika();

    // Base64 pattern compiled once during construction
    private final Pattern base64Pattern;

    @Inject
    public AttachmentStripperService(@NonNull AttachmentService attachmentService,
            @NonNull IdGenerator idGenerator,
            @NonNull ObjectMapper objectMapper,
            @NonNull S3Config s3Config) {
        this.attachmentService = attachmentService;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.s3Config = s3Config;

        // Compile the regex pattern once during construction based on configuration
        int minLength = s3Config.getStripAttachmentsMinSize();
        this.base64Pattern = Pattern.compile("([A-Za-z0-9+/]{" + minLength + ",}={0,2})");

        log.info("AttachmentStripperService initialized with minBase64Length: {}", minLength);
    }

    /**
     * Strips attachments from a JSON payload and returns the processed JSON with attachment references.
     *
     * Uses a simplified string-based approach for maximum simplicity and robustness.
     * Attachments are uploaded using either direct upload (MinIO) or multipart upload (S3) based on configuration.
     * Generated filenames include context to avoid conflicts between input, output, and metadata attachments.
     *
     * @param node the JSON node to process
     * @param entityId the entity ID (trace or span ID) to link attachments to
     * @param entityType the entity type (TRACE or SPAN)
     * @param workspaceId the workspace ID
     * @param userName the user name
     * @param projectName the project name
     * @param context the context where attachments are found (input, output, metadata)
     * @return processed JSON node with attachments replaced by references
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
        }
    }

    /**
     * Processes all base64 strings found in a JSON string and replaces them with attachment references.
     *
     * Uses regex to find base64 strings longer than the minimum threshold, processes each one
     * as a potential attachment, and replaces valid attachments with reference strings.
     *
     * @param jsonString the JSON string to process
     * @param entityId the entity ID (trace or span ID) to link attachments to
     * @param entityType the entity type (TRACE or SPAN)
     * @param workspaceId the workspace ID
     * @param userName the user name
     * @param projectName the project name
     * @param context the context where attachments are found (input, output, metadata)
     * @return the processed JSON string with attachment references
     */
    private String processBase64InJsonString(String jsonString,
            UUID entityId,
            EntityType entityType,
            String workspaceId,
            String userName,
            String projectName,
            String context) {

        // Use the pre-compiled pattern from construction
        Matcher matcher = base64Pattern.matcher(jsonString);
        StringBuffer result = new StringBuffer();
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
     * Decodes the base64 data, uses Apache Tika to detect the MIME type, and uploads the attachment
     * using either direct upload (MinIO) or multipart upload (S3) based on configuration.
     * The generated filename includes the context prefix to avoid conflicts.
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
                log.debug("Skipping base64 string - detected as {} (not an attachment)", mimeType);
                return null;
            }

            // Generate attachment info with appropriate extension and context
            String extension = getFileExtension(mimeType);
            String fileName = context + "-attachment-" + attachmentNumber + "." + extension;

            // Create AttachmentInfo for upload
            AttachmentInfo attachmentInfo = AttachmentInfo.builder()
                    .fileName(fileName)
                    .mimeType(mimeType)
                    .entityType(entityType)
                    .entityId(entityId)
                    .projectName(projectName)
                    .build();

            // Upload attachment using appropriate method based on configuration
            if (s3Config.isMinIO()) {
                // For MinIO, use direct upload
                attachmentService.uploadAttachment(attachmentInfo, bytes, workspaceId, userName);
            } else {
                // For S3, use multipart upload
                uploadAttachmentViaMultipart(attachmentInfo, bytes, workspaceId, userName);
            }

            log.info("Successfully processed attachment: fileName='{}', type='{}', size='{}' bytes",
                    fileName, mimeType, bytes.length);

            log.debug("Replaced base64 attachment with attachment name: {}", fileName);
            return "[" + fileName + "]";

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
            log.debug("Unknown MIME type: {}, using fallback extension", mimeType);

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

    /**
     * Upload attachment via multipart upload flow for S3.
     *
     * This method implements the complete S3 multipart upload flow:
     * 1. Starts a multipart upload to get presigned URLs
     * 2. Uploads the data to the presigned URL using HTTP PUT
     * 3. Completes the multipart upload with the ETag from the upload response
     *
     * @param attachmentInfo the attachment information including filename, MIME type, and entity details
     * @param bytes the binary data to upload
     * @param workspaceId the workspace ID
     * @param userName the user name
     * @throws Exception if any step of the multipart upload fails
     */
    private void uploadAttachmentViaMultipart(AttachmentInfo attachmentInfo, byte[] bytes,
            String workspaceId, String userName) throws Exception {
        // Step 1: Start multipart upload
        StartMultipartUploadRequest startRequest = StartMultipartUploadRequest.builder()
                .fileName(attachmentInfo.fileName())
                .mimeType(attachmentInfo.mimeType())
                .entityType(attachmentInfo.entityType())
                .entityId(attachmentInfo.entityId())
                .projectName(attachmentInfo.projectName())
                .numOfFileParts(1) // Single part since we have all data in memory
                .path("placeholder")
                .build();

        StartMultipartUploadResponse startResponse = attachmentService.startMultiPartUpload(startRequest, workspaceId,
                userName);

        // Step 2: Upload the data to the presigned URL and get the ETag
        String uploadUrl = startResponse.preSignUrls().get(0); // First (and only) URL
        String eTag = uploadDataToPresignedUrl(uploadUrl, bytes);

        // Step 3: Complete multipart upload with the actual ETag
        MultipartUploadPart part = MultipartUploadPart.builder()
                .partNumber(1)
                .eTag(eTag) // Use the actual ETag from the upload response
                .build();

        CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                .fileName(attachmentInfo.fileName())
                .projectName(attachmentInfo.projectName())
                .entityType(attachmentInfo.entityType())
                .entityId(attachmentInfo.entityId())
                .fileSize((long) bytes.length)
                .mimeType(attachmentInfo.mimeType())
                .uploadId(startResponse.uploadId())
                .uploadedFileParts(Collections.singletonList(part))
                .build();

        attachmentService.completeMultiPartUpload(completeRequest, workspaceId, userName);

        log.debug("Completed multipart upload for attachment: {}", attachmentInfo.fileName());
    }

    /**
     * Upload data to a presigned URL using HTTP PUT.
     *
     * Performs a synchronous HTTP PUT request to upload binary data to the provided presigned URL.
     * The ETag from the response headers is required for completing the multipart upload.
     *
     * @param presignedUrl the presigned URL to upload to
     * @param data the binary data to upload
     * @return the ETag from the upload response headers
     * @throws IOException if the HTTP request fails
     * @throws InterruptedException if the HTTP request is interrupted
     */
    private String uploadDataToPresignedUrl(String presignedUrl, byte[] data) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(presignedUrl))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException(
                    "Failed to upload data to presigned URL: " + response.statusCode() + " " + response.body());
        }

        // Extract and return the ETag from response headers
        String eTag = response.headers().firstValue("ETag").orElseThrow(
                () -> new IOException("ETag not found in upload response headers"));

        log.debug("Successfully uploaded data to presigned URL, ETag: {}", eTag);
        return eTag;
    }
}
