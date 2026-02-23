package com.comet.opik.domain.evaluators;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@ExtendWith(MockitoExtension.class)
@DisplayName("Trace Filter Evaluation Service Test")
class TraceFilterEvaluationServiceTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private TraceFilterEvaluationService traceFilterEvaluationService;

    @BeforeEach
    void setUp() {
        traceFilterEvaluationService = new TraceFilterEvaluationService();
    }

    @Nested
    @DisplayName("Empty Filters")
    class EmptyFilters {

        @Test
        void matchesAllFiltersWhenFiltersListIsEmpty() {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class);
            var emptyFilters = List.<TraceFilter>of();

            // When
            var result = traceFilterEvaluationService.matchesAllFilters(emptyFilters, trace);

            // Then
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("String Field Filtering")
    class StringFieldFiltering {

        static Stream<Arguments> stringFieldTestCases() {
            return Stream.of(
                    arguments(TraceField.NAME, "Test Trace", Operator.EQUAL, "Test Trace", true),
                    arguments(TraceField.NAME, "Test Trace", Operator.EQUAL, "Different Name", false),
                    arguments(TraceField.NAME, "Test Trace", Operator.NOT_EQUAL, "Different Name", true),
                    arguments(TraceField.NAME, "Test Trace", Operator.NOT_EQUAL, "Test Trace", false),
                    arguments(TraceField.NAME, "Test Trace", Operator.CONTAINS, "Test", true),
                    arguments(TraceField.NAME, "Test Trace", Operator.CONTAINS, "trace", true), // case insensitive
                    arguments(TraceField.NAME, "Test Trace", Operator.CONTAINS, "Missing", false),
                    arguments(TraceField.NAME, "Test Trace", Operator.NOT_CONTAINS, "Missing", true),
                    arguments(TraceField.NAME, "Test Trace", Operator.NOT_CONTAINS, "Test", false),
                    arguments(TraceField.NAME, "", Operator.IS_EMPTY, "", true),
                    arguments(TraceField.NAME, "Test Trace", Operator.IS_EMPTY, "", false),
                    arguments(TraceField.NAME, "Test Trace", Operator.IS_NOT_EMPTY, "", true),
                    arguments(TraceField.NAME, "", Operator.IS_NOT_EMPTY, "", false));
        }

        @ParameterizedTest
        @MethodSource("stringFieldTestCases")
        void matchesFilter(TraceField field, String fieldValue, Operator operator, String filterValue,
                boolean expectedResult) {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .name(fieldValue)
                    .build();
            var filter = TraceFilter.builder()
                    .field(field)
                    .operator(operator)
                    .value(filterValue)
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isEqualTo(expectedResult);
        }

        @Test
        void matchesFilterWhenFieldValueIsNull() {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .name(null)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("Numeric Field Filtering")
    class NumericFieldFiltering {

        static Stream<Arguments> numericTestCases() {
            return Stream.of(
                    arguments(BigDecimal.valueOf(100), Operator.GREATER_THAN, "50", true),
                    arguments(BigDecimal.valueOf(100), Operator.GREATER_THAN, "100", false),
                    arguments(BigDecimal.valueOf(100), Operator.GREATER_THAN, "150", false),
                    arguments(BigDecimal.valueOf(100), Operator.GREATER_THAN_EQUAL, "100", true),
                    arguments(BigDecimal.valueOf(100), Operator.GREATER_THAN_EQUAL, "150", false),
                    arguments(BigDecimal.valueOf(100), Operator.LESS_THAN, "150", true),
                    arguments(BigDecimal.valueOf(100), Operator.LESS_THAN, "100", false),
                    arguments(BigDecimal.valueOf(100), Operator.LESS_THAN, "50", false),
                    arguments(BigDecimal.valueOf(100), Operator.LESS_THAN_EQUAL, "100", true),
                    arguments(BigDecimal.valueOf(100), Operator.LESS_THAN_EQUAL, "50", false));
        }

        @ParameterizedTest
        @MethodSource("numericTestCases")
        void matchesFilterWithTotalEstimatedCost(BigDecimal cost, Operator operator, String filterValue,
                boolean expectedResult) {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .totalEstimatedCost(cost)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.TOTAL_ESTIMATED_COST)
                    .operator(operator)
                    .value(filterValue)
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isEqualTo(expectedResult);
        }

        @Test
        void matchesFilterWhenCostIsNull() {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .totalEstimatedCost(null)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.TOTAL_ESTIMATED_COST)
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("Duration Field Filtering")
    class DurationFieldFiltering {

        @Test
        void matchesFilterWithDuration() {
            // Given
            var startTime = Instant.now().minusSeconds(10);
            var endTime = Instant.now();
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .startTime(startTime)
                    .endTime(endTime)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.DURATION)
                    .operator(Operator.GREATER_THAN)
                    .value("5000") // 5 seconds in milliseconds
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWhenDurationCannotBeCalculated() {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .startTime(null)
                    .endTime(null)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.DURATION)
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("TTFT Field Filtering")
    class TtftFieldFiltering {

        @Test
        void matchesFilterWithTtft() {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .ttft(150.0)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.TTFT)
                    .operator(Operator.GREATER_THAN)
                    .value("100")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWhenTtftIsNull() {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .ttft(null)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.TTFT)
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("Tags Field Filtering")
    class TagsFieldFiltering {

        @Test
        void matchesFilterWithTagsContains() {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .tags(Set.of("production", "high-priority", "user-facing"))
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.TAGS)
                    .operator(Operator.CONTAINS)
                    .value("production")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithTagsCaseInsensitive() {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .tags(Set.of("Production", "High-Priority"))
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.TAGS)
                    .operator(Operator.CONTAINS)
                    .value("production")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWhenTagsIsEmpty() {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .tags(Set.of())
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.TAGS)
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("Feedback Scores Field Filtering")
    class FeedbackScoresFieldFiltering {

        @Test
        void matchesFilterWithFeedbackScoreByKey() {
            // Given
            var feedbackScore = FeedbackScore.builder()
                    .name("relevance")
                    .value(BigDecimal.valueOf(0.85))
                    .build();
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .feedbackScores(List.of(feedbackScore))
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.FEEDBACK_SCORES)
                    .key("relevance")
                    .operator(Operator.GREATER_THAN)
                    .value("0.8")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWhenFeedbackScoreKeyNotFound() {
            // Given
            var feedbackScore = FeedbackScore.builder()
                    .name("relevance")
                    .value(BigDecimal.valueOf(0.85))
                    .build();
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .feedbackScores(List.of(feedbackScore))
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.FEEDBACK_SCORES)
                    .key("accuracy") // Different key
                    .operator(Operator.GREATER_THAN)
                    .value("0.8")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        void matchesFilterWhenFeedbackScoresIsEmpty() {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .feedbackScores(List.of())
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.FEEDBACK_SCORES)
                    .key("relevance")
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("Usage Field Filtering")
    class UsageFieldFiltering {

        @Test
        void matchesFilterWithUsageField() {
            // Given
            var usage = Map.of(
                    "completion_tokens", 150L,
                    "prompt_tokens", 100L,
                    "total_tokens", 250L);
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .usage(usage)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.USAGE_TOTAL_TOKENS)
                    .operator(Operator.GREATER_THAN)
                    .value("200")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWhenUsageKeyNotFound() {
            // Given
            var usage = Map.of("completion_tokens", 150L);
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .usage(usage)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.USAGE_TOTAL_TOKENS)
                    .operator(Operator.GREATER_THAN)
                    .value("100")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Metadata and JSON Field Filtering")
    class MetadataFiltering {

        @Test
        void matchesFilterWithMetadataField() {
            // Given
            ObjectNode metadata = JsonUtils.createObjectNode();
            metadata.put("environment", "production");
            metadata.put("version", "1.0.0");

            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .metadata(metadata)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .key("environment")
                    .operator(Operator.EQUAL)
                    .value("production")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithInputField() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("query", "What is AI?");
            input.put("context", "machine learning");

            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .input(input)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.INPUT)
                    .key("query")
                    .operator(Operator.CONTAINS)
                    .value("AI")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithMetadataDirectKey() {
            // Given
            ObjectNode metadata = JsonUtils.createObjectNode();
            metadata.put("model", "gpt-4");
            metadata.put("temperature", "0.7");

            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .metadata(metadata)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .key("model")
                    .operator(Operator.EQUAL)
                    .value("gpt-4")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithCustomInputField() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("message", "Hello world");
            input.put("context", "test context");

            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .input(input)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.CUSTOM)
                    .key("input.message")
                    .operator(Operator.CONTAINS)
                    .value("world")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithCustomOutputField() {
            // Given
            ObjectNode output = JsonUtils.createObjectNode();
            output.put("answer", "AI is artificial intelligence");
            output.put("confidence", "0.95");

            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .output(output)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.CUSTOM)
                    .key("output.answer")
                    .operator(Operator.CONTAINS)
                    .value("artificial")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithCustomInputFieldEqual() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("model", "gpt-4");

            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .input(input)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.CUSTOM)
                    .key("input.model")
                    .operator(Operator.EQUAL)
                    .value("gpt-4")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithCustomFieldInvalidKey() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("message", "Hello");

            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .input(input)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.CUSTOM)
                    .key("invalid") // Missing dot separator
                    .operator(Operator.CONTAINS)
                    .value("Hello")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isFalse(); // Should not match due to invalid key format
        }

        @Test
        void matchesFilterWithCustomFieldUnsupportedBaseField() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("message", "Hello");

            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .input(input)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.CUSTOM)
                    .key("metadata.message") // Unsupported base field (should be input/output)
                    .operator(Operator.CONTAINS)
                    .value("Hello")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isFalse(); // Should not match due to unsupported base field
        }

        @Test
        void matchesFilterWithCustomFieldArrayIndex() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            ArrayNode messages = JsonUtils.createArrayNode();
            ObjectNode message = JsonUtils.createObjectNode();
            message.put("role", "user");
            message.put("content", "Where is brazil?");
            messages.add(message);
            input.set("messages", messages);

            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .input(input)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.CUSTOM)
                    .key("input.messages[0].content")
                    .operator(Operator.CONTAINS)
                    .value("brazil")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithCustomFieldArrayIndexNumericPath() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            ArrayNode messages = JsonUtils.createArrayNode();
            ObjectNode message = JsonUtils.createObjectNode();
            message.put("role", "user");
            message.put("content", "Where is brazil?");
            messages.add(message);
            input.set("messages", messages);

            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .input(input)
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.CUSTOM)
                    .key("input.messages.0.content")
                    .operator(Operator.CONTAINS)
                    .value("brazil")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("Multiple Filters")
    class MultipleFilters {

        @Test
        void matchesAllFiltersWhenAllFiltersMatch() {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .name("Test Trace")
                    .totalEstimatedCost(BigDecimal.valueOf(0.05))
                    .tags(Set.of("production", "important"))
                    .build();

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.CONTAINS)
                            .value("Test")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TOTAL_ESTIMATED_COST)
                            .operator(Operator.GREATER_THAN)
                            .value("0.01")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TAGS)
                            .operator(Operator.CONTAINS)
                            .value("production")
                            .build());

            // When
            var result = traceFilterEvaluationService.matchesAllFilters(filters, trace);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesAllFiltersWhenOneFilterFails() {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .name("Test Trace")
                    .totalEstimatedCost(BigDecimal.valueOf(0.001)) // This will fail the cost filter
                    .tags(Set.of("production", "important"))
                    .build();

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.CONTAINS)
                            .value("Test")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TOTAL_ESTIMATED_COST)
                            .operator(Operator.GREATER_THAN)
                            .value("0.01") // This filter will fail
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TAGS)
                            .operator(Operator.CONTAINS)
                            .value("production")
                            .build());

            // When
            var result = traceFilterEvaluationService.matchesAllFilters(filters, trace);

            // Then
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        void matchesFilterWhenExceptionOccurs() {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .metadata(null) // This will cause an exception when trying to access nested field
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .key("nested.field")
                    .operator(Operator.EQUAL)
                    .value("value")
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isFalse(); // Should return false on error, not throw
        }

        @ParameterizedTest
        @ValueSource(strings = {"invalid_number", "", "not-a-number"})
        void matchesFilterWhenInvalidNumericValue(String invalidValue) {
            // Given
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .totalEstimatedCost(BigDecimal.valueOf(100))
                    .build();
            var filter = TraceFilter.builder()
                    .field(TraceField.TOTAL_ESTIMATED_COST)
                    .operator(Operator.GREATER_THAN)
                    .value(invalidValue)
                    .build();

            // When
            var result = traceFilterEvaluationService.matchesFilter(filter, trace);

            // Then
            assertThat(result).isFalse(); // Should handle gracefully and return false
        }
    }

    @Nested
    @DisplayName("Comprehensive Integration")
    class ComprehensiveIntegration {

        @Test
        void matchesAllFiltersWithComplexTrace() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("query", "What is machine learning?");
            input.put("context", "AI and technology");

            ObjectNode output = JsonUtils.createObjectNode();
            output.put("answer", "Machine learning is a subset of AI");

            ObjectNode metadata = JsonUtils.createObjectNode();
            metadata.put("model", "gpt-4");
            metadata.put("temperature", "0.7");

            var feedbackScore = FeedbackScore.builder()
                    .name("relevance")
                    .value(BigDecimal.valueOf(0.95))
                    .build();

            var usage = Map.of(
                    "completion_tokens", 50L,
                    "prompt_tokens", 25L,
                    "total_tokens", 75L);

            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .name("ML Query Trace")
                    .input(input)
                    .output(output)
                    .metadata(metadata)
                    .tags(Set.of("ml", "production", "gpt-4"))
                    .feedbackScores(List.of(feedbackScore))
                    .usage(usage)
                    .totalEstimatedCost(BigDecimal.valueOf(0.02))
                    .build();

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.CONTAINS)
                            .value("ML")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.INPUT)
                            .key("query")
                            .operator(Operator.CONTAINS)
                            .value("machine learning")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.METADATA)
                            .key("model")
                            .operator(Operator.EQUAL)
                            .value("gpt-4")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TAGS)
                            .operator(Operator.CONTAINS)
                            .value("production")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .key("relevance")
                            .operator(Operator.GREATER_THAN)
                            .value("0.9")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.USAGE_TOTAL_TOKENS)
                            .operator(Operator.LESS_THAN)
                            .value("100")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TOTAL_ESTIMATED_COST)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value("0.05")
                            .build());

            // When
            var result = traceFilterEvaluationService.matchesAllFilters(filters, trace);

            // Then
            assertThat(result).isTrue();
        }
    }
}
