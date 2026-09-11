package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.events.AttachmentUploadRequested;
import com.comet.opik.infrastructure.AttachmentsConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.comet.opik.utils.AttachmentPayloadUtilsTest.createLargeGifBase64;
import static com.comet.opik.utils.AttachmentPayloadUtilsTest.createLargePdfBase64;
import static com.comet.opik.utils.AttachmentPayloadUtilsTest.createLargePngBase64;
import static com.comet.opik.utils.AttachmentPayloadUtilsTest.createShortBase64;
import static com.comet.opik.utils.AttachmentPayloadUtilsTest.createValidPngBase64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentStripperServiceTest {

    @Mock
    private AttachmentsConfig attachmentsConfig;

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

        // Mock AttachmentsConfig to return default threshold
        when(attachmentsConfig.getStripMinSize()).thenReturn(5000L);

        // Mock OpikConfiguration to return the configs
        when(opikConfiguration.getAttachmentsConfig()).thenReturn(attachmentsConfig);

        // Use OpenTelemetry no-op implementation and EventBus mock for async uploads
        attachmentStripperService = new AttachmentStripperService(objectMapper, opikConfiguration, eventBus);
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

    @Test
    @DisplayName("Should strip a base64 blob without bridging across whitespace into following text")
    void shouldNotBridgeAcrossWhitespace() {
        // Given - a contiguous base64 blob >= threshold immediately followed by a newline + prose in the
        // SAME string. The newline is not a base64 char, so it ends the run: the blob is stripped while
        // the prose is preserved verbatim and nothing extra is absorbed into the upload.
        String blob = createLargePngBase64();
        JsonNode input = objectMapper.createObjectNode().put("data", blob + "\nThanks for the image");

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "input");

        // Then - only the blob is replaced; the trailing prose survives unchanged
        assertThat(result.get("data").asText())
                .matches("\\[input-attachment-1-\\d+\\.png\\]\nThanks for the image");

        // ...and the uploaded payload decodes (strict RFC 4648, as AttachmentUploadListener does) back to
        // exactly the blob bytes -- no characters were absorbed from the following text.
        ArgumentCaptor<AttachmentUploadRequested> captor = ArgumentCaptor.forClass(AttachmentUploadRequested.class);
        verify(eventBus, times(1)).post(captor.capture());
        assertThat(Base64.getDecoder().decode(captor.getValue().base64Data()))
                .isEqualTo(Base64.getDecoder().decode(blob));
    }

    @Test
    @DisplayName("Should leave MIME line-wrapped base64 intact (contiguous-only; wrapped stripping is out of scope)")
    void shouldLeaveWrappedBase64Intact() {
        // Given - a real PNG whose base64 is MIME line-wrapped (CRLF@76). Detection is contiguous-only
        // (matching the prior regex), so a line-wrapped blob is left untouched -- no false strip, no
        // regression. (Stripping wrapped attachments is deferred to a separate change.)
        byte[] pngBytes = Base64.getDecoder().decode(createLargePngBase64());
        String wrapped = Base64.getMimeEncoder().encodeToString(pngBytes);
        JsonNode input = objectMapper.createObjectNode().put("data", wrapped);

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "metadata");

        // Then - unchanged, nothing uploaded
        assertThat(result.get("data").asText()).isEqualTo(wrapped);
        verify(eventBus, never()).post(any());
    }

    @Test
    @DisplayName("Should not false-positive on large non-base64 text")
    void shouldNotFalsePositiveOnLargeText() {
        // Given - a large natural-language blob with no contiguous base64 run >= threshold
        String bigText = "The quick brown fox jumps over the lazy dog. ".repeat(2_000);
        JsonNode input = objectMapper.createObjectNode().put("data", bigText);

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "input");

        // Then - unchanged, nothing uploaded
        assertThat(result.get("data").asText()).isEqualTo(bigText);
        verify(eventBus, never()).post(any());
    }

    @Test
    @DisplayName("Should not merge base64 runs separated by spaces (any non-base64 char ends a run)")
    void shouldNotMergeSpaceSeparatedBase64() {
        // Given - base64-looking chunks joined by single spaces. A space is not a base64 char, so it
        // ends the current run; no run reaches the threshold and nothing is treated as an attachment.
        String spaced = ("A".repeat(100) + " ").repeat(6_000);
        JsonNode input = objectMapper.createObjectNode().put("data", spaced);

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "input");

        // Then - unchanged, nothing uploaded
        assertThat(result.get("data").asText()).isEqualTo(spaced);
        verify(eventBus, never()).post(any());
    }

    @Test
    @DisplayName("Should strip a run at exactly the size threshold but not one just below it")
    void shouldStripAtThresholdButNotJustBelow() {
        // OPIK-7118 moved the size check into code: `runEnd - start < minBase64Size` (threshold 5000
        // here). Pin both sides of that comparison with PNG runs one char apart. A 3749-byte PNG encodes
        // to a 4999-char run plus one '=' (so end - start == 5000 once padding is counted); a 3750-byte
        // PNG to a 5000-char run with no padding. Asserting 4999 is left alone and 5000 is stripped
        // guards against `<=` and against comparing the padded end instead of the run.
        String belowThreshold = createValidPngBase64(3749); // run length 4999 (< 5000)
        String atThreshold = createValidPngBase64(3750); // run length 5000 (== 5000)

        JsonNode below = objectMapper.createObjectNode().put("data", belowThreshold);
        JsonNode belowResult = attachmentStripperService.stripAttachments(
                below, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "input");
        assertThat(belowResult.get("data").asText()).isEqualTo(belowThreshold); // unchanged

        JsonNode at = objectMapper.createObjectNode().put("data", atThreshold);
        JsonNode atResult = attachmentStripperService.stripAttachments(
                at, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "input");
        assertThat(atResult.get("data").asText()).matches("\\[input-attachment-1-\\d+\\.png\\]");

        // Only the at-threshold run is treated as an attachment.
        verify(eventBus, times(1)).post(any());
    }

    @Test
    @DisplayName("Should capture base64 padding exactly - zero, two, and never a third '='")
    void shouldCapturePaddingExactly() {
        // The rewrite replaced the regex's inline `={0,2}` with a bounded loop:
        //   while (end < length && end - runEnd < 2 && text.charAt(end) == '=') end++;
        // Existing fixtures all end in a single '='; cover the 0-pad and 2-pad paths, and prove the loop
        // stops at two (a third '=' must survive in the output, not be folded into the uploaded payload).
        String zeroPad = createValidPngBase64(3750); // 3750 % 3 == 0 -> no padding
        String twoPad = createValidPngBase64(3751); // 3751 % 3 == 1 -> two '=' padding
        assertThat(zeroPad).doesNotEndWith("=");
        assertThat(twoPad).endsWith("==");

        JsonNode zero = objectMapper.createObjectNode().put("data", zeroPad);
        JsonNode zeroResult = attachmentStripperService.stripAttachments(
                zero, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "input");
        assertThat(zeroResult.get("data").asText()).matches("\\[input-attachment-1-\\d+\\.png\\]");

        // A stray third '=' right after the two-pad blob: only two '=' belong to the blob, so the third
        // must remain in the output and must not be folded into the decoded payload.
        JsonNode two = objectMapper.createObjectNode().put("data", twoPad + "=");
        JsonNode twoResult = attachmentStripperService.stripAttachments(
                two, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "input");
        assertThat(twoResult.get("data").asText()).matches("\\[input-attachment-1-\\d+\\.png\\]=");

        ArgumentCaptor<AttachmentUploadRequested> captor = ArgumentCaptor.forClass(AttachmentUploadRequested.class);
        verify(eventBus, times(2)).post(captor.capture());
        assertThat(Base64.getDecoder().decode(captor.getAllValues().get(0).base64Data()))
                .isEqualTo(Base64.getDecoder().decode(zeroPad));
        assertThat(Base64.getDecoder().decode(captor.getAllValues().get(1).base64Data()))
                .isEqualTo(Base64.getDecoder().decode(twoPad));
    }

    @Test
    @DisplayName("Should detect and strip an OOXML (x-tika-ooxml) document attachment")
    void shouldStripOoxmlAttachment() throws Exception {
        // Given - a minimal OOXML package (the [Content_Types].xml marker Tika keys on), base64-encoded.
        // Real .docx/.xlsx/.pptx attachments surface as application/x-tika-ooxml (or a specific office
        // type); either way they are binary files that must be stripped, not skipped as text/octet-stream.
        String base64 = Base64.getEncoder().encodeToString(minimalOoxmlPackage());
        // Tika recognizes the OOXML/ZIP container as a real binary type (x-tika-ooxml, a specific office
        // type, or application/zip) -- not text/plain or octet-stream -- so it gets stripped, not skipped.
        String mime = attachmentStripperService.detectMimeType(base64);
        assertThat(mime).isNotEqualTo("application/octet-stream").isNotEqualTo("text/plain");

        JsonNode input = objectMapper.createObjectNode().put("doc", base64);

        // When
        JsonNode result = attachmentStripperService.stripAttachments(
                input, TEST_ENTITY_ID, EntityType.TRACE, TEST_WORKSPACE_ID, TEST_USER_NAME, TEST_PROJECT_NAME,
                "input");

        // Then - the OOXML doc is detected as a real file and stripped
        assertThat(result.get("doc").asText()).matches("\\[input-attachment-1-\\d+\\..+\\]");
        verify(eventBus, times(1)).post(any());
    }

    private static byte[] minimalOoxmlPackage() throws IOException {
        var bos = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            // Trailing '\' on each line keeps the XML on a single line (no inserted newlines).
            zip.write("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                    <Default Extension="xml" ContentType="application/xml"/></Types>"""
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>"""
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            // incompressible padding so the base64 clears the strip threshold
            zip.putNextEntry(new ZipEntry("docProps/pad.bin"));
            byte[] pad = new byte[6000];
            new Random(7).nextBytes(pad);
            zip.write(pad);
            zip.closeEntry();
        }
        return bos.toByteArray();
    }
}
