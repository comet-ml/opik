package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility class for attachment-related operations.
 * Contains shared constants and helper methods used across attachment processing.
 */
@UtilityClass
public class AttachmentUtils {

    /**
     * Template for S3 key generation for attachments.
     */
    public static final String KEY_TEMPLATE = "opik/attachment/workspaces/{workspaceId}/projects/{projectId}/{entity_type}s/{entity_id}/files/{file_name}";

    /**
     * Base pattern for attachment filenames without anchors or brackets.
     * Format: {context}-attachment-{number}-{timestamp}.{extension}
     * Where context is one of: input, output, metadata
     * Uses non-capturing group (?:...) to avoid interfering with other capture groups.
     *
     * Example: input-attachment-1-1234567890.jpg
     */
    private static final String ATTACHMENT_FILENAME_BASE_PATTERN = "(?:input|output|metadata)-attachment-\\d+-\\d+\\.\\w+";

    /**
     * A regex pattern that identifies valid filenames for Python SDK-extracted attachments.
     *
     * The pattern matches filenames that adhere to the following format:
     * - Begin with either "input", "output", or "metadata".
     * - Include the suffix "-attachment" followed by two numeric segments separated by a hyphen.
     * - Optionally end with "-sdk".
     * - Have a file extension starting with a period followed by alphanumeric characters.
     *
     * Example valid filenames:
     * - input-attachment-1-1234567890.png
     * - output-attachment-5-1234567890-sdk.json
     */
    private static final String ATTACHMENT_FILENAME_SDK_SUPPORT_PATTERN = "(?:input|output|metadata)-attachment-\\d+-\\d+(?:-sdk)?\\.\\w+";

    /**
     * Pattern for validating auto-stripped attachment filenames (whole string match).
     * Uses anchors (^$) to ensure the entire string matches the pattern.
     */
    private static final String AUTO_STRIPPED_FILENAME_PATTERN = "^" + ATTACHMENT_FILENAME_BASE_PATTERN + "$";

    /**
     * Compiled pattern for validating auto-stripped attachment filenames.
     */
    private static final Pattern AUTO_STRIPPED_PATTERN = Pattern.compile(AUTO_STRIPPED_FILENAME_PATTERN);

    /**
     * Pattern for finding attachment references in JSON strings (with brackets).
     * No anchors - used with .find() to locate references anywhere in text.
     * Group 1 captures the filename without brackets.
     *
     * Examples:
     *  - [input-attachment-1-1234567890.jpg]
     *  - [metadata-attachment-1-1704067199000-sdk.pdf]
     *  - "some text [input-attachment-1-123.png] more text"
     */
    private static final Pattern FIND_REFERENCE_PATTERN = Pattern
            .compile("\\[(" + ATTACHMENT_FILENAME_SDK_SUPPORT_PATTERN + ")\\]");

    /**
     * Checks if a filename matches the auto-stripped attachment pattern.
     *
     * @param fileName the filename to check
     * @return true if the filename matches the auto-stripped pattern, false otherwise
     */
    public static boolean isAutoStrippedAttachment(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return AUTO_STRIPPED_PATTERN.matcher(fileName).matches();
    }

    /**
     * Filters a list of attachments to only include auto-stripped attachments.
     *
     * @param attachments the list of attachments to filter
     * @return a filtered list containing only auto-stripped attachments
     */
    public static List<AttachmentInfo> filterAutoStrippedAttachments(List<AttachmentInfo> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }

        return attachments.stream()
                .filter(att -> isAutoStrippedAttachment(att.fileName()))
                .collect(Collectors.toList());
    }

    /**
     * Extracts all unique attachment references from a JSON string.
     * Finds all patterns matching [filename.ext] where filename follows the auto-stripped format.
     *
     * @param jsonString the JSON string to search for attachment references
     * @return a list of unique attachment filenames (without brackets)
     */
    public static List<String> extractAttachmentReferences(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return List.of();
        }

        List<String> references = new ArrayList<>();
        Matcher matcher = FIND_REFERENCE_PATTERN.matcher(jsonString);

        while (matcher.find()) {
            String filename = matcher.group(1); // Extract filename without brackets
            if (!references.contains(filename)) {
                references.add(filename);
            }
        }

        return references;
    }

    /**
     * Wraps a filename in brackets to create an attachment reference.
     *
     * @param filename the filename to wrap (e.g., "input-attachment-1-123.png")
     * @return the wrapped reference (e.g., "[input-attachment-1-123.png]")
     */
    public static String wrapReference(String filename) {
        if (filename == null || filename.isEmpty()) {
            return filename;
        }
        return "[" + filename + "]";
    }

    /**
     * Recursively scans a JsonNode to check if it contains any attachment references.
     * Handles TextNodes that contain JSON strings by parsing and recursing into them.
     *
     * @param node the JsonNode to scan
     * @param objectMapper the ObjectMapper to use for parsing JSON strings
     * @return true if the node or any of its children contain attachment references
     */
    public static boolean containsAttachmentReference(@NonNull JsonNode node, @NonNull ObjectMapper objectMapper) {
        if (node.isNull()) {
            return false;
        }

        if (node.isTextual()) {
            String text = node.asText();
            // First check if the text itself is an attachment reference
            if (FIND_REFERENCE_PATTERN.matcher(text).find()) {
                return true;
            }
            // If it's a JSON string, try to parse it and check recursively
            if (text.startsWith("{") || text.startsWith("[")) {
                try {
                    JsonNode parsed = objectMapper.readTree(text);
                    return containsAttachmentReference(parsed, objectMapper);
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
                if (containsAttachmentReference(node.get(fieldName), objectMapper)) {
                    return true;
                }
            }
            return false;
        }

        if (node.isArray()) {
            for (JsonNode element : node) {
                if (containsAttachmentReference(element, objectMapper)) {
                    return true;
                }
            }
            return false;
        }

        return false;
    }

    /**
     * Quick check to see if any of the provided JSON nodes contain attachment references.
     * This is useful for optimization - avoiding expensive operations when there are no attachments to process.
     *
     * @param objectMapper the ObjectMapper to use for parsing JSON strings
     * @param nodes the JsonNodes to check
     * @return true if any node contains attachment references
     */
    public static boolean hasAttachmentReferences(@NonNull ObjectMapper objectMapper, JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && containsAttachmentReference(node, objectMapper)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses a MIME type string into category and subtype components.
     *
     * @param mimeType the MIME type string (e.g., "image/png", "application/pdf")
     * @return a String array where [0] is the category and [1] is the subtype
     *         Returns ["unknown", "unknown"] if the MIME type is null, empty, or malformed
     */
    public static String[] parseMimeType(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return new String[]{"unknown", "unknown"};
        }

        String[] parts = mimeType.split("/", 2);
        if (parts.length != 2) {
            return new String[]{"unknown", "unknown"};
        }

        return parts;
    }

    /**
     * Calculates the decoded byte size from a base64 string.
     * Uses the base64 encoding ratio: 4 base64 chars = 3 bytes of data.
     * Accounts for padding characters (=) at the end.
     *
     * @param base64String the base64-encoded string
     * @return the estimated decoded size in bytes
     */
    public static long calculateBase64DecodedSize(String base64String) {
        if (base64String == null || base64String.isEmpty()) {
            return 0;
        }

        long base64Length = base64String.length();
        long paddingCount = 0;

        if (base64String.endsWith("==")) {
            paddingCount = 2;
        } else if (base64String.endsWith("=")) {
            paddingCount = 1;
        }

        return (base64Length * 3) / 4 - paddingCount;
    }
}
