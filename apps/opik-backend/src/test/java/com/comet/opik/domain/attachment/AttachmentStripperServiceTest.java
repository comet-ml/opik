package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.events.AttachmentUploadRequested;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.S3Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static com.comet.opik.utils.AttachmentPayloadUtilsTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentStripperServiceTest {

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private S3Config s3Config;

    @Mock
    private OpikConfiguration opikConfiguration;

    @Mock
    private EventBus eventBus;

    private ObjectMapper objectMapper;
    private AttachmentStripperService attachmentStripperService;

    // Test data
    private static final UUID TEST_ENTITY_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String TEST_WORKSPACE_ID = "test-workspace";
    private static final String TEST_USER_NAME = "test-user";
    private static final String TEST_PROJECT_NAME = "test-project";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        // Mock S3Config to return default threshold
        when(s3Config.getStripAttachmentsMinSize()).thenReturn(5000);
        lenient().when(s3Config.isMinIO()).thenReturn(true); // Use MinIO mode for unit tests (direct upload)

        // Mock OpikConfiguration to return the S3Config
        when(opikConfiguration.getS3Config()).thenReturn(s3Config);

        // Mock EventBus - no setup needed, just verify posts

        // Use OpenTelemetry no-op implementation - no mocking needed!
        attachmentStripperService = new AttachmentStripperService(
                attachmentService, idGenerator, objectMapper, opikConfiguration, eventBus);
    }

    @Test
    @DisplayName("Should return null node unchanged")
    void shouldReturnNullNodeUnchanged() {
        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                null, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME, "input");

        // Then
        assertThat(result).isNull();
        // Should not have posted any events to EventBus (null input)
        verify(eventBus, never()).post(any(AttachmentUploadRequested.class));
    }

    @Test
    @DisplayName("Should detect correct MIME type for PNG")
    void shouldDetectCorrectMimeTypeForPng() {
        // Given
        String base64Data = createLargePngBase64();

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
        JsonNode input = objectMapper.readTree("{\"data\": \"" + createShortBase64() + "\"}");

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME, "input");

        // Then
        assertThat(result).isEqualTo(input); // Should be unchanged
        // Should not have posted any events to EventBus (small data below threshold)
        verify(eventBus, never()).post(any(AttachmentUploadRequested.class));
    }

    @Test
    @DisplayName("Should process large base64 strings and replace with attachment references")
    void shouldProcessLargeBase64StringsAndReplaceWithReferences() throws Exception {
        // Given
        JsonNode metadata = objectMapper.readTree("{\"data\": \"" + createLargePngBase64() + "\"}");
        // No need to mock idGenerator since we don't use it in the new implementation

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                metadata, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "metadata");

        // Then
        assertThat(result.has("data")).isTrue();
        String replacedValue = result.get("data").asText();
        assertThat(replacedValue).matches("\\[metadata-attachment-1-\\d+\\.png\\]");

        // Should have posted one event to EventBus
        verify(eventBus, times(1)).post(any(AttachmentUploadRequested.class));
    }

    @Test
    @DisplayName("Should handle multiple attachments in same JSON")
    void shouldHandleMultipleAttachmentsInSameJson() throws Exception {
        // Given - JSON with multiple large base64 strings
        String jsonWithMultipleAttachments = "{\n" +
                "  \"first_attachment\": \"" + createLargePngBase64() + "\",\n" +
                "  \"regular_text\": \"just some text\",\n" +
                "  \"second_attachment\": \"" + createLargePdfBase64() + "\"\n" +
                "}";

        JsonNode input = objectMapper.readTree(jsonWithMultipleAttachments);
        // No need to mock idGenerator since we don't use it in the new implementation

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "output");

        // Then
        // First attachment should be replaced (with timestamp)
        String firstAttachment = result.get("first_attachment").asText();
        assertThat(firstAttachment).matches("\\[output-attachment-1-\\d+\\.png\\]");

        // Regular text should be unchanged
        assertThat(result.get("regular_text").asText()).isEqualTo("just some text");

        // Second attachment should be replaced with different number (with timestamp)
        String secondAttachment = result.get("second_attachment").asText();
        assertThat(secondAttachment).matches("\\[output-attachment-2-\\d+\\.pdf\\]");

        // Should have posted two events to EventBus (one for each attachment)
        verify(eventBus, times(2)).post(any(AttachmentUploadRequested.class));
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
                "            \"data\": \"" + createLargePngBase64() + "\"\n" +
                "          }\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        JsonNode metadata = objectMapper.readTree(nestedJson);
        // No need to mock idGenerator since we don't use it in the new implementation

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                metadata, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "metadata");

        // Then
        // Navigate to the data field and verify it was replaced (with timestamp)
        JsonNode dataField = result.at("/messages/0/content/1/inline_data/data");

        assertThat(dataField.asText()).matches("\\[metadata-attachment-1-\\d+\\.png\\]");

        // Other fields should be unchanged
        assertThat(result.at("/messages/0/content/0/text").asText()).isEqualTo("What's in this image?");
        assertThat(result.at("/messages/0/content/1/inline_data/mime_type").asText()).isEqualTo("image/png");

        // Should have posted one event to EventBus
        verify(eventBus, times(1)).post(any(AttachmentUploadRequested.class));
    }

    @Test
    @DisplayName("Should skip text/plain content")
    void shouldSkipTextPlainContent() throws Exception {
        // Given - Large base64 string that decodes to text (not a binary attachment)
        String largeTextBase64 = java.util.Base64.getEncoder().encodeToString(
                "This is a large text content that will be encoded to base64 for testing purposes. ".repeat(50)
                        .getBytes());

        JsonNode output = objectMapper.readTree("{\"data\": \"" + largeTextBase64 + "\"}");

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                output, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "output");

        // Then
        // Should be unchanged since Tika will detect this as text/plain
        assertThat(result).isEqualTo(output);
        // Should not have posted any events to EventBus (text/plain content)
        verify(eventBus, never()).post(any(AttachmentUploadRequested.class));
    }

    @Test
    @DisplayName("Should generate context-aware filenames for different contexts")
    void shouldGenerateContextAwareFilenames() throws Exception {
        // Given
        JsonNode input = objectMapper.readTree("{\"image\": \"" + createLargeGifBase64() + "\"}");

        // When - Test with different contexts
        JsonNode inputResult = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "input");

        JsonNode outputResult = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "output");

        JsonNode metadataResult = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "metadata");

        // Then - Each context should generate different filename prefixes (with timestamps)
        assertThat(inputResult.get("image").asText()).matches("\\[input-attachment-1-\\d+\\.gif\\]");
        assertThat(outputResult.get("image").asText()).matches("\\[output-attachment-1-\\d+\\.gif\\]");
        assertThat(metadataResult.get("image").asText()).matches("\\[metadata-attachment-1-\\d+\\.gif\\]");

        // Should have posted three events to EventBus (once for each context)
        verify(eventBus, times(3)).post(any(AttachmentUploadRequested.class));
    }

    // Helper methods to create test data
    private String createLargePngBase64() {
        // Create a large base64 string representing a PNG image (> 5000 chars)
        // This is a minimal PNG header + data to make it detectable as image/png by Tika
        byte[] pngHeader = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, // IHDR chunk header
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, // 1x1 pixel
                0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53, (byte) 0xDE // IHDR data + CRC
        };

        // Pad with additional data to exceed the minimum length
        byte[] paddedData = new byte[4000];
        System.arraycopy(pngHeader, 0, paddedData, 0, pngHeader.length);

        return java.util.Base64.getEncoder().encodeToString(paddedData);
    }

    private String createLargeGifBase64() {
        // Create a large base64 string representing a GIF image
        byte[] gifHeader = new byte[]{
                0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // GIF89a signature
                0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00 // minimal GIF data
        };

        // Pad with additional data to exceed the minimum length
        byte[] paddedData = new byte[4000];
        System.arraycopy(gifHeader, 0, paddedData, 0, gifHeader.length);

        return java.util.Base64.getEncoder().encodeToString(paddedData);
    }

    private String createLargePdfBase64() {
        // Create a large base64 string representing a PDF
        byte[] pdfHeader = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj 3 0 obj<</Type/Page/MediaBox[0 0 612 792]/Parent 2 0 R>>endobj xref 0 4 0000000000 65535 f 0000000010 00000 n 0000000053 00000 n 0000000100 00000 n trailer<</Size 4/Root 1 0 R>>startxref 149 %%EOF"
                .getBytes();

        // Pad with additional data to exceed the minimum length
        byte[] paddedData = new byte[4000];
        System.arraycopy(pdfHeader, 0, paddedData, 0, Math.min(pdfHeader.length, paddedData.length));

        return java.util.Base64.getEncoder().encodeToString(paddedData);
    }

    private String createShortBase64() {
        // Create a short base64 string (< 5000 chars) that should not be processed
        return java.util.Base64.getEncoder().encodeToString("short content".getBytes());
    }
}
