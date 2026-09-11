package com.comet.opik.api.evaluators;

import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.AutomationRuleEvaluatorTestUtils.toProjects;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test for AutomationRuleEvaluatorFiltersDeserializer.
 * Validates that filters are correctly deserialized as SpanFilter or TraceFilter
 * based on the evaluator type.
 */
@DisplayName("AutomationRuleEvaluatorFiltersDeserializer Tests")
class AutomationRuleEvaluatorFiltersDeserializerTest {

    private final ObjectMapper objectMapper = JsonUtils.getMapper();

    @Nested
    @DisplayName("Span LLM-as-Judge Evaluator Tests")
    class SpanLlmAsJudgeTests {

        @Test
        @DisplayName("Should deserialize filters as SpanFilter for span_llm_as_judge type")
        void shouldDeserializeFiltersAsSpanFilter() throws Exception {
            // Given: JSON with span_llm_as_judge type and filters
            String json = """
                    {
                      "action": "evaluator",
                      "type": "span_llm_as_judge",
                      "project_id": "00000000-0000-0000-0000-000000000001",
                      "name": "Test Span Rule",
                      "sampling_rate": 1.0,
                      "enabled": true,
                      "filters": [
                        {
                          "field": "type",
                          "operator": "=",
                          "value": "llm"
                        },
                        {
                          "field": "model",
                          "operator": "=",
                          "value": "gpt-4o-mini"
                        },
                        {
                          "field": "input_json",
                          "operator": "is_not_empty",
                          "key": "context",
                          "value": ""
                        }
                      ],
                      "code": {
                        "model": {
                          "name": "gpt-4o-mini",
                          "temperature": 0.0
                        },
                        "messages": [
                          {
                            "role": "USER",
                            "content": "Test message"
                          }
                        ],
                        "variables": {},
                        "schema": []
                      }
                    }
                    """;

            // When: Deserialize the JSON
            AutomationRuleEvaluator<?, ?> evaluator = objectMapper.readValue(json, AutomationRuleEvaluator.class);

            // Then: Filters should be deserialized as SpanFilter instances
            assertThat(evaluator).isInstanceOf(AutomationRuleEvaluatorSpanLlmAsJudge.class);
            assertThat(evaluator.getFilters()).hasSize(3);
            assertThat(evaluator.getFilters()).allMatch(filter -> filter instanceof SpanFilter);

            // Verify filter details
            SpanFilter filter1 = (SpanFilter) evaluator.getFilters().get(0);
            assertThat(filter1.field()).isEqualTo(SpanField.TYPE);
            assertThat(filter1.operator()).isEqualTo(Operator.EQUAL);
            assertThat(filter1.value()).isEqualTo("llm");

            SpanFilter filter2 = (SpanFilter) evaluator.getFilters().get(1);
            assertThat(filter2.field()).isEqualTo(SpanField.MODEL);
            assertThat(filter2.operator()).isEqualTo(Operator.EQUAL);
            assertThat(filter2.value()).isEqualTo("gpt-4o-mini");

            SpanFilter filter3 = (SpanFilter) evaluator.getFilters().get(2);
            assertThat(filter3.field()).isEqualTo(SpanField.INPUT_JSON);
            assertThat(filter3.operator()).isEqualTo(Operator.IS_NOT_EMPTY);
            assertThat(filter3.key()).isEqualTo("context");
        }

