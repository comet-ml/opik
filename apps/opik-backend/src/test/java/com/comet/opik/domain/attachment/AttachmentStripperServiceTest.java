package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.S3Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AttachmentStripperServiceTest {

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private S3Config s3Config;

    private ObjectMapper objectMapper;
    private AttachmentStripperService attachmentStripperService;

    // Test data
    private static final UUID TEST_ENTITY_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String TEST_WORKSPACE_ID = "test-workspace";
    private static final String TEST_USER_NAME = "test-user";
    private static final String TEST_PROJECT_NAME = "test-project";

    // Base64 encoded 1x1 PNG image (minimal valid image) - keeping for potential future use
    @SuppressWarnings("unused")
    private static final String SMALL_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChAI9jU77gwAAAABJRU5ErkJggg==";

    // Large base64 string (1000+ chars) - create a valid large PNG by duplicating the header and padding
    private static final String LARGE_PNG_BASE64;
    static {
        // Create a fake PNG with proper PNG header but large enough to trigger our threshold
        byte[] pngHeader = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}; // PNG header
        byte[] largeData = new byte[1500]; // Large enough to exceed threshold
        System.arraycopy(pngHeader, 0, largeData, 0, pngHeader.length);
        // Fill rest with pattern that will compress well
        for (int i = pngHeader.length; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }
        LARGE_PNG_BASE64 = java.util.Base64.getEncoder().encodeToString(largeData);
    }

    // Short base64 string (less than 1000 chars)
    private static final String SHORT_BASE64 = "dGVzdA=="; // "test" in base64

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        // Mock S3Config to return default threshold (lenient to avoid unnecessary stubbing errors)
        lenient().when(s3Config.getStripAttachmentsMinSize()).thenReturn(1000);

        attachmentStripperService = new AttachmentStripperService(
                attachmentService, idGenerator, objectMapper, s3Config);
    }

    @Test
    @DisplayName("Should return null node unchanged")
    void shouldReturnNullNodeUnchanged() {
        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                null, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME);

        // Then
        assertThat(result).isNull();
        verify(attachmentService, never()).uploadAttachment(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Should detect correct MIME type for PNG")
    void shouldDetectCorrectMimeTypeForPng() {
        // Given
        String base64Data = LARGE_PNG_BASE64;

        // When
        String mimeType = attachmentStripperService.detectMimeType(base64Data);

        // Then
        // Should detect as PNG based on the PNG header bytes
        assertThat(mimeType).isEqualTo("image/png");
    }

    @Test
    @DisplayName("Should not process short base64 strings")
    void shouldNotProcessShortBase64Strings() throws Exception {
        // Given
        JsonNode input = objectMapper.readTree("{\"data\": \"" + SHORT_BASE64 + "\"}");

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME);

        // Then
        assertThat(result).isEqualTo(input); // Should be unchanged
        verify(attachmentService, never()).uploadAttachment(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Should process large base64 strings and replace with attachment references")
    void shouldProcessLargeBase64StringsAndReplaceWithReferences() throws Exception {
        // Given
        JsonNode input = objectMapper.readTree("{\"data\": \"" + LARGE_PNG_BASE64 + "\"}");
        // No need to mock idGenerator since we don't use it in the new implementation

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME);

        // Then
        assertThat(result.has("data")).isTrue();
        String replacedValue = result.get("data").asText();
        assertThat(replacedValue).startsWith("[attachment-1.");
        assertThat(replacedValue).endsWith("]");
        assertThat(replacedValue).contains("image/png");

        verify(attachmentService, times(1)).uploadAttachment(
                any(AttachmentInfo.class), any(byte[].class), eq(TEST_WORKSPACE_ID), eq(TEST_USER_NAME));
    }

    @Test
    @DisplayName("Should handle multiple attachments in same JSON")
    void shouldHandleMultipleAttachmentsInSameJson() throws Exception {
        // Given - JSON with multiple large base64 strings
        String jsonWithMultipleAttachments = "{\n" +
                "  \"first_attachment\": \"" + LARGE_PNG_BASE64 + "\",\n" +
                "  \"regular_text\": \"just some text\",\n" +
                "  \"second_attachment\": \"" + LARGE_PNG_BASE64 + "\"\n" +
                "}";

        JsonNode input = objectMapper.readTree(jsonWithMultipleAttachments);
        // No need to mock idGenerator since we don't use it in the new implementation

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME);

        // Then
        // First attachment should be replaced
        String firstAttachment = result.get("first_attachment").asText();
        assertThat(firstAttachment).startsWith("[attachment-1.");
        assertThat(firstAttachment).contains("image/png");

        // Regular text should be unchanged
        assertThat(result.get("regular_text").asText()).isEqualTo("just some text");

        // Second attachment should be replaced with different number
        String secondAttachment = result.get("second_attachment").asText();
        assertThat(secondAttachment).startsWith("[attachment-2.");
        assertThat(secondAttachment).contains("image/png");

        // Should have called upload service twice
        verify(attachmentService, times(2)).uploadAttachment(
                any(AttachmentInfo.class), any(byte[].class), eq(TEST_WORKSPACE_ID), eq(TEST_USER_NAME));
    }

    @Test
    @DisplayName("Should handle nested JSON structures")
    void shouldHandleNestedJsonStructures() throws Exception {
        // Given - Nested JSON with attachment (similar to LiteLLM structure)
        String nestedJson = "{\n" +
                "  \"messages\": [\n" +
                "    {\n" +
                "      \"role\": \"user\",\n" +
                "      \"content\": [\n" +
                "        {\n" +
                "          \"type\": \"text\",\n" +
                "          \"text\": \"What's in this image?\"\n" +
                "        },\n" +
                "        {\n" +
                "          \"type\": \"image\",\n" +
                "          \"inline_data\": {\n" +
                "            \"mime_type\": \"image/png\",\n" +
                "            \"data\": \"" + LARGE_PNG_BASE64 + "\"\n" +
                "          }\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        JsonNode input = objectMapper.readTree(nestedJson);
        // No need to mock idGenerator since we don't use it in the new implementation

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME);

        // Then
        // Navigate to the data field and verify it was replaced
        JsonNode dataField = result.at("/messages/0/content/1/inline_data/data");
        assertThat(dataField.asText()).startsWith("[attachment-1.");
        assertThat(dataField.asText()).contains("image/png");

        // Other fields should be unchanged
        assertThat(result.at("/messages/0/content/0/text").asText()).isEqualTo("What's in this image?");
        assertThat(result.at("/messages/0/content/1/inline_data/mime_type").asText()).isEqualTo("image/png");

        verify(attachmentService, times(1)).uploadAttachment(
                any(AttachmentInfo.class), any(byte[].class), eq(TEST_WORKSPACE_ID), eq(TEST_USER_NAME));
    }

    @Test
    @DisplayName("Should skip text/plain content")
    void shouldSkipTextPlainContent() throws Exception {
        // Given - Large base64 string that decodes to text (not a binary attachment)
        String largeTextBase64 = java.util.Base64.getEncoder().encodeToString(
                "This is a large text content that will be encoded to base64 for testing purposes. ".repeat(50)
                        .getBytes());

        JsonNode input = objectMapper.readTree("{\"data\": \"" + largeTextBase64 + "\"}");

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME);

        // Then
        // Should be unchanged since Tika will detect this as text/plain
        assertThat(result).isEqualTo(input);
        verify(attachmentService, never()).uploadAttachment(any(), any(), anyString(), anyString());
    }
}