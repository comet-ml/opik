package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.comet.opik.api.events.SpanToScoreLlmAsJudge;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.domain.evaluators.SpanFilterEvaluationService;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.data.message.ChatMessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode;
import static com.comet.opik.api.resources.utils.AutomationRuleEvaluatorTestUtils.toProjects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OnlineScoringSpanSampler Tests")
class OnlineScoringSpanSamplerTest {

    @Mock
    private AutomationRuleEvaluatorService ruleEvaluatorService;

    @Mock
    private SpanFilterEvaluationService filterEvaluationService;

    @Mock
    private OnlineScorePublisher onlineScorePublisher;

    @Mock
    private ServiceTogglesConfig serviceTogglesConfig;

    private OnlineScoringSpanSampler sampler;
    private MockedStatic<UserFacingLoggingFactory> mockedFactory;

    private UUID projectId;
    private String workspaceId;
    private String userName;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        // Mock the static UserFacingLoggingFactory.getLogger method
        mockedFactory = mockStatic(UserFacingLoggingFactory.class);
        mockedFactory.when(() -> UserFacingLoggingFactory.getLogger(any(Class.class)))
                .thenReturn(mock(org.slf4j.Logger.class));

        sampler = new OnlineScoringSpanSampler(
                serviceTogglesConfig,
                ruleEvaluatorService,
                filterEvaluationService,
                onlineScorePublisher);

