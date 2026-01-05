package com.comet.opik.domain.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class AttachmentUtilsTest {

    /**
     * Unit tests for {@link AttachmentUtils#extractAttachmentReferences(String)} method.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ExtractAttachmentReferences {
        @Test
        @DisplayName("Should extract single attachment reference")
        void shouldExtractSingleAttachmentReference() {
            // Given
            String input = "[input-attachment-1-1234567890.png]";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly("input-attachment-1-1234567890.png");
        }

        @Test
        @DisplayName("Should extract attachment reference from text with surrounding content")
        void shouldExtractAttachmentReferenceFromTextWithSurroundingContent() {
            // Given
            String input = "Check this image [input-attachment-1-1234567890.png] for details";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly("input-attachment-1-1234567890.png");
        }

        @Test
        @DisplayName("Should extract multiple different attachment references")
        void shouldExtractMultipleDifferentAttachmentReferences() {
            // Given
            String input = "[input-attachment-1-1234567890.png] and [output-attachment-2-9876543210.json]";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly(
                    "input-attachment-1-1234567890.png",
                    "output-attachment-2-9876543210.json");
        }

        @Test
        @DisplayName("Should extract unique references when duplicates exist")
        void shouldExtractUniqueReferencesWhenDuplicatesExist() {
            // Given
            String input = "[input-attachment-1-1234567890.png] and [input-attachment-1-1234567890.png] again";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly("input-attachment-1-1234567890.png");
        }

        @Test
        @DisplayName("Should maintain insertion order for unique references")
        void shouldMaintainInsertionOrderForUniqueReferences() {
            // Given
            String input = "[output-attachment-3-1111111111.txt] [input-attachment-1-2222222222.png] [metadata-attachment-5-3333333333.pdf]";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly(
                    "output-attachment-3-1111111111.txt",
                    "input-attachment-1-2222222222.png",
                    "metadata-attachment-5-3333333333.pdf");
        }

        @Test
        @DisplayName("Should extract references from JSON string")
        void shouldExtractReferencesFromJsonString() {
            // Given
            String input = "{\"image\": \"[input-attachment-1-1234567890.png]\", \"doc\": \"[metadata-attachment-2-9876543210-sdk.pdf]\"}";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly(
                    "input-attachment-1-1234567890.png",
                    "metadata-attachment-2-9876543210-sdk.pdf");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "input-attachment-1-1234567890-sdk.png",
                "output-attachment-2-9876543210-sdk.json",
                "metadata-attachment-5-1111111111-sdk.pdf"
        })
        @DisplayName("Should extract SDK references with different contexts")
        void shouldExtractSdkReferencesWithDifferentContexts(String filename) {
            // Given
            String input = "[" + filename + "]";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly(filename);
        }

        @Test
        @DisplayName("Should extract both SDK and non-SDK references together")
        void shouldExtractBothSdkAndNonSdkReferencesTogether() {
            // Given
            String input = "[input-attachment-1-1234567890.png] [output-attachment-2-9876543210-sdk.json] [metadata-attachment-3-5555555555.pdf]";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly(
                    "input-attachment-1-1234567890.png",
                    "output-attachment-2-9876543210-sdk.json",
                    "metadata-attachment-3-5555555555.pdf");
        }

        @Test
        @DisplayName("Should extract only valid references when mixed with invalid ones")
        void shouldExtractOnlyValidReferencesWhenMixedWithInvalidOnes() {
            // Given
            String input = "[input-attachment-1-1234567890.png] [invalid.jpg] [output-attachment-2-9876543210.json] [not-valid]";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly(
                    "input-attachment-1-1234567890.png",
                    "output-attachment-2-9876543210.json");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "input-attachment-1-1234567890.png",
                "output-attachment-2-1234567890.json",
                "metadata-attachment-5-1234567890.pdf"
        })
        @DisplayName("Should extract references with different contexts")
        void shouldExtractReferencesWithDifferentContexts(String filename) {
            // Given
            String input = "[" + filename + "]";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly(filename);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "input-attachment-1-1234567890.png",
                "input-attachment-1-1234567890.jpg",
                "input-attachment-1-1234567890.gif",
                "input-attachment-1-1234567890.pdf",
                "input-attachment-1-1234567890.json",
                "input-attachment-1-1234567890.txt",
                "input-attachment-1-1234567890.mp4",
                "input-attachment-1-1234567890.wav"
        })
        @DisplayName("Should extract references with different file extensions")
        void shouldExtractReferencesWithDifferentFileExtensions(String filename) {
            // Given
            String input = "[" + filename + "]";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly(filename);
        }

        @Test
        @DisplayName("Should extract references from complex JSON structure")
        void shouldExtractReferencesFromComplexJsonStructure() {
            // Given
            String input = """
                    {
                        "input": {
                            "images": ["[input-attachment-1-1234567890.png]", "[input-attachment-2-1234567891.jpg]"],
                            "text": "Some text"
                        },
                        "output": {
                            "result": "[output-attachment-1-9876543210.json]"
                        },
                        "metadata": {
                            "docs": "[metadata-attachment-1-5555555555.pdf]"
                        }
                    }
                    """;

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly(
                    "input-attachment-1-1234567890.png",
                    "input-attachment-2-1234567891.jpg",
                    "output-attachment-1-9876543210.json",
                    "metadata-attachment-1-5555555555.pdf");
        }

        @Test
        @DisplayName("Should handle references separated by newlines")
        void shouldHandleReferencesSeparatedByNewlines() {
            // Given
            String input = "[input-attachment-1-1234567890.png]\n[output-attachment-2-9876543210.json]\n[metadata-attachment-3-1111111111.pdf]";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly(
                    "input-attachment-1-1234567890.png",
                    "output-attachment-2-9876543210.json",
                    "metadata-attachment-3-1111111111.pdf");
        }

        @Test
        @DisplayName("Should handle consecutive references without spaces")
        void shouldHandleConsecutiveReferencesWithoutSpaces() {
            // Given
            String input = "[input-attachment-1-1234567890.png][output-attachment-2-9876543210.json]";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly(
                    "input-attachment-1-1234567890.png",
                    "output-attachment-2-9876543210.json");
        }

        @Test
        @DisplayName("Should handle very long strings with many references")
        void shouldHandleVeryLongStringsWithManyReferences() {
            // Given
            StringBuilder input = new StringBuilder();
            for (int i = 1; i <= 100; i++) {
                input.append("[input-attachment-").append(i).append("-").append(System.currentTimeMillis())
                        .append(".png] ");
            }

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input.toString());

            // Then
            assertThat(result).hasSize(100);
        }

        @ParameterizedTest
        @MethodSource("provideInvalidPatterns")
        @DisplayName("Should not extract invalid attachment patterns")
        void shouldNotExtractVariousInvalidAttachmentPatterns(String input, String description) {
            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).as(description).isEmpty();
        }

        private static Stream<Arguments> provideInvalidPatterns() {
            return Stream.of(
                    Arguments.of("[attachment-1-1234567890.png]", "Missing context prefix"),
                    Arguments.of("[input-1-1234567890.png]", "Missing 'attachment' keyword"),
                    Arguments.of("[input-attachment-1234567890.png]", "Missing separator between numbers"),
                    Arguments.of("[input-attachment-1.png]", "Missing second number"),
                    Arguments.of("[input-attachment-abc-1234567890.png]", "Non-numeric first number"),
                    Arguments.of("[input-attachment-1-abc.png]", "Non-numeric second number"),
                    Arguments.of("input-attachment-1-1234567890.png", "Missing brackets"),
                    Arguments.of("[input_attachment-1-1234567890.png]", "Underscore instead of hyphen"),
                    Arguments.of("[inputattachment-1-1234567890.png]", "Missing hyphen after context"),
                    Arguments.of("[input-attachment--1234567890.png]", "Double hyphen"),
                    Arguments.of("[ input-attachment-1-1234567890.png ]", "Whitespace inside brackets"),
                    Arguments.of("[INPUT-attachment-1-1234567890.png] [Output-attachment-2-9876543210.json]",
                            "Uppercase context"),
                    Arguments.of("[wrong-attachment-1-1234567890.png] [custom-attachment-2-9876543210.json]",
                            "Wrong context prefix"),
                    Arguments.of("[input-attachment.png]", "Missing numbers"),
                    Arguments.of("[input-attachment-1-1234567890]", "Missing extension"),
                    Arguments.of("[invalid-attachment.png] [not-an-attachment-1-123.jpg] [random-text]",
                            "Invalid attachment patterns"),
                    Arguments.of("input-attachment-1-1234567890.png", "Missing brackets"),
                    Arguments.of("This is just regular text with no attachment references",
                            "Regular text - no references"),
                    Arguments.of("", "Empty string"),
                    Arguments.of(null, "Null input"),
                    Arguments.of("\"   \\n\\t  \"", "String with only whitespace"),
                    Arguments.of("[] [[]] [  ]", "String with empty brackets"));
        }

        @Test
        @DisplayName("Should extract references from escaped JSON string")
        void shouldExtractReferencesFromEscapedJsonString() {
            // Given
            String input = "\\\"[input-attachment-1-1234567890.png]\\\"";

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly("input-attachment-1-1234567890.png");
        }

        @Test
        @DisplayName("Should deduplicate references across different positions")
        void shouldDeduplicateReferencesAcrossDifferentPositions() {
            // Given
            String input = """
                    Start [input-attachment-1-1234567890.png]
                    Middle [output-attachment-2-9876543210.json]
                    Duplicate [input-attachment-1-1234567890.png]
                    Another [metadata-attachment-3-5555555555.pdf]
                    Another duplicate [output-attachment-2-9876543210.json]
                    """;

            // When
            List<String> result = AttachmentUtils.extractAttachmentReferences(input);

            // Then
            assertThat(result).containsExactly(
                    "input-attachment-1-1234567890.png",
                    "output-attachment-2-9876543210.json",
                    "metadata-attachment-3-5555555555.pdf");
        }
    }

    /**
     * Unit tests for {@link AttachmentUtils#hasAttachmentReferences(ObjectMapper, JsonNode...)} method.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class HasAttachmentReferences {
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
        @DisplayName("Should return true when attachment reference is surrounded by other text")
        void shouldReturnTrueWhenAttachmentReferenceIsSurroundedByOtherText() throws Exception {
            // Given
            JsonNode node = objectMapper
                    .readTree("{\"text\": \"Check this image [input-attachment-1-1234567890.png] for details\"}");

            // When
            boolean result = AttachmentUtils.hasAttachmentReferences(objectMapper, node);

            // Then
            assertThat(result).isTrue();
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
            String level2Json = "{\\\\\\\"data\\\\\\\": \\\\\\\"image: [input-attachment-1-1234567890.png]\\\\\\\"}";
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
}
