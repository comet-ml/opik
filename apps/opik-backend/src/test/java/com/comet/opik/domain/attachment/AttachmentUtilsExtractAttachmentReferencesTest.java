package com.comet.opik.domain.attachment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AttachmentUtils#extractAttachmentReferences(String)} method.
 */
class AttachmentUtilsExtractAttachmentReferencesTest {

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
                "metadata-attachment-2-9876543210.pdf");
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
                Arguments.of("[INPUT-attachment-1-1234567890.png] [Output-attachment-2-9876543210.json]", "Uppercase context"),
                Arguments.of("[wrong-attachment-1-1234567890.png] [custom-attachment-2-9876543210.json]","Wrong context prefix"),
                Arguments.of("[input-attachment.png]", "Missing numbers"),
                Arguments.of("[input-attachment-1-1234567890]","Missing extension"),
                Arguments.of("[invalid-attachment.png] [not-an-attachment-1-123.jpg] [random-text]", "Invalid attachment patterns"),
                Arguments.of("input-attachment-1-1234567890.png", "Missing brackets"),
                Arguments.of("This is just regular text with no attachment references", "Regular text - no references"),
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