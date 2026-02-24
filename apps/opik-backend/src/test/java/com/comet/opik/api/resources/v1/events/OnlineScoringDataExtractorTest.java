package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("OnlineScoringDataExtractor")
class OnlineScoringDataExtractorTest {

    private static final String PROJECT_NAME = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String USER_NAME = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private static final String SUMMARY_STR = "What was the approach to experimenting with different data mixtures?";
    private static final String OUTPUT_STR = "The study employed a systematic approach to experiment with varying data mixtures by manipulating the proportions and sources of datasets used for model training.";
    private static final String INPUT = """
            {
                "questions": {
                    "question1": "%s",
                    "question2": "Whatever, we wont use it anyway"
                 },
                "pdf_url": "https://arxiv.org/pdf/2406.04744",
                "title": "CRAG -- Comprehensive RAG Benchmark"
            }
            """.formatted(SUMMARY_STR).trim();
    private static final String OUTPUT = """
            {
                "output": "%s"
            }
            """.formatted(OUTPUT_STR).trim();

    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    // ==================== parseVariableAsPath ====================

    static Stream<Arguments> parseVariableAsPathCases() {
        return Stream.of(
                arguments("input.question", OnlineScoringDataExtractor.TraceSection.INPUT, "$.question"),
                arguments("output.answer", OnlineScoringDataExtractor.TraceSection.OUTPUT, "$.answer"),
                arguments("metadata.model", OnlineScoringDataExtractor.TraceSection.METADATA, "$.model"),
                arguments("input", OnlineScoringDataExtractor.TraceSection.INPUT, "$"),
                arguments("input.questions.question1", OnlineScoringDataExtractor.TraceSection.INPUT,
                        "$.questions.question1"));
    }

    @ParameterizedTest(name = "parseVariableAsPath(\"{0}\") -> section={1}, jsonPath={2}")
    @MethodSource("parseVariableAsPathCases")
    void testParseVariableAsPath(String variableName, OnlineScoringDataExtractor.TraceSection expectedSection,
            String expectedJsonPath) {
        var mapping = OnlineScoringDataExtractor.parseVariableAsPath(variableName);

        assertThat(mapping).isNotNull();
        assertThat(mapping.variableName()).isEqualTo(variableName);
        assertThat(mapping.traceSection()).isEqualTo(expectedSection);
        assertThat(mapping.jsonPath()).isEqualTo(expectedJsonPath);
    }

