package com.comet.opik.domain.evaluators;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.Span;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.domain.SpanType;
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
@DisplayName("Span Filter Evaluation Service Test")
class SpanFilterEvaluationServiceTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private SpanFilterEvaluationService spanFilterEvaluationService;

    @BeforeEach
    void setUp() {
        spanFilterEvaluationService = new SpanFilterEvaluationService();
    }

    @Nested
    @DisplayName("Empty Filters")
    class EmptyFilters {

        @Test
        void matchesAllFiltersWhenFiltersListIsEmpty() {
            // Given
            var span = podamFactory.manufacturePojo(Span.class);
            var emptyFilters = List.<SpanFilter>of();

            // When
            var result = spanFilterEvaluationService.matchesAllFilters(emptyFilters, span);

            // Then
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("String Field Filtering")
    class StringFieldFiltering {

        static Stream<Arguments> stringFieldTestCases() {
            return Stream.of(
                    arguments(SpanField.NAME, "Test Span", Operator.EQUAL, "Test Span", true),
                    arguments(SpanField.NAME, "Test Span", Operator.EQUAL, "Different Name", false),
                    arguments(SpanField.NAME, "Test Span", Operator.NOT_EQUAL, "Different Name", true),
                    arguments(SpanField.NAME, "Test Span", Operator.NOT_EQUAL, "Test Span", false),
                    arguments(SpanField.NAME, "Test Span", Operator.CONTAINS, "Test", true),
                    arguments(SpanField.NAME, "Test Span", Operator.CONTAINS, "span", true), // case insensitive
                    arguments(SpanField.NAME, "Test Span", Operator.CONTAINS, "Missing", false),
                    arguments(SpanField.NAME, "Test Span", Operator.NOT_CONTAINS, "Missing", true),
                    arguments(SpanField.NAME, "Test Span", Operator.NOT_CONTAINS, "Test", false),
                    arguments(SpanField.NAME, "", Operator.IS_EMPTY, "", true),
                    arguments(SpanField.NAME, "Test Span", Operator.IS_EMPTY, "", false),
                    arguments(SpanField.NAME, "Test Span", Operator.IS_NOT_EMPTY, "", true),
                    arguments(SpanField.NAME, "", Operator.IS_NOT_EMPTY, "", false),
                    arguments(SpanField.MODEL, "gpt-4", Operator.EQUAL, "gpt-4", true),
                    arguments(SpanField.MODEL, "gpt-4", Operator.CONTAINS, "gpt", true),
                    arguments(SpanField.PROVIDER, "openai", Operator.EQUAL, "openai", true),
                    arguments(SpanField.PROVIDER, "openai", Operator.CONTAINS, "open", true));
        }

        @ParameterizedTest
        @MethodSource("stringFieldTestCases")
        void matchesFilter(SpanField field, String fieldValue, Operator operator, String filterValue,
                boolean expectedResult) {
            // Given
            var spanBuilder = podamFactory.manufacturePojo(Span.class).toBuilder();
            switch (field) {
                case NAME -> spanBuilder.name(fieldValue);
                case MODEL -> spanBuilder.model(fieldValue);
                case PROVIDER -> spanBuilder.provider(fieldValue);
                default -> {
                    // Should not happen in test cases
                }
            }
            var span = spanBuilder.build();
            var filter = SpanFilter.builder()
                    .field(field)
                    .operator(operator)
                    .value(filterValue)
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isEqualTo(expectedResult);
        }

        @Test
        void matchesFilterWhenFieldValueIsNull() {
            // Given
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .name(null)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("Type Field Filtering")
    class TypeFieldFiltering {

        @Test
        void matchesFilterWithTypeField() {
            // Given
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .type(SpanType.llm)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.TYPE)
                    .operator(Operator.EQUAL)
                    .value("llm")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWhenTypeIsNull() {
            // Given
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .type(null)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.TYPE)
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

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
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .totalEstimatedCost(cost)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.TOTAL_ESTIMATED_COST)
                    .operator(operator)
                    .value(filterValue)
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isEqualTo(expectedResult);
        }

        @Test
        void matchesFilterWhenCostIsNull() {
            // Given
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .totalEstimatedCost(null)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.TOTAL_ESTIMATED_COST)
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

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
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .startTime(startTime)
                    .endTime(endTime)
                    .duration(null) // Let it calculate from start/end time
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.DURATION)
                    .operator(Operator.GREATER_THAN)
                    .value("5000") // 5 seconds in milliseconds
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithExplicitDuration() {
            // Given
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .duration(15000.0) // 15 seconds
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.DURATION)
                    .operator(Operator.GREATER_THAN)
                    .value("10000")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWhenDurationCannotBeCalculated() {
            // Given
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .startTime(null)
                    .endTime(null)
                    .duration(null)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.DURATION)
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

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
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .tags(Set.of("production", "high-priority", "user-facing"))
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.TAGS)
                    .operator(Operator.CONTAINS)
                    .value("production")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithTagsCaseInsensitive() {
            // Given
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .tags(Set.of("Production", "High-Priority"))
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.TAGS)
                    .operator(Operator.CONTAINS)
                    .value("production")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWhenTagsIsEmpty() {
            // Given
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .tags(Set.of())
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.TAGS)
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

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
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .feedbackScores(List.of(feedbackScore))
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.FEEDBACK_SCORES)
                    .key("relevance")
                    .operator(Operator.GREATER_THAN)
                    .value("0.8")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

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
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .feedbackScores(List.of(feedbackScore))
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.FEEDBACK_SCORES)
                    .key("accuracy") // Different key
                    .operator(Operator.GREATER_THAN)
                    .value("0.8")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        void matchesFilterWhenFeedbackScoresIsEmpty() {
            // Given
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .feedbackScores(List.of())
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.FEEDBACK_SCORES)
                    .key("relevance")
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

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
                    "completion_tokens", 150,
                    "prompt_tokens", 100,
                    "total_tokens", 250);
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .usage(usage)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.USAGE_TOTAL_TOKENS)
                    .operator(Operator.GREATER_THAN)
                    .value("200")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithCompletionTokens() {
            // Given
            var usage = Map.of("completion_tokens", 150, "prompt_tokens", 100);
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .usage(usage)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.USAGE_COMPLETION_TOKENS)
                    .operator(Operator.GREATER_THAN)
                    .value("100")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWhenUsageKeyNotFound() {
            // Given
            var usage = Map.of("completion_tokens", 150);
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .usage(usage)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.USAGE_TOTAL_TOKENS)
                    .operator(Operator.GREATER_THAN)
                    .value("100")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

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

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .metadata(metadata)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .key("environment")
                    .operator(Operator.EQUAL)
                    .value("production")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithInputField() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("query", "What is AI?");
            input.put("context", "machine learning");

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(input)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.INPUT)
                    .key("query")
                    .operator(Operator.CONTAINS)
                    .value("AI")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithOutputField() {
            // Given
            ObjectNode output = JsonUtils.createObjectNode();
            output.put("answer", "AI is artificial intelligence");

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .output(output)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.OUTPUT)
                    .key("answer")
                    .operator(Operator.CONTAINS)
                    .value("artificial")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithInputJsonFieldIsNotEmpty() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("query", "What is AI?");
            input.put("context", "machine learning");

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(input)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.INPUT_JSON)
                    .key("context")
                    .operator(Operator.IS_NOT_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithInputJsonFieldIsEmpty() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("query", "What is AI?");
            // context key is missing

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(input)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.INPUT_JSON)
                    .key("context")
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithInputJsonFieldIsEmptyWhenValueIsNull() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.putNull("context");

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(input)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.INPUT_JSON)
                    .key("context")
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithInputJsonFieldIsEmptyWhenValueIsEmptyString() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("context", "");

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(input)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.INPUT_JSON)
                    .key("context")
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithOutputJsonFieldIsNotEmpty() {
            // Given
            ObjectNode output = JsonUtils.createObjectNode();
            output.put("answer", "AI is artificial intelligence");
            output.put("confidence", "high");

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .output(output)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.OUTPUT_JSON)
                    .key("answer")
                    .operator(Operator.IS_NOT_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithOutputJsonFieldIsEmpty() {
            // Given
            ObjectNode output = JsonUtils.createObjectNode();
            output.put("answer", "AI is artificial intelligence");
            // confidence key is missing

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .output(output)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.OUTPUT_JSON)
                    .key("confidence")
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithMetadataFieldIsNotEmpty() {
            // Given
            ObjectNode metadata = JsonUtils.createObjectNode();
            metadata.put("environment", "production");
            metadata.put("version", "1.0.0");

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .metadata(metadata)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .key("environment")
                    .operator(Operator.IS_NOT_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithMetadataFieldIsEmpty() {
            // Given
            ObjectNode metadata = JsonUtils.createObjectNode();
            metadata.put("environment", "production");
            // version key is missing

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .metadata(metadata)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .key("version")
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithInputJsonFieldIsNotEmptyWhenInputIsNull() {
            // Given
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(null)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.INPUT_JSON)
                    .key("context")
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithCustomInputField() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("message", "Hello world");
            input.put("context", "test context");

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(input)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.CUSTOM)
                    .key("input.message")
                    .operator(Operator.CONTAINS)
                    .value("world")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithCustomOutputField() {
            // Given
            ObjectNode output = JsonUtils.createObjectNode();
            output.put("answer", "AI is artificial intelligence");
            output.put("confidence", "0.95");

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .output(output)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.CUSTOM)
                    .key("output.answer")
                    .operator(Operator.CONTAINS)
                    .value("artificial")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithCustomInputFieldEqual() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("model", "gpt-4");

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(input)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.CUSTOM)
                    .key("input.model")
                    .operator(Operator.EQUAL)
                    .value("gpt-4")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithCustomFieldInvalidKey() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("message", "Hello");

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(input)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.CUSTOM)
                    .key("invalid") // Missing dot separator
                    .operator(Operator.CONTAINS)
                    .value("Hello")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isFalse(); // Should not match due to invalid key format
        }

        @Test
        void matchesFilterWithCustomFieldUnsupportedBaseField() {
            // Given
            ObjectNode input = JsonUtils.createObjectNode();
            input.put("message", "Hello");

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(input)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.CUSTOM)
                    .key("metadata.message") // Unsupported base field (should be input/output)
                    .operator(Operator.CONTAINS)
                    .value("Hello")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

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

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(input)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.CUSTOM)
                    .key("input.messages[0].content")
                    .operator(Operator.CONTAINS)
                    .value("brazil")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

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

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(input)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.CUSTOM)
                    .key("input.messages.0.content")
                    .operator(Operator.CONTAINS)
                    .value("brazil")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("Date/Time Field Filtering")
    class DateTimeFieldFiltering {

        @Test
        void matchesFilterWithStartTime() {
            // Given
            var startTime = Instant.parse("2024-01-01T00:00:00Z");
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .startTime(startTime)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.GREATER_THAN)
                    .value("2023-12-31T00:00:00Z")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithEndTime() {
            // Given
            var endTime = Instant.parse("2024-01-01T00:00:00Z");
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .endTime(endTime)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.END_TIME)
                    .operator(Operator.LESS_THAN)
                    .value("2024-01-02T00:00:00Z")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

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
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .name("Test Span")
                    .type(SpanType.llm)
                    .totalEstimatedCost(BigDecimal.valueOf(0.05))
                    .tags(Set.of("production", "important"))
                    .build();

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.NAME)
                            .operator(Operator.CONTAINS)
                            .value("Test")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TYPE)
                            .operator(Operator.EQUAL)
                            .value("llm")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TOTAL_ESTIMATED_COST)
                            .operator(Operator.GREATER_THAN)
                            .value("0.01")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TAGS)
                            .operator(Operator.CONTAINS)
                            .value("production")
                            .build());

            // When
            var result = spanFilterEvaluationService.matchesAllFilters(filters, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesAllFiltersWhenOneFilterFails() {
            // Given
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .name("Test Span")
                    .type(SpanType.tool) // This will fail the type filter
                    .totalEstimatedCost(BigDecimal.valueOf(0.05))
                    .tags(Set.of("production", "important"))
                    .build();

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.NAME)
                            .operator(Operator.CONTAINS)
                            .value("Test")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TYPE)
                            .operator(Operator.EQUAL)
                            .value("llm") // This filter will fail
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TAGS)
                            .operator(Operator.CONTAINS)
                            .value("production")
                            .build());

            // When
            var result = spanFilterEvaluationService.matchesAllFilters(filters, span);

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
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .metadata(null) // This will cause an exception when trying to access nested field
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .key("nested.field")
                    .operator(Operator.EQUAL)
                    .value("value")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isFalse(); // Should return false on error, not throw
        }

        @ParameterizedTest
        @ValueSource(strings = {"invalid_number", "", "not-a-number"})
        void matchesFilterWhenInvalidNumericValue(String invalidValue) {
            // Given
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .totalEstimatedCost(BigDecimal.valueOf(100))
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.TOTAL_ESTIMATED_COST)
                    .operator(Operator.GREATER_THAN)
                    .value(invalidValue)
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isFalse(); // Should handle gracefully and return false
        }
    }

    @Nested
    @DisplayName("Error Info Field Filtering")
    class ErrorInfoFieldFiltering {

        @Test
        void matchesFilterWithErrorInfoExceptionType() {
            // Given
            var errorInfo = com.comet.opik.api.ErrorInfo.builder()
                    .exceptionType("ValueError")
                    .message("Invalid value")
                    .traceback("Traceback...")
                    .build();
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .errorInfo(errorInfo)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.ERROR_INFO)
                    .key("exceptionType")
                    .operator(Operator.EQUAL)
                    .value("ValueError")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithErrorInfoMessage() {
            // Given
            var errorInfo = com.comet.opik.api.ErrorInfo.builder()
                    .exceptionType("ValueError")
                    .message("Invalid value provided")
                    .traceback("Traceback...")
                    .build();
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .errorInfo(errorInfo)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.ERROR_INFO)
                    .key("message")
                    .operator(Operator.CONTAINS)
                    .value("Invalid")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWithErrorInfoTraceback() {
            // Given
            var errorInfo = com.comet.opik.api.ErrorInfo.builder()
                    .exceptionType("ValueError")
                    .message("Invalid value")
                    .traceback("File \"script.py\", line 10")
                    .build();
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .errorInfo(errorInfo)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.ERROR_INFO)
                    .key("traceback")
                    .operator(Operator.CONTAINS)
                    .value("script.py")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWhenErrorInfoIsNull() {
            // Given
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .errorInfo(null)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.ERROR_INFO)
                    .key("exceptionType")
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void matchesFilterWhenErrorInfoKeyNotFound() {
            // Given
            var errorInfo = com.comet.opik.api.ErrorInfo.builder()
                    .exceptionType("ValueError")
                    .message("Invalid value")
                    .traceback("Traceback...")
                    .build();
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .errorInfo(errorInfo)
                    .build();
            var filter = SpanFilter.builder()
                    .field(SpanField.ERROR_INFO)
                    .key("unknownField") // Unknown field
                    .operator(Operator.EQUAL)
                    .value("value")
                    .build();

            // When
            var result = spanFilterEvaluationService.matchesFilter(filter, span);

            // Then
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Comprehensive Integration")
    class ComprehensiveIntegration {

        @Test
        void matchesAllFiltersWithComplexSpan() {
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
                    "completion_tokens", 50,
                    "prompt_tokens", 25,
                    "total_tokens", 75);

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .name("ML Query Span")
                    .type(SpanType.llm)
                    .input(input)
                    .output(output)
                    .metadata(metadata)
                    .tags(Set.of("ml", "production", "gpt-4"))
                    .feedbackScores(List.of(feedbackScore))
                    .usage(usage)
                    .totalEstimatedCost(BigDecimal.valueOf(0.02))
                    .build();

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.NAME)
                            .operator(Operator.CONTAINS)
                            .value("ML")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TYPE)
                            .operator(Operator.EQUAL)
                            .value("llm")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.INPUT)
                            .key("query")
                            .operator(Operator.CONTAINS)
                            .value("machine learning")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.METADATA)
                            .key("model")
                            .operator(Operator.EQUAL)
                            .value("gpt-4")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TAGS)
                            .operator(Operator.CONTAINS)
                            .value("production")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .key("relevance")
                            .operator(Operator.GREATER_THAN)
                            .value("0.9")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_TOTAL_TOKENS)
                            .operator(Operator.LESS_THAN)
                            .value("100")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TOTAL_ESTIMATED_COST)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value("0.05")
                            .build());

            // When
            var result = spanFilterEvaluationService.matchesAllFilters(filters, span);

            // Then
            assertThat(result).isTrue();
        }
    }
}
