package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.EntityType;
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

        // Use OpenTelemetry no-op implementation and EventBus mock for async uploads
        attachmentStripperService = new AttachmentStripperService(
                idGenerator, objectMapper, opikConfiguration, eventBus);
    }

    @Test
    @DisplayName("Should return null node unchanged")
    void shouldReturnNullNodeUnchanged() {
        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                null, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME, "input");

        // Then
        assertThat(result).isNull();
        verify(eventBus, never()).post(any());
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
        verify(eventBus, never()).post(any());
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

        // Verify EventBus.post() was called with AttachmentUploadRequested event (async upload)
        verify(eventBus, times(1)).post(any());
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
        // First attachment should be replaced
        String firstAttachment = result.get("first_attachment").asText();
        assertThat(firstAttachment).matches("\\[output-attachment-1-\\d+\\.png\\]");

        // Regular text should be unchanged
        assertThat(result.get("regular_text").asText()).isEqualTo("just some text");

        // Second attachment should be replaced with different number
        String secondAttachment = result.get("second_attachment").asText();
        assertThat(secondAttachment).matches("\\[output-attachment-2-\\d+\\.pdf\\]");

        // Should have posted 2 async upload events to EventBus
        verify(eventBus, times(2)).post(any());
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
        // Navigate to the data field and verify it was replaced
        JsonNode dataField = result.at("/messages/0/content/1/inline_data/data");

        assertThat(dataField.asText()).matches("\\[metadata-attachment-1-\\d+\\.png\\]");

        // Other fields should be unchanged
        assertThat(result.at("/messages/0/content/0/text").asText()).isEqualTo("What's in this image?");
        assertThat(result.at("/messages/0/content/1/inline_data/mime_type").asText()).isEqualTo("image/png");

        // Verify EventBus.post() was called once for async upload
        verify(eventBus, times(1)).post(any());
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
        // No async upload events should be posted for text content
        verify(eventBus, never()).post(any());
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

        // Then - Each context should generate different filename prefixes
        assertThat(inputResult.get("image").asText()).matches("\\[input-attachment-1-\\d+\\.gif\\]");
        assertThat(outputResult.get("image").asText()).matches("\\[output-attachment-1-\\d+\\.gif\\]");
        assertThat(metadataResult.get("image").asText()).matches("\\[metadata-attachment-1-\\d+\\.gif\\]");

        // Should have posted 3 async upload events to EventBus
        verify(eventBus, times(3)).post(any());
    }
}