    @ParameterizedTest(name = "parseVariableAsPath(\"{0}\") -> null")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "unknown.field"})
    void testParseVariableAsPath_returnsNull(String input) {
        assertThat(OnlineScoringDataExtractor.parseVariableAsPath(input)).isNull();
    }

    // ==================== toReplacementsFromTemplateVariables ====================

    @Test
    @DisplayName("toReplacementsFromTemplateVariables should extract values from trace")
    void testToReplacementsFromTemplateVariables() throws JsonProcessingException {
        // Given
        var traceId = generator.generate();
        var projectId = generator.generate();
        var trace = createTrace(traceId, projectId);

        var templateVariables = Set.of(
                "input.questions.question1",
                "output.output",
                "input");

        // When
        var replacements = OnlineScoringDataExtractor.toReplacementsFromTemplateVariables(templateVariables, trace);

        // Then
        assertThat(replacements).hasSize(3);
        assertThat(replacements.get("input.questions.question1")).isEqualTo(SUMMARY_STR);
        assertThat(replacements.get("output.output")).isEqualTo(OUTPUT_STR);
        // input should contain the full input JSON
        assertThat(replacements.get("input")).contains("questions");
    }

    // ==================== toFullSectionObjectData ====================

    static Stream<Arguments> toFullSectionObjectDataCases() {
        return Stream.of(
                Arguments.of(
                        "dict input/output with metadata",
                        Map.of("question", "What is AI?", "context", "tech"),
                        Map.of("answer", "Artificial Intelligence", "confidence", 0.95),
                        Map.of("model", "gpt-4", "tokens", 150),
                        Map.of(
                                "input", Map.of("question", "What is AI?", "context", "tech"),
                                "output", Map.of("answer", "Artificial Intelligence", "confidence", 0.95),
                                "metadata", Map.of("model", "gpt-4", "tokens", 150))),
                Arguments.of(
                        "string input/output without metadata",
                        "What is AI?",
                        "Artificial Intelligence",
                        null,
                        Map.of(
                                "input", "What is AI?",
                                "output", "Artificial Intelligence")),
                Arguments.of(
                        "array input/output",
                        List.of("item1", "item2", "item3"),
                        List.of(Map.of("score", 0.8), Map.of("score", 0.9)),
                        null,
                        Map.of(
                                "input", List.of("item1", "item2", "item3"),
                                "output", List.of(Map.of("score", 0.8), Map.of("score", 0.9)))));
    }

    @ParameterizedTest(name = "toFullSectionObjectData should handle {0}")
    @MethodSource("toFullSectionObjectDataCases")
    void testToFullSectionObjectData(String scenario, Object inputVal, Object outputVal,
            Object metadataVal, Map<String, Object> expectedData) {
        // Given
        var input = JsonUtils.getMapper().valueToTree(inputVal);
        var output = JsonUtils.getMapper().valueToTree(outputVal);
        var metadata = metadataVal != null ? JsonUtils.getMapper().valueToTree(metadataVal) : null;

        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .projectId(UUID.randomUUID())
                .input(input)
                .output(output)
                .metadata(metadata)
                .build();

        // When
        var data = OnlineScoringDataExtractor.toFullSectionObjectData(trace);

        // Then
        assertThat(data).hasSize(expectedData.size());
        expectedData.forEach((key, value) -> assertThat(data.get(key)).isEqualTo(value));
    }

    @Test
    @DisplayName("toFullSectionObjectData should handle nested dict input/output")
    void testToFullSectionObjectData_nestedDictInputOutput() {
        // Given
        var input = JsonUtils.getMapper().valueToTree(
                Map.of("messages", List.of(
                        Map.of("role", "user", "content", "hello"),
                        Map.of("role", "assistant", "content", "hi there"))));
        var output = JsonUtils.getMapper().valueToTree(
                Map.of("result", Map.of("answer", "hello", "metadata", Map.of("score", 0.9))));

        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .projectId(UUID.randomUUID())
                .input(input)
                .output(output)
                .build();

        // When
        var data = OnlineScoringDataExtractor.toFullSectionObjectData(trace);

        // Then
        assertThat(data).hasSize(2);

        @SuppressWarnings("unchecked")
        var inputMap = (Map<String, Object>) data.get("input");
        assertThat(inputMap).containsKey("messages");
        @SuppressWarnings("unchecked")
        var messages = (List<Map<String, Object>>) inputMap.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role")).isEqualTo("user");

        @SuppressWarnings("unchecked")
        var outputMap = (Map<String, Object>) data.get("output");
        @SuppressWarnings("unchecked")
        var resultMap = (Map<String, Object>) outputMap.get("result");
        assertThat(resultMap.get("answer")).isEqualTo("hello");
    }

    @Test
    @DisplayName("toFullSectionObjectData should skip null sections")
    void testToFullSectionObjectData_nullSections() {
        // Given - only output, no input or metadata
        var output = JsonUtils.getMapper().valueToTree(Map.of("result", "success"));

        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .projectId(UUID.randomUUID())
                .output(output)
                .build();

        // When
        var data = OnlineScoringDataExtractor.toFullSectionObjectData(trace);

        // Then
        assertThat(data).hasSize(1);
        assertThat(data).containsKey("output");
        assertThat(data).doesNotContainKey("input");
        assertThat(data).doesNotContainKey("metadata");
    }

    @Test
    @DisplayName("toFullSectionObjectData should handle mixed types - string input, dict output")
    void testToFullSectionObjectData_mixedTypes() {
        // Given
        var input = JsonUtils.getMapper().valueToTree("plain text input");
        var output = JsonUtils.getMapper().valueToTree(Map.of("answer", "hello", "confidence", 0.9));
        var metadata = JsonUtils.getMapper().valueToTree(Map.of("model", "gpt-4"));

        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .projectId(UUID.randomUUID())
                .input(input)
                .output(output)
                .metadata(metadata)
                .build();

        // When
        var data = OnlineScoringDataExtractor.toFullSectionObjectData(trace);

        // Then
        assertThat(data).hasSize(3);
        assertThat(data.get("input")).isEqualTo("plain text input");
        assertThat(data.get("output")).isEqualTo(Map.of("answer", "hello", "confidence", 0.9));
        assertThat(data.get("metadata")).isEqualTo(Map.of("model", "gpt-4"));
    }

    @Test
    @DisplayName("toFullSectionObjectData should work with Span")
    void testToFullSectionObjectData_span() {
        // Given
        var input = JsonUtils.getMapper().valueToTree(Map.of("prompt", "tell me a joke"));
        var output = JsonUtils.getMapper().valueToTree(Map.of("response", "Why did the chicken cross the road?"));

        var span = Span.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .traceId(UUID.randomUUID())
                .name("test-span")
                .input(input)
                .output(output)
                .build();

        // When
        var data = OnlineScoringDataExtractor.toFullSectionObjectData(span);

        // Then
        assertThat(data).hasSize(2);
        assertThat(data.get("input")).isEqualTo(Map.of("prompt", "tell me a joke"));
        assertThat(data.get("output")).isEqualTo(Map.of("response", "Why did the chicken cross the road?"));
        assertThat(data).doesNotContainKey("metadata");
    }

    @Test
    @DisplayName("toFullSectionObjectData should handle numeric and boolean values in objects")
    void testToFullSectionObjectData_numericAndBooleanValues() {
        // Given
        var input = JsonUtils.getMapper().valueToTree(Map.of(
                "temperature", 0.7,
                "max_tokens", 100,
                "stream", true));
        var output = JsonUtils.getMapper().valueToTree(Map.of(
                "score", 0.95,
                "passed", true,
                "count", 42));

        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .projectId(UUID.randomUUID())
                .input(input)
                .output(output)
                .build();

        // When
        var data = OnlineScoringDataExtractor.toFullSectionObjectData(trace);

        // Then
        assertThat(data).hasSize(2);

        @SuppressWarnings("unchecked")
        var inputMap = (Map<String, Object>) data.get("input");
        assertThat(inputMap.get("temperature")).isEqualTo(0.7);
        assertThat(inputMap.get("max_tokens")).isEqualTo(100);
        assertThat(inputMap.get("stream")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        var outputMap = (Map<String, Object>) data.get("output");
        assertThat(outputMap.get("score")).isEqualTo(0.95);
        assertThat(outputMap.get("passed")).isEqualTo(true);
        assertThat(outputMap.get("count")).isEqualTo(42);
    }

    // ==================== Helpers ====================

    private Trace createTrace(UUID traceId, UUID projectId) throws JsonProcessingException {
        return Trace.builder()
                .id(traceId)
                .projectName(PROJECT_NAME)
                .projectId(projectId)
                .createdBy(USER_NAME)
                .input(JsonUtils.getJsonNodeFromString(INPUT))
                .output(JsonUtils.getJsonNodeFromString(OUTPUT))
                .build();
    }
}
