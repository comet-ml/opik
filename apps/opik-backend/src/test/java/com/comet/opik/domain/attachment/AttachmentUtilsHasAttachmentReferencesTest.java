package com.comet.opik.domain.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AttachmentUtils#hasAttachmentReferences(ObjectMapper, JsonNode...)} method.
 */
class AttachmentUtilsHasAttachmentReferencesTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Should return false when no nodes are provided")
    void shouldReturnFalseWhenNoNodesProvided() {
        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return false when all nodes are null")
    void shouldReturnFalseWhenAllNodesAreNull() {
        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, null, null, null);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return false when node contains no attachment references")
    void shouldReturnFalseWhenNodeContainsNoAttachmentReferences() throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("{\"text\": \"just some regular text\"}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return true when node contains direct attachment reference")
    void shouldReturnTrueWhenNodeContainsDirectAttachmentReference() throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("{\"image\": \"[input-attachment-1-1234567890.png]\"}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return true when node contains output attachment reference")
    void shouldReturnTrueWhenNodeContainsOutputAttachmentReference() throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("{\"result\": \"[output-attachment-2-9876543210.json]\"}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return true when node contains metadata attachment reference")
    void shouldReturnTrueWhenNodeContainsMetadataAttachmentReference() throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("{\"meta\": \"[metadata-attachment-5-1234567890.pdf]\"}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return true when node contains SDK attachment reference")
    void shouldReturnTrueWhenNodeContainsSdkAttachmentReference() throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("{\"sdk\": \"[input-attachment-1-1234567890-sdk.png]\"}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return true when node contains nested attachment reference in object")
    void shouldReturnTrueWhenNodeContainsNestedAttachmentReferenceInObject() throws Exception {
        // Given
        JsonNode node = objectMapper
                .readTree("{\"outer\": {\"inner\": {\"image\": \"[input-attachment-1-1234567890.png]\"}}}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return true when node contains attachment reference in array")
    void shouldReturnTrueWhenNodeContainsAttachmentReferenceInArray() throws Exception {
        // Given
        JsonNode node = objectMapper
                .readTree("{\"items\": [\"text\", \"[output-attachment-3-1234567890.jpg]\", \"more text\"]}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return true when node contains attachment reference in JSON string")
    void shouldReturnTrueWhenNodeContainsAttachmentReferenceInJsonString() throws Exception {
        // Given
        String nestedJson = "{\\\"image\\\": \\\"[input-attachment-1-1234567890.png]\\\"}";
        JsonNode node = objectMapper.readTree("{\"data\": \"" + nestedJson + "\"}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return true when first node contains attachment reference")
    void shouldReturnTrueWhenFirstNodeContainsAttachmentReference() throws Exception {
        // Given
        JsonNode node1 = objectMapper.readTree("{\"image\": \"[input-attachment-1-1234567890.png]\"}");
        JsonNode node2 = objectMapper.readTree("{\"text\": \"regular text\"}");
        JsonNode node3 = objectMapper.readTree("{\"value\": 123}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node1, node2, node3);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return true when middle node contains attachment reference")
    void shouldReturnTrueWhenMiddleNodeContainsAttachmentReference() throws Exception {
        // Given
        JsonNode node1 = objectMapper.readTree("{\"text\": \"regular text\"}");
        JsonNode node2 = objectMapper.readTree("{\"image\": \"[output-attachment-2-1234567890.jpg]\"}");
        JsonNode node3 = objectMapper.readTree("{\"value\": 123}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node1, node2, node3);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return true when last node contains attachment reference")
    void shouldReturnTrueWhenLastNodeContainsAttachmentReference() throws Exception {
        // Given
        JsonNode node1 = objectMapper.readTree("{\"text\": \"regular text\"}");
        JsonNode node2 = objectMapper.readTree("{\"value\": 123}");
        JsonNode node3 = objectMapper.readTree("{\"doc\": \"[metadata-attachment-1-1234567890.pdf]\"}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node1, node2, node3);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return true when multiple nodes contain attachment references")
    void shouldReturnTrueWhenMultipleNodesContainAttachmentReferences() throws Exception {
        // Given
        JsonNode node1 = objectMapper.readTree("{\"image\": \"[input-attachment-1-1234567890.png]\"}");
        JsonNode node2 = objectMapper.readTree("{\"doc\": \"[metadata-attachment-2-1234567890.pdf]\"}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node1, node2);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return false when multiple nodes contain no attachment references")
    void shouldReturnFalseWhenMultipleNodesContainNoAttachmentReferences() throws Exception {
        // Given
        JsonNode node1 = objectMapper.readTree("{\"text\": \"regular text\"}");
        JsonNode node2 = objectMapper.readTree("{\"value\": 123}");
        JsonNode node3 = objectMapper.readTree("{\"items\": [\"a\", \"b\", \"c\"]}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node1, node2, node3);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return false when nodes contain invalid attachment references")
    void shouldReturnFalseWhenNodesContainInvalidAttachmentReferences() throws Exception {
        // Given
        JsonNode node1 = objectMapper.readTree("{\"text\": \"[invalid-attachment.png]\"}");
        JsonNode node2 = objectMapper.readTree("{\"text\": \"[not-an-attachment-1-123.jpg]\"}");
        JsonNode node3 = objectMapper.readTree("{\"text\": \"input-attachment-1-123.jpg\"}"); // Missing brackets

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node1, node2, node3);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return false when attachment reference is surrounded by other text")
    void shouldReturnFalseWhenAttachmentReferenceIsSurroundedByOtherText() throws Exception {
        // Given - The VALIDATE_REFERENCE_PATTERN requires the entire string to be a reference
        JsonNode node = objectMapper
                .readTree("{\"text\": \"Check this image [input-attachment-1-1234567890.png] for details\"}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return false when node is null value")
    void shouldReturnFalseWhenNodeIsNullValue() throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("{\"field\": null}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return false when node is empty object")
    void shouldReturnFalseWhenNodeIsEmptyObject() throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("{}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return false when node is empty array")
    void shouldReturnFalseWhenNodeIsEmptyArray() throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("[]");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return false when node contains numbers")
    void shouldReturnFalseWhenNodeContainsNumbers() throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("{\"value\": 42, \"pi\": 3.14}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return false when node contains booleans")
    void shouldReturnFalseWhenNodeContainsBooleans() throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("{\"flag\": true, \"active\": false}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return true when some nodes are null and one contains attachment reference")
    void shouldReturnTrueWhenSomeNodesAreNullAndOneContainsAttachmentReference() throws Exception {
        // Given
        JsonNode node1 = null;
        JsonNode node2 = objectMapper.readTree("{\"image\": \"[input-attachment-1-1234567890.png]\"}");
        JsonNode node3 = null;

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node1, node2, node3);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return true when node contains deeply nested JSON string with attachment reference")
    void shouldReturnTrueWhenNodeContainsDeeplyNestedJsonStringWithAttachmentReference() throws Exception {
        // Given
        String level2Json = "{\\\\\\\"img\\\\\\\": \\\\\\\"[input-attachment-1-1234567890.png]\\\\\\\"}";
        String level1Json = "{\\\"nested\\\": \\\"" + level2Json + "\\\"}";
        JsonNode node = objectMapper.readTree("{\"data\": \"" + level1Json + "\"}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return true when array node contains attachment reference")
    void shouldReturnTrueWhenArrayNodeContainsAttachmentReference() throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("[\"text\", \"[output-attachment-1-1234567890.json]\"]");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return false when array node contains no attachment references")
    void shouldReturnFalseWhenArrayNodeContainsNoAttachmentReferences() throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("[\"text\", \"more text\", 123]");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return true when text node is a valid attachment reference")
    void shouldReturnTrueWhenTextNodeIsValidAttachmentReference() throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("\"[input-attachment-1-1234567890.png]\"");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"[invalid json string\"}",
            "\"{invalid json object\"}"
    })
    @DisplayName("Should return false when text starts with brace but is not valid JSON")
    void shouldReturnFalseWhenTextStartsWithBraceButIsNotValidJson(String invalid) throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("{\"text\": " + invalid);

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "input-attachment-1-1234567890.png",
            "output-attachment-2-9876543210.json",
            "metadata-attachment-5-1111111111.pdf"
    })
    @DisplayName("Should return true when node contains valid attachment references with different contexts")
    void shouldReturnTrueWhenNodeContainsValidAttachmentReferencesWithDifferentContexts(String filename)
            throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("{\"file\": \"[" + filename + "]\"}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "input-attachment-1-1234567890-sdk.png",
            "output-attachment-2-9876543210-sdk.json",
            "metadata-attachment-5-1111111111-sdk.pdf"
    })
    @DisplayName("Should return true when node contains valid SDK attachment references")
    void shouldReturnTrueWhenNodeContainsValidSdkAttachmentReferences(String filename) throws Exception {
        // Given
        JsonNode node = objectMapper.readTree("{\"file\": \"[" + filename + "]\"}");

        // When
        boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

        // Then
        assertThat(result).isTrue();
    }
}