        @Test
        @DisplayName("Should handle empty filters array for span_llm_as_judge")
        void shouldHandleEmptyFilters() throws Exception {
            // Given: JSON with empty filters array
            String json = """
                    {
                      "action": "evaluator",
                      "type": "span_llm_as_judge",
                      "project_id": "00000000-0000-0000-0000-000000000001",
                      "name": "Test Span Rule",
                      "sampling_rate": 1.0,
                      "enabled": true,
                      "filters": [],
                      "code": {
                        "model": {
                          "name": "gpt-4o-mini",
                          "temperature": 0.0
                        },
                        "messages": [
                          {
                            "role": "USER",
                            "content": "Test message"
                          }
                        ],
                        "variables": {},
                        "schema": []
                      }
                    }
                    """;

            // When: Deserialize the JSON
            AutomationRuleEvaluator<?, ?> evaluator = objectMapper.readValue(json, AutomationRuleEvaluator.class);

            // Then: Filters should be empty
            assertThat(evaluator).isInstanceOf(AutomationRuleEvaluatorSpanLlmAsJudge.class);
            assertThat(evaluator.getFilters()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Trace LLM-as-Judge Evaluator Tests")
    class TraceLlmAsJudgeTests {

        @Test
        @DisplayName("Should deserialize filters as TraceFilter for llm_as_judge type")
        void shouldDeserializeFiltersAsTraceFilter() throws Exception {
            // Given: JSON with llm_as_judge type and filters
            String json = """
                    {
                      "action": "evaluator",
                      "type": "llm_as_judge",
                      "project_id": "00000000-0000-0000-0000-000000000001",
                      "name": "Test Trace Rule",
                      "sampling_rate": 1.0,
                      "enabled": true,
                      "filters": [
                        {
                          "field": "name",
                          "operator": "contains",
                          "value": "test"
                        },
                        {
                          "field": "total_estimated_cost",
                          "operator": ">",
                          "value": "0.01"
                        }
                      ],
                      "code": {
                        "model": {
                          "name": "gpt-4o-mini",
                          "temperature": 0.0
                        },
                        "messages": [
                          {
                            "role": "USER",
                            "content": "Test message"
                          }
                        ],
                        "variables": {},
                        "schema": []
                      }
                    }
                    """;

            // When: Deserialize the JSON
            AutomationRuleEvaluator<?, ?> evaluator = objectMapper.readValue(json, AutomationRuleEvaluator.class);

            // Then: Filters should be deserialized as TraceFilter instances
            assertThat(evaluator).isInstanceOf(AutomationRuleEvaluatorLlmAsJudge.class);
            assertThat(evaluator.getFilters()).hasSize(2);
            assertThat(evaluator.getFilters()).allMatch(filter -> filter instanceof TraceFilter);

            // Verify filter details
            TraceFilter filter1 = (TraceFilter) evaluator.getFilters().get(0);
            assertThat(filter1.field()).isEqualTo(TraceField.NAME);
            assertThat(filter1.operator()).isEqualTo(Operator.CONTAINS);
            assertThat(filter1.value()).isEqualTo("test");

            TraceFilter filter2 = (TraceFilter) evaluator.getFilters().get(1);
            assertThat(filter2.field()).isEqualTo(TraceField.TOTAL_ESTIMATED_COST);
            assertThat(filter2.operator()).isEqualTo(Operator.GREATER_THAN);
            assertThat(filter2.value()).isEqualTo("0.01");
        }

        @Test
        @DisplayName("Should handle empty filters array for llm_as_judge")
        void shouldHandleEmptyFilters() throws Exception {
            // Given: JSON with empty filters array
            String json = """
                    {
                      "action": "evaluator",
                      "type": "llm_as_judge",
                      "project_id": "00000000-0000-0000-0000-000000000001",
                      "name": "Test Trace Rule",
                      "sampling_rate": 1.0,
                      "enabled": true,
                      "filters": [],
                      "code": {
                        "model": {
                          "name": "gpt-4o-mini",
                          "temperature": 0.0
                        },
                        "messages": [
                          {
                            "role": "USER",
                            "content": "Test message"
                          }
                        ],
                        "variables": {},
                        "schema": []
                      }
                    }
                    """;

            // When: Deserialize the JSON
            AutomationRuleEvaluator<?, ?> evaluator = objectMapper.readValue(json, AutomationRuleEvaluator.class);

            // Then: Filters should be empty
            assertThat(evaluator).isInstanceOf(AutomationRuleEvaluatorLlmAsJudge.class);
            assertThat(evaluator.getFilters()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Other Evaluator Types Tests")
    class OtherEvaluatorTypesTests {

        @Test
        @DisplayName("Should deserialize filters as TraceFilter for trace_thread_llm_as_judge type")
        void shouldDeserializeFiltersAsTraceFilterForTraceThread() throws Exception {
            // Given: JSON with trace_thread_llm_as_judge type and filters
            String json = """
                    {
                      "action": "evaluator",
                      "type": "trace_thread_llm_as_judge",
                      "project_id": "00000000-0000-0000-0000-000000000001",
                      "name": "Test Thread Rule",
                      "sampling_rate": 1.0,
                      "enabled": true,
                      "filters": [
                        {
                          "field": "first_message",
                          "operator": "contains",
                          "value": "thread"
                        }
                      ],
                      "code": {
                        "model": {
                          "name": "gpt-4o-mini",
                          "temperature": 0.0
                        },
                        "messages": [
                          {
                            "role": "USER",
                            "content": "Test message"
                          }
                        ],
                        "variables": {},
                        "schema": []
                      }
                    }
                    """;

            // When: Deserialize the JSON
            AutomationRuleEvaluator<?, ?> evaluator = objectMapper.readValue(json, AutomationRuleEvaluator.class);

            // Then: Filters should be deserialized as TraceFilter instances
            assertThat(evaluator).isInstanceOf(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class);
            assertThat(evaluator.getFilters()).hasSize(1);
            assertThat(evaluator.getFilters()).allMatch(filter -> filter instanceof TraceThreadFilter);
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should round-trip serialize and deserialize filters correctly")
        void shouldRoundTripSerializeDeserialize() throws Exception {
            // Given: Create an evaluator with SpanFilters
            UUID projectId = UUID.randomUUID();
            AutomationRuleEvaluatorSpanLlmAsJudge originalEvaluator = AutomationRuleEvaluatorSpanLlmAsJudge.builder()
                    .id(UUID.randomUUID())
                    .projects(toProjects(Set.of(projectId)))
                    .name("Test Rule")
                    .samplingRate(1.0f)
                    .enabled(true)
                    .filters(List.of(
                            SpanFilter.builder()
                                    .field(SpanField.TYPE)
                                    .operator(Operator.EQUAL)
                                    .value("llm")
                                    .build(),
                            SpanFilter.builder()
                                    .field(SpanField.MODEL)
                                    .operator(Operator.EQUAL)
                                    .value("gpt-4o-mini")
                                    .build()))
                    .code(AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode.builder()
                            .model(com.comet.opik.api.evaluators.LlmAsJudgeModelParameters.builder()
                                    .name("gpt-4o-mini")
                                    .temperature(0.0)
                                    .build())
                            .messages(List.of())
                            .variables(java.util.Map.of())
                            .schema(List.of())
                            .build())
                    .build();

            // When: Serialize and deserialize
            String json = objectMapper.writeValueAsString(originalEvaluator);
            AutomationRuleEvaluator<?, ?> deserialized = objectMapper.readValue(json, AutomationRuleEvaluator.class);

            // Then: Filters should be preserved as SpanFilters
            assertThat(deserialized).isInstanceOf(AutomationRuleEvaluatorSpanLlmAsJudge.class);
            assertThat(deserialized.getFilters()).hasSize(2);
            assertThat(deserialized.getFilters()).allMatch(filter -> filter instanceof SpanFilter);

            SpanFilter filter1 = (SpanFilter) deserialized.getFilters().get(0);
            assertThat(filter1.field()).isEqualTo(SpanField.TYPE);
            assertThat(filter1.value()).isEqualTo("llm");

            SpanFilter filter2 = (SpanFilter) deserialized.getFilters().get(1);
            assertThat(filter2.field()).isEqualTo(SpanField.MODEL);
            assertThat(filter2.value()).isEqualTo("gpt-4o-mini");
        }
    }
}
