package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.S3Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentStripperServiceTest {

    // Helper classes for mocking OpenTelemetry builders
    private static class MockLongCounterBuilder implements io.opentelemetry.api.metrics.LongCounterBuilder {
        @Override
        public io.opentelemetry.api.metrics.LongCounterBuilder setDescription(String description) {
            return this;
        }

        @Override
        public io.opentelemetry.api.metrics.LongCounterBuilder setUnit(String unit) {
            return this;
        }

        @Override
        public LongCounter build() {
            return new LongCounter() {
                @Override
                public void add(long value) {
                    // No-op for tests
                }

                @Override
                public void add(long value, io.opentelemetry.api.common.Attributes attributes) {
                    // No-op for tests
                }
            };
        }
    }

    private static class MockLongHistogramBuilder implements io.opentelemetry.api.metrics.LongHistogramBuilder {
        @Override
        public io.opentelemetry.api.metrics.LongHistogramBuilder setDescription(String description) {
            return this;
        }

        @Override
        public io.opentelemetry.api.metrics.LongHistogramBuilder setUnit(String unit) {
            return this;
        }

        @Override
        public LongHistogram build() {
            return new LongHistogram() {
                @Override
                public void record(long value) {
                    // No-op for tests
                }

                @Override
                public void record(long value, io.opentelemetry.api.common.Attributes attributes) {
                    // No-op for tests
                }
            };
        }

        @Override
        public io.opentelemetry.api.metrics.LongHistogramBuilder ofLongs() {
            return this;
        }
    }

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private S3Config s3Config;

    @Mock
    private OpenTelemetry openTelemetry;

    @Mock
    private Meter meter;

    @Mock
    private LongCounter longCounter;

    @Mock
    private LongHistogram longHistogram;

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
        when(s3Config.getStripAttachmentsMinSize()).thenReturn(1000);
        lenient().when(s3Config.isMinIO()).thenReturn(true); // Use MinIO mode for unit tests (direct upload)

        // Mock direct upload for MinIO mode
        lenient().doNothing().when(attachmentService).uploadAttachment(any(AttachmentInfo.class), any(byte[].class),
                anyString(), anyString());

        // Mock OpenTelemetry metrics
        lenient().when(openTelemetry.getMeter("opik.attachments")).thenReturn(meter);
        lenient().when(meter.counterBuilder(anyString())).thenReturn(new MockLongCounterBuilder());

        // Create a proper mock for histogram builder that returns the right type
        @SuppressWarnings("unchecked")
        io.opentelemetry.api.metrics.DoubleHistogramBuilder doubleHistogramBuilder = (io.opentelemetry.api.metrics.DoubleHistogramBuilder) org.mockito.Mockito
                .mock(io.opentelemetry.api.metrics.DoubleHistogramBuilder.class);
        lenient().when(doubleHistogramBuilder.setDescription(anyString())).thenReturn(doubleHistogramBuilder);
        lenient().when(doubleHistogramBuilder.setUnit(anyString())).thenReturn(doubleHistogramBuilder);
        lenient().when(doubleHistogramBuilder.ofLongs()).thenReturn(new MockLongHistogramBuilder());

        lenient().when(meter.histogramBuilder(anyString())).thenReturn(doubleHistogramBuilder);

        attachmentStripperService = new AttachmentStripperService(
                attachmentService, idGenerator, objectMapper, s3Config, openTelemetry);
    }

    @Test
    @DisplayName("Should return null node unchanged")
    void shouldReturnNullNodeUnchanged() {
        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                null, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME, "input");

        // Then
        assertThat(result).isNull();
        verify(attachmentService, never()).uploadAttachment(any(), any(), anyString(), anyString());
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
        verify(attachmentService, never()).uploadAttachment(any(), any(), anyString(), anyString());
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
        assertThat(replacedValue).startsWith("[metadata-attachment-1.");
        assertThat(replacedValue).endsWith("]");
        assertThat(replacedValue).isEqualTo("[metadata-attachment-1.png]");

        verify(attachmentService, times(1)).uploadAttachment(any(AttachmentInfo.class), any(byte[].class), anyString(),
                anyString());
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
        assertThat(firstAttachment).isEqualTo("[output-attachment-1.png]");

        // Regular text should be unchanged
        assertThat(result.get("regular_text").asText()).isEqualTo("just some text");

        // Second attachment should be replaced with different number
        String secondAttachment = result.get("second_attachment").asText();
        assertThat(secondAttachment).isEqualTo("[output-attachment-2.pdf]");

        // Should have called upload service twice
        verify(attachmentService, times(2)).uploadAttachment(any(AttachmentInfo.class), any(byte[].class), anyString(),
                anyString());
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

        assertThat(dataField.asText()).isEqualTo("[metadata-attachment-1.png]");

        // Other fields should be unchanged
        assertThat(result.at("/messages/0/content/0/text").asText()).isEqualTo("What's in this image?");
        assertThat(result.at("/messages/0/content/1/inline_data/mime_type").asText()).isEqualTo("image/png");

        verify(attachmentService, times(1)).uploadAttachment(any(AttachmentInfo.class), any(byte[].class), anyString(),
                anyString());
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
        verify(attachmentService, never()).uploadAttachment(any(), any(), anyString(), anyString());
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
        assertThat(inputResult.get("image").asText()).isEqualTo("[input-attachment-1.gif]");
        assertThat(outputResult.get("image").asText()).isEqualTo("[output-attachment-1.gif]");
        assertThat(metadataResult.get("image").asText()).isEqualTo("[metadata-attachment-1.gif]");

        // Should have called upload service three times (once for each context)
        verify(attachmentService, times(3)).uploadAttachment(any(AttachmentInfo.class), any(byte[].class), anyString(),
                anyString());
    }
}