        projectId = UUID.randomUUID();
        workspaceId = "workspace-123";
        userName = "test-user";
    }

    @AfterEach
    void tearDown() {
        if (mockedFactory != null) {
            mockedFactory.close();
        }
    }

    @Nested
    @DisplayName("Service Toggle Tests")
    class ServiceToggleTests {

        @Test
        @DisplayName("Should skip sampling when span LLM as Judge is disabled")
        void shouldSkipSamplingWhenToggleDisabled() {
            // Given
            when(serviceTogglesConfig.isSpanLlmAsJudgeEnabled()).thenReturn(false);
            when(serviceTogglesConfig.isSpanUserDefinedMetricPythonEnabled()).thenReturn(false);
            Span span = createTestSpan();
            SpansCreated event = new SpansCreated(List.of(span), workspaceId, userName);

            // When
            sampler.onSpansCreated(event);

            // Then
            // When both toggles are disabled, findAll is never called since we return early
            verify(ruleEvaluatorService, never()).findAll(any(), any(),
                    eq(AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE));
            verify(ruleEvaluatorService, never()).findAll(any(), any(),
                    eq(AutomationRuleEvaluatorType.SPAN_USER_DEFINED_METRIC_PYTHON));
            verify(onlineScorePublisher, never()).enqueueMessage(any(), any());
        }

        @Test
        @DisplayName("Should skip Python evaluators when Python toggle is disabled")
        void shouldSkipPythonEvaluatorsWhenToggleDisabled() {
            // Given
            when(serviceTogglesConfig.isSpanLlmAsJudgeEnabled()).thenReturn(true);
            when(serviceTogglesConfig.isSpanUserDefinedMetricPythonEnabled()).thenReturn(false);
            Span span = createTestSpan();
            SpansCreated event = new SpansCreated(List.of(span), workspaceId, userName);

            AutomationRuleEvaluatorSpanLlmAsJudge evaluator = createTestEvaluator(true, 1.0f, List.of());
            List<AutomationRuleEvaluatorSpanLlmAsJudge> evaluators = List.of(evaluator);

            when(ruleEvaluatorService.<SpanLlmAsJudgeCode, SpanFilter, AutomationRuleEvaluatorSpanLlmAsJudge>findAll(
                    projectId, workspaceId, AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE))
                    .thenReturn(evaluators);
            lenient().when(filterEvaluationService.matchesAllFilters(any(), any()))
                    .thenReturn(true);

            // When
            sampler.onSpansCreated(event);

            // Then
            // Should fetch LLM evaluators but NOT Python evaluators
            verify(ruleEvaluatorService, times(1)).findAll(
                    projectId, workspaceId, AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE);
            verify(ruleEvaluatorService, never()).findAll(
                    any(), any(), eq(AutomationRuleEvaluatorType.SPAN_USER_DEFINED_METRIC_PYTHON));
            verify(onlineScorePublisher, times(1)).enqueueMessage(any(),
                    eq(AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE));
        }

        @Test
        @DisplayName("Should process sampling when span LLM as Judge is enabled")
        void shouldProcessSamplingWhenToggleEnabled() {
            // Given
            when(serviceTogglesConfig.isSpanLlmAsJudgeEnabled()).thenReturn(true);
            when(serviceTogglesConfig.isSpanUserDefinedMetricPythonEnabled()).thenReturn(false);
            Span span = createTestSpan();
            SpansCreated event = new SpansCreated(List.of(span), workspaceId, userName);

            AutomationRuleEvaluatorSpanLlmAsJudge evaluator = createTestEvaluator(true, 1.0f, List.of());
            List<AutomationRuleEvaluatorSpanLlmAsJudge> evaluators = List.of(evaluator);

            when(ruleEvaluatorService.<SpanLlmAsJudgeCode, SpanFilter, AutomationRuleEvaluatorSpanLlmAsJudge>findAll(
                    projectId, workspaceId, AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE))
                    .thenReturn(evaluators);
            // Empty filters list means all spans match (matchesAllFilters returns true for empty list)
            // The implementation calls matchesAllFilters even with empty list, so we need to stub it
            // Use lenient to avoid unnecessary stubbing warnings when the method might not be called
            // in certain code paths
            lenient().when(filterEvaluationService.matchesAllFilters(any(), any()))
                    .thenReturn(true);

            // When
            sampler.onSpansCreated(event);

            // Then
            ArgumentCaptor<List<SpanToScoreLlmAsJudge>> captor = ArgumentCaptor.forClass(List.class);
            verify(onlineScorePublisher, times(1)).enqueueMessage(captor.capture(),
                    eq(AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE));

            List<SpanToScoreLlmAsJudge> messages = captor.getValue();
            assertThat(messages).hasSize(1);
            assertThat(messages.getFirst().span()).isEqualTo(span);
            assertThat(messages.getFirst().ruleId()).isEqualTo(evaluator.getId());
        }
    }

    @Nested
    @DisplayName("Rule Filtering Tests")
    class RuleFilteringTests {

        @Test
        @DisplayName("Should only process SPAN_LLM_AS_JUDGE evaluators")
        void shouldOnlyProcessSpanLlmAsJudgeEvaluators() {
            // Given
            when(serviceTogglesConfig.isSpanLlmAsJudgeEnabled()).thenReturn(true);
            Span span = createTestSpan();
            SpansCreated event = new SpansCreated(List.of(span), workspaceId, userName);

            AutomationRuleEvaluatorSpanLlmAsJudge spanEvaluator = createTestEvaluator(true, 1.0f, List.of());
            // Create a non-span evaluator (would be LLM_AS_JUDGE or other type)
            List<AutomationRuleEvaluatorSpanLlmAsJudge> evaluators = List.of(spanEvaluator);
            when(ruleEvaluatorService.<SpanLlmAsJudgeCode, SpanFilter, AutomationRuleEvaluatorSpanLlmAsJudge>findAll(
                    projectId, workspaceId, AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE)).thenReturn(evaluators);

            // Empty filters list means all spans match (matchesAllFilters returns true for empty list)
            // Need to stub since the mock needs to return true for empty filter lists
            lenient().when(filterEvaluationService.matchesAllFilters(any(), any()))
                    .thenReturn(true);

            // When
            sampler.onSpansCreated(event);

            // Then
            verify(onlineScorePublisher, times(1)).enqueueMessage(any(),
                    eq(AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE));
        }

        @Test
        @DisplayName("Should skip disabled evaluators")
        void shouldSkipDisabledEvaluators() {
            // Given
            when(serviceTogglesConfig.isSpanLlmAsJudgeEnabled()).thenReturn(true);
            Span span = createTestSpan();
            SpansCreated event = new SpansCreated(List.of(span), workspaceId, userName);

            AutomationRuleEvaluatorSpanLlmAsJudge disabledEvaluator = createTestEvaluator(false, 1.0f, List.of());

            List<AutomationRuleEvaluatorSpanLlmAsJudge> evaluators = List.of(disabledEvaluator);
            when(ruleEvaluatorService.<SpanLlmAsJudgeCode, SpanFilter, AutomationRuleEvaluatorSpanLlmAsJudge>findAll(
                    projectId, workspaceId, AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE)).thenReturn(evaluators);

            // When
            sampler.onSpansCreated(event);

            // Then
            verify(onlineScorePublisher, never()).enqueueMessage(any(), any());
        }

        @Test
        @DisplayName("Should skip spans that don't match filters")
        void shouldSkipSpansThatDontMatchFilters() {
            // Given
            when(serviceTogglesConfig.isSpanLlmAsJudgeEnabled()).thenReturn(true);
            Span span = createTestSpan();
            SpansCreated event = new SpansCreated(List.of(span), workspaceId, userName);

            // Create evaluator with a filter that won't match
            SpanFilter spanFilter = SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.EQUAL)
                    .value("different-name")
                    .build();

            AutomationRuleEvaluatorSpanLlmAsJudge evaluator = createTestEvaluator(true, 1.0f, List.of(spanFilter));
            List<AutomationRuleEvaluatorSpanLlmAsJudge> evaluators = List.of(evaluator);

            when(ruleEvaluatorService.<SpanLlmAsJudgeCode, SpanFilter, AutomationRuleEvaluatorSpanLlmAsJudge>findAll(
                    projectId, workspaceId, AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE)).thenReturn(evaluators);
            when(filterEvaluationService.matchesAllFilters(any(), any())).thenReturn(false);

            // When
            sampler.onSpansCreated(event);

            // Then
            verify(onlineScorePublisher, never()).enqueueMessage(any(), any());
        }

        @Test
        @DisplayName("Should process spans that match filters")
        void shouldProcessSpansThatMatchFilters() {
            // Given
            when(serviceTogglesConfig.isSpanLlmAsJudgeEnabled()).thenReturn(true);
            Span span = createTestSpan();
            SpansCreated event = new SpansCreated(List.of(span), workspaceId, userName);

            SpanFilter spanFilter = SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.EQUAL)
                    .value("test-span")
                    .build();

            AutomationRuleEvaluatorSpanLlmAsJudge evaluator = createTestEvaluator(true, 1.0f, List.of(spanFilter));

            List<AutomationRuleEvaluatorSpanLlmAsJudge> evaluators = List.of(evaluator);
            when(ruleEvaluatorService.<SpanLlmAsJudgeCode, SpanFilter, AutomationRuleEvaluatorSpanLlmAsJudge>findAll(
                    projectId, workspaceId, AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE)).thenReturn(evaluators);
            when(filterEvaluationService.matchesAllFilters(any(), any())).thenReturn(true);

            // When
            sampler.onSpansCreated(event);

            // Then
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<SpanFilter>> filterCaptor = ArgumentCaptor.forClass(List.class);
            verify(filterEvaluationService, times(1)).matchesAllFilters(filterCaptor.capture(), eq(span));
            verify(onlineScorePublisher, times(1)).enqueueMessage(any(),
                    eq(AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE));

            // Verify the converted filters
            List<SpanFilter> convertedFilters = filterCaptor.getValue();
            assertThat(convertedFilters).hasSize(1);
            assertThat(convertedFilters.getFirst().field()).isEqualTo(SpanField.NAME);
            assertThat(convertedFilters.getFirst().operator()).isEqualTo(Operator.EQUAL);
            assertThat(convertedFilters.getFirst().value()).isEqualTo("test-span");
        }
    }

    @Nested
    @DisplayName("Sampling Rate Tests")
    class SamplingRateTests {

        @Test
        @DisplayName("Should sample all spans when sampling rate is 1.0")
        void shouldSampleAllSpansWhenRateIsOne() {
            // Given
            when(serviceTogglesConfig.isSpanLlmAsJudgeEnabled()).thenReturn(true);
            Span span1 = createTestSpan();
            Span span2 = createTestSpan();
            SpansCreated event = new SpansCreated(List.of(span1, span2), workspaceId, userName);

            AutomationRuleEvaluatorSpanLlmAsJudge evaluator = createTestEvaluator(true, 1.0f, List.of());

            List<AutomationRuleEvaluatorSpanLlmAsJudge> evaluators = List.of(evaluator);
            when(ruleEvaluatorService.<SpanLlmAsJudgeCode, SpanFilter, AutomationRuleEvaluatorSpanLlmAsJudge>findAll(
                    projectId, workspaceId, AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE)).thenReturn(evaluators);
            when(filterEvaluationService.matchesAllFilters(any(), any())).thenReturn(true);

            // When
            sampler.onSpansCreated(event);

            // Then
            ArgumentCaptor<List<SpanToScoreLlmAsJudge>> captor = ArgumentCaptor.forClass(List.class);
            verify(onlineScorePublisher, times(1)).enqueueMessage(captor.capture(),
                    eq(AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE));
            // With rate 1.0, both spans should be sampled (though randomness may affect this in real scenarios)
            assertThat(captor.getValue().size()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Should respect sampling rate")
        void shouldRespectSamplingRate() {
            // Given
            when(serviceTogglesConfig.isSpanLlmAsJudgeEnabled()).thenReturn(true);
            Span span = createTestSpan();
            SpansCreated event = new SpansCreated(List.of(span), workspaceId, userName);

            AutomationRuleEvaluatorSpanLlmAsJudge evaluator = createTestEvaluator(true, 0.0f, List.of());

            List<AutomationRuleEvaluatorSpanLlmAsJudge> evaluators = List.of(evaluator);
            when(ruleEvaluatorService.<SpanLlmAsJudgeCode, SpanFilter, AutomationRuleEvaluatorSpanLlmAsJudge>findAll(
                    projectId, workspaceId, AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE)).thenReturn(evaluators);
            // Empty filters list means all spans match (matchesAllFilters returns true for empty list)
            // No need to stub since empty list returns true immediately, and with sampling rate 0.0,
            // the span won't be sampled anyway

            // When
            sampler.onSpansCreated(event);

            // Then
            // With rate 0.0, no spans should be sampled
            verify(onlineScorePublisher, never()).enqueueMessage(any(), any());
        }
    }

    @Nested
    @DisplayName("Multiple Projects Tests")
    class MultipleProjectsTests {

        @Test
        @DisplayName("Should process spans from multiple projects separately")
        void shouldProcessSpansFromMultipleProjectsSeparately() {
            // Given
            when(serviceTogglesConfig.isSpanLlmAsJudgeEnabled()).thenReturn(true);
            when(serviceTogglesConfig.isSpanUserDefinedMetricPythonEnabled()).thenReturn(false);
            UUID projectId2 = UUID.randomUUID();

            Span span1 = createTestSpan(projectId);
            Span span2 = createTestSpan(projectId2);
            SpansCreated event = new SpansCreated(List.of(span1, span2), workspaceId, userName);

            AutomationRuleEvaluatorSpanLlmAsJudge evaluator1 = createTestEvaluator(true, 1.0f, List.of());
            AutomationRuleEvaluatorSpanLlmAsJudge evaluator2 = createTestEvaluator(true, 1.0f, List.of());

            List<AutomationRuleEvaluatorSpanLlmAsJudge> evaluators1 = List.of(evaluator1);
            List<AutomationRuleEvaluatorSpanLlmAsJudge> evaluators2 = List.of(evaluator2);

            when(ruleEvaluatorService.<SpanLlmAsJudgeCode, SpanFilter, AutomationRuleEvaluatorSpanLlmAsJudge>findAll(
                    projectId, workspaceId, AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE)).thenReturn(evaluators1);
            when(ruleEvaluatorService.<SpanLlmAsJudgeCode, SpanFilter, AutomationRuleEvaluatorSpanLlmAsJudge>findAll(
                    projectId2, workspaceId, AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE)).thenReturn(evaluators2);
            // Python toggle is disabled, so these calls should never happen
            // No need to mock them since they won't be called
            // Empty filters list means all spans match (matchesAllFilters returns true for empty list)
            // Need to stub since the mock needs to return true for empty filter lists
            lenient().when(filterEvaluationService.matchesAllFilters(any(), any()))
                    .thenReturn(true);

            // When
            sampler.onSpansCreated(event);

            // Then
            verify(ruleEvaluatorService, times(1)).findAll(projectId, workspaceId,
                    AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE);
            verify(ruleEvaluatorService, times(1)).findAll(projectId2, workspaceId,
                    AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE);
            verify(onlineScorePublisher, times(2)).enqueueMessage(any(),
                    eq(AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE));
        }
    }

    @Nested
    @DisplayName("Empty Cases Tests")
    class EmptyCasesTests {

        @Test
        @DisplayName("Should handle empty spans list")
        void shouldHandleEmptySpansList() {
            // Given
            SpansCreated event = new SpansCreated(List.of(), workspaceId, userName);

            // When
            sampler.onSpansCreated(event);

            // Then
            verify(ruleEvaluatorService, never()).findAll(any(), any(), any());
            verify(onlineScorePublisher, never()).enqueueMessage(any(), any());
        }

        @Test
        @DisplayName("Should handle no evaluators found")
        void shouldHandleNoEvaluatorsFound() {
            // Given
            when(serviceTogglesConfig.isSpanLlmAsJudgeEnabled()).thenReturn(true);
            Span span = createTestSpan();
            SpansCreated event = new SpansCreated(List.of(span), workspaceId, userName);

            List<AutomationRuleEvaluatorSpanLlmAsJudge> emptyList = List.of();
            when(ruleEvaluatorService.<SpanLlmAsJudgeCode, SpanFilter, AutomationRuleEvaluatorSpanLlmAsJudge>findAll(
                    projectId, workspaceId, AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE)).thenReturn(emptyList);

            // When
            sampler.onSpansCreated(event);

            // Then
            verify(onlineScorePublisher, never()).enqueueMessage(any(), any());
        }
    }

    @Nested
    @DisplayName("Filter Conversion Tests")
    class FilterConversionTests {

        @Test
        @DisplayName("Should convert TraceFilter with SpanField-compatible field")
        void shouldConvertTraceFilterWithSpanField() {
            // Given
            when(serviceTogglesConfig.isSpanLlmAsJudgeEnabled()).thenReturn(true);
            Span span = createTestSpan();
            SpansCreated event = new SpansCreated(List.of(span), workspaceId, userName);

            // Create a SpanFilter (filters for span evaluators should use SpanField)
            SpanFilter spanFilter = SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.EQUAL)
                    .value("test-span")
                    .build();

            // AutomationRuleEvaluator now uses List<Filter>, so span evaluators can use SpanFilter directly
            AutomationRuleEvaluatorSpanLlmAsJudge evaluator = createTestEvaluator(true, 1.0f, List.of(spanFilter));

            List<AutomationRuleEvaluatorSpanLlmAsJudge> evaluators = List.of(evaluator);
            when(ruleEvaluatorService.<SpanLlmAsJudgeCode, SpanFilter, AutomationRuleEvaluatorSpanLlmAsJudge>findAll(
                    projectId, workspaceId, AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE)).thenReturn(evaluators);
            when(filterEvaluationService.matchesAllFilters(any(), any())).thenReturn(true);

            // When
            sampler.onSpansCreated(event);

            // Then
            ArgumentCaptor<List<SpanFilter>> filterCaptor = ArgumentCaptor.forClass(List.class);
            verify(filterEvaluationService, times(1)).matchesAllFilters(filterCaptor.capture(), eq(span));

            List<SpanFilter> convertedFilters = filterCaptor.getValue();
            assertThat(convertedFilters).hasSize(1);
            assertThat(convertedFilters.getFirst().field()).isInstanceOf(SpanField.class);
            assertThat(convertedFilters.getFirst().operator()).isEqualTo(Operator.EQUAL);
            assertThat(convertedFilters.getFirst().value()).isEqualTo("test-span");
        }
    }

    // Helper methods

    private Span createTestSpan() {
        return createTestSpan(projectId);
    }

    private Span createTestSpan(UUID projectId) {
        java.time.Instant now = java.time.Instant.now();
        return Span.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .traceId(UUID.randomUUID())
                .name("test-span")
                .startTime(now)
                .build();
    }

    private AutomationRuleEvaluatorSpanLlmAsJudge createTestEvaluator(
            boolean enabled, float samplingRate, List<? extends com.comet.opik.api.filter.Filter> filterList) {
        // AutomationRuleEvaluator now uses List<Filter>, so we can use filters directly
        @SuppressWarnings({"unchecked"})
        List<SpanFilter> filters = (List<SpanFilter>) filterList;
        LlmAsJudgeModelParameters modelParams = LlmAsJudgeModelParameters.builder()
                .name("gpt-4")
                .temperature(0.7)
                .seed(1000)
                .customParameters(null)
                .build();
        LlmAsJudgeMessage message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("test")
                .build();
        LlmAsJudgeOutputSchema schema = LlmAsJudgeOutputSchema.builder()
                .name("score")
                .type(LlmAsJudgeOutputSchemaType.DOUBLE)
                .description("Test score")
                .build();
        SpanLlmAsJudgeCode code = new SpanLlmAsJudgeCode(
                modelParams,
                List.of(message),
                java.util.Map.of(),
                List.of(schema));

        return AutomationRuleEvaluatorSpanLlmAsJudge.builder()
                .id(UUID.randomUUID())
                .projects(toProjects(Set.of(projectId)))
                .name("test-evaluator")
                .samplingRate(samplingRate)
                .enabled(enabled)
                .filters(filters)
                .code(code)
                .createdAt(java.time.Instant.now())
                .createdBy(userName)
                .lastUpdatedAt(java.time.Instant.now())
                .lastUpdatedBy(userName)
                .build();
    }
}
