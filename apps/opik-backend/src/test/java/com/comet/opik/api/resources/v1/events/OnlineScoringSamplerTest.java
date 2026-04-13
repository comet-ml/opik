package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.Source;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceToScoreUserDefinedMetricPython;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.domain.evaluators.TraceFilterEvaluationService;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import uk.co.jemos.podam.api.PodamFactory;

import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.AutomationRuleEvaluatorTestUtils.toProjects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlineScoringSamplerTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Mock
    private ServiceTogglesConfig serviceTogglesConfig;

    @Mock
    private AutomationRuleEvaluatorService ruleEvaluatorService;

    @Mock
    private OnlineScorePublisher onlineScorePublisher;

    @Mock
    private TraceService traceService;

    private OnlineScoringSampler onlineScoringSampler;
    private MockedStatic<UserFacingLoggingFactory> mockedFactory;

    private UUID projectId;
    private String workspaceId;
    private String userName;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        mockedFactory = mockStatic(UserFacingLoggingFactory.class);
        mockedFactory.when(() -> UserFacingLoggingFactory.getLogger(any(Class.class)))
                .thenReturn(mock(Logger.class));

        onlineScoringSampler = new OnlineScoringSampler(
                serviceTogglesConfig,
                ruleEvaluatorService,
                new TraceFilterEvaluationService(),
                onlineScorePublisher,
                traceService);

        projectId = UUID.randomUUID();
        workspaceId = UUID.randomUUID().toString();
        userName = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);
    }

    @AfterEach
    void tearDown() {
        if (mockedFactory != null) {
            mockedFactory.close();
        }
    }

    @Nested
    class ServiceToggleTests {

        @Test
        void processesPythonEvaluatorWhenToggleEnabled() {
            when(serviceTogglesConfig.isPythonEvaluatorEnabled()).thenReturn(true);
            var trace = createTrace(Source.SDK);
            var evaluator = createPythonEvaluator(1.0f);
            whenFindAllPythonEvaluators(evaluator);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            var expectedMessage = TraceToScoreUserDefinedMetricPython.builder()
                    .trace(trace)
                    .ruleId(evaluator.getId())
                    .ruleName(evaluator.getName())
                    .code(evaluator.getCode())
                    .workspaceId(workspaceId)
                    .userName(userName)
                    .build();
            verify(onlineScorePublisher).enqueueMessage(List.of(expectedMessage),
                    AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON);
        }

        @Test
        void skipsPythonEvaluatorWhenToggleDisabled() {
            when(serviceTogglesConfig.isPythonEvaluatorEnabled()).thenReturn(false);
            var trace = createTrace(Source.SDK);
            var evaluator = createPythonEvaluator(1.0f);
            whenFindAllPythonEvaluators(evaluator);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verifyNoInteractions(onlineScorePublisher);
        }
    }

    @Nested
    class SourceFilteringTests {

        @ParameterizedTest
        @EnumSource(value = Source.class, names = {"SDK"})
        @NullSource
        void scoresTracesWithSdkOrNullSource(Source source) {
            var trace = createTrace(source);
            var evaluator = createLlmEvaluator(true, 1.0f, List.of());
            whenFindAllLlmEvaluators(evaluator);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verify(onlineScorePublisher).enqueueMessage(List.of(toLlmMessage(evaluator, trace)),
                    AutomationRuleEvaluatorType.LLM_AS_JUDGE);
        }

        @ParameterizedTest
        @EnumSource(value = Source.class, mode = EnumSource.Mode.EXCLUDE, names = {"SDK"})
        void skipsNonSdkTracesWithoutSelectedRuleIds(Source source) {
            var trace = createTrace(source);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verifyNoInteractions(ruleEvaluatorService);
            verifyNoInteractions(onlineScorePublisher);
        }
    }

    @Nested
    class SelectedRuleIdsTests {

        @ParameterizedTest
        @EnumSource(value = Source.class, mode = EnumSource.Mode.EXCLUDE, names = {"SDK"})
        void scoresNonSdkTracesCarryingSelectedRuleIds(Source source) {
            var evaluator = createLlmEvaluator(true, 1.0f, List.of());
            var trace = createTrace(source).toBuilder()
                    .metadata(metadataWithRuleIds(evaluator.getId()))
                    .build();
            whenFindAllLlmEvaluators(evaluator);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verify(onlineScorePublisher).enqueueMessage(List.of(toLlmMessage(evaluator, trace)),
                    AutomationRuleEvaluatorType.LLM_AS_JUDGE);
        }

        @Test
        void narrowsEvaluatorsToSelectedRuleIdsSet() {
            var selected = createLlmEvaluator(true, 1.0f, List.of());
            var other = createLlmEvaluator(true, 1.0f, List.of());
            var trace = createTrace(Source.PLAYGROUND).toBuilder()
                    .metadata(metadataWithRuleIds(selected.getId()))
                    .build();
            whenFindAllLlmEvaluators(selected, other);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verify(onlineScorePublisher).enqueueMessage(List.of(toLlmMessage(selected, trace)),
                    AutomationRuleEvaluatorType.LLM_AS_JUDGE);
        }

        @Test
        void scoresEachNonSdkTraceOnlyByItsOwnSelectedRuleIds() {
            var evalA = createLlmEvaluator(true, 1.0f, List.of());
            var evalB = createLlmEvaluator(true, 1.0f, List.of());
            var unrelated = createLlmEvaluator(true, 1.0f, List.of());

            var traceA = createTrace(Source.PLAYGROUND).toBuilder()
                    .metadata(metadataWithRuleIds(evalA.getId()))
                    .build();
            var traceB = createTrace(Source.PLAYGROUND).toBuilder()
                    .metadata(metadataWithRuleIds(evalB.getId()))
                    .build();

            whenFindAllLlmEvaluators(evalA, evalB, unrelated);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(traceA, traceB), workspaceId, userName));

            // evalA enqueues traceA only; evalB enqueues traceB only; unrelated never enqueues.
            ArgumentCaptor<List<TraceToScoreLlmAsJudge>> captor = ArgumentCaptor.forClass(List.class);
            verify(onlineScorePublisher, times(2)).enqueueMessage(captor.capture(),
                    eq(AutomationRuleEvaluatorType.LLM_AS_JUDGE));

            assertThat(captor.getAllValues()).containsExactlyInAnyOrder(
                    List.of(toLlmMessage(evalA, traceA)),
                    List.of(toLlmMessage(evalB, traceB)));
        }

        @Test
        void scoresSdkTraceByAllEvaluatorsEvenWhenBatchIncludesNonSdkSelection() {
            var selected = createLlmEvaluator(true, 1.0f, List.of());
            var other = createLlmEvaluator(true, 1.0f, List.of());
            var sdkTrace = createTrace(Source.SDK);
            var playgroundTrace = createTrace(Source.PLAYGROUND).toBuilder()
                    .metadata(metadataWithRuleIds(selected.getId()))
                    .build();
            whenFindAllLlmEvaluators(selected, other);

            onlineScoringSampler
                    .onTracesCreated(new TracesCreated(List.of(sdkTrace, playgroundTrace), workspaceId, userName));

            // Two enqueue calls (one per evaluator, parallelStream):
            //   - selected: scores both traces (SDK + playground, since playground selected it)
            //   - other:    scores only the SDK trace (playground did not select it)
            ArgumentCaptor<List<TraceToScoreLlmAsJudge>> captor = ArgumentCaptor.forClass(List.class);
            verify(onlineScorePublisher, times(2)).enqueueMessage(captor.capture(),
                    eq(AutomationRuleEvaluatorType.LLM_AS_JUDGE));

            assertThat(captor.getAllValues()).containsExactlyInAnyOrder(
                    List.of(toLlmMessage(selected, sdkTrace), toLlmMessage(selected, playgroundTrace)),
                    List.of(toLlmMessage(other, sdkTrace)));
        }

        @Test
        void skipsMalformedUuidsButKeepsValidOnesInSelectedRuleIds() {
            var evaluator = createLlmEvaluator(true, 1.0f, List.of());
            var metadata = JsonUtils.createObjectNode();
            metadata.putArray("selected_rule_ids")
                    .add(evaluator.getId().toString())
                    .add("not-a-uuid");
            var trace = createTrace(Source.PLAYGROUND).toBuilder().metadata(metadata).build();
            whenFindAllLlmEvaluators(evaluator);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verify(onlineScorePublisher).enqueueMessage(List.of(toLlmMessage(evaluator, trace)),
                    AutomationRuleEvaluatorType.LLM_AS_JUDGE);
        }

        @Test
        void treatsNonArraySelectedRuleIdsAsAbsent() {
            var metadata = JsonUtils.createObjectNode();
            metadata.put("selected_rule_ids", "not-an-array");
            var trace = createTrace(Source.PLAYGROUND).toBuilder().metadata(metadata).build();

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verifyNoInteractions(ruleEvaluatorService);
            verifyNoInteractions(onlineScorePublisher);
        }

        @Test
        void treatsMissingSelectedRuleIdsKeyAsAbsent() {
            var metadata = JsonUtils.createObjectNode();
            metadata.put("other_field", "value");
            var trace = createTrace(Source.PLAYGROUND).toBuilder().metadata(metadata).build();

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verifyNoInteractions(ruleEvaluatorService);
            verifyNoInteractions(onlineScorePublisher);
        }

        @Test
        void skipsNonTextualEntriesInSelectedRuleIdsArray() {
            var evaluator = createLlmEvaluator(true, 1.0f, List.of());
            var metadata = JsonUtils.createObjectNode();
            metadata.putArray("selected_rule_ids")
                    .add(evaluator.getId().toString())
                    .add(123);
            var trace = createTrace(Source.PLAYGROUND).toBuilder().metadata(metadata).build();
            whenFindAllLlmEvaluators(evaluator);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verify(onlineScorePublisher).enqueueMessage(List.of(toLlmMessage(evaluator, trace)),
                    AutomationRuleEvaluatorType.LLM_AS_JUDGE);
        }
    }

    @Nested
    class RuleFilteringTests {

        @Test
        void skipsDisabledEvaluators() {
            var trace = createTrace(Source.SDK);
            var evaluator = createLlmEvaluator(false, 1.0f, List.of());
            whenFindAllLlmEvaluators(evaluator);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verifyNoInteractions(onlineScorePublisher);
        }

        @Test
        void skipsTracesThatDontMatchEvaluatorFilters() {
            var trace = createTrace(Source.SDK);
            var nonMatchingFilter = TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.EQUAL)
                    .value("definitely-not-the-name-" + UUID.randomUUID())
                    .build();
            var evaluator = createLlmEvaluator(true, 1.0f, List.of(nonMatchingFilter));
            whenFindAllLlmEvaluators(evaluator);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verifyNoInteractions(onlineScorePublisher);
        }

        @Test
        void processesTracesThatMatchEvaluatorFilters() {
            var trace = createTrace(Source.SDK);
            var matchingFilter = TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.EQUAL)
                    .value(trace.name())
                    .build();
            var evaluator = createLlmEvaluator(true, 1.0f, List.of(matchingFilter));
            whenFindAllLlmEvaluators(evaluator);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verify(onlineScorePublisher).enqueueMessage(List.of(toLlmMessage(evaluator, trace)),
                    AutomationRuleEvaluatorType.LLM_AS_JUDGE);
        }
    }

    @Nested
    class SamplingRateTests {

        @Test
        void doesNotEnqueueLlmJudgeWhenSamplingRateIsZero() {
            var trace = createTrace(Source.SDK);
            var evaluator = createLlmEvaluator(true, 0.0f, List.of());
            whenFindAllLlmEvaluators(evaluator);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verifyNoInteractions(onlineScorePublisher);
        }

        @Test
        void doesNotEnqueuePythonEvaluatorWhenSamplingRateIsZero() {
            when(serviceTogglesConfig.isPythonEvaluatorEnabled()).thenReturn(true);
            var trace = createTrace(Source.SDK);
            var evaluator = createPythonEvaluator(0.0f);
            whenFindAllPythonEvaluators(evaluator);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verifyNoInteractions(onlineScorePublisher);
        }

        @Test
        void samplesAllTracesWhenSamplingRateIsOne() {
            var trace1 = createTrace(Source.SDK);
            var trace2 = createTrace(Source.SDK);
            var evaluator = createLlmEvaluator(true, 1.0f, List.of());
            whenFindAllLlmEvaluators(evaluator);

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace1, trace2), workspaceId, userName));

            verify(onlineScorePublisher).enqueueMessage(
                    List.of(toLlmMessage(evaluator, trace1), toLlmMessage(evaluator, trace2)),
                    AutomationRuleEvaluatorType.LLM_AS_JUDGE);
        }
    }

    @Nested
    class MultipleProjectsTests {

        @Test
        void processesTracesFromMultipleProjectsIndependently() {
            var trace1 = createTrace(Source.SDK);
            var otherProjectId = UUID.randomUUID();
            var trace2 = createTrace(Source.SDK).toBuilder().projectId(otherProjectId).build();
            var evaluator1 = createLlmEvaluator(true, 1.0f, List.of());
            var evaluator2 = createLlmEvaluator(true, 1.0f, List.of());

            when(ruleEvaluatorService
                    .<LlmAsJudgeCode, TraceFilter, AutomationRuleEvaluatorLlmAsJudge>findAll(projectId, workspaceId))
                    .thenReturn(List.of(evaluator1));
            when(ruleEvaluatorService
                    .<LlmAsJudgeCode, TraceFilter, AutomationRuleEvaluatorLlmAsJudge>findAll(otherProjectId,
                            workspaceId))
                    .thenReturn(List.of(evaluator2));

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace1, trace2), workspaceId, userName));

            verify(onlineScorePublisher).enqueueMessage(List.of(toLlmMessage(evaluator1, trace1)),
                    AutomationRuleEvaluatorType.LLM_AS_JUDGE);
            verify(onlineScorePublisher).enqueueMessage(List.of(toLlmMessage(evaluator2, trace2)),
                    AutomationRuleEvaluatorType.LLM_AS_JUDGE);
        }
    }

    @Nested
    class EmptyCasesTests {

        @Test
        void handlesEmptyTracesList() {
            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(), workspaceId, userName));

            verifyNoInteractions(ruleEvaluatorService);
            verifyNoInteractions(onlineScorePublisher);
        }

        @Test
        void filtersOutPartialTracesWithoutEndTime() {
            var partialTrace = createTrace(Source.SDK).toBuilder().endTime(null).build();
            var completeTrace = createTrace(Source.SDK);
            var evaluator = createLlmEvaluator(true, 1.0f, List.of());
            whenFindAllLlmEvaluators(evaluator);

            onlineScoringSampler.onTracesCreated(
                    new TracesCreated(List.of(partialTrace, completeTrace), workspaceId, userName));

            verify(onlineScorePublisher).enqueueMessage(List.of(toLlmMessage(evaluator, completeTrace)),
                    AutomationRuleEvaluatorType.LLM_AS_JUDGE);
        }

        @Test
        void handlesNoEvaluatorsFound() {
            var trace = createTrace(Source.SDK);
            whenFindAllLlmEvaluators();

            onlineScoringSampler.onTracesCreated(new TracesCreated(List.of(trace), workspaceId, userName));

            verifyNoInteractions(onlineScorePublisher);
        }
    }

    @Nested
    class TracesUpdatedTests {

        @ParameterizedTest
        @EnumSource(value = Source.class, names = {"SDK"})
        @NullSource
        void processesOnTracesUpdatedWithEndTimeForSdkOrNullSource(Source source) {
            var trace = createTrace(source);
            var traceUpdate = TraceUpdate.builder().endTime(Instant.now()).build();
            var event = new TracesUpdated(Set.of(projectId), Set.of(trace.id()),
                    workspaceId, userName, traceUpdate);
            var evaluator = createLlmEvaluator(true, 1.0f, List.of());

            when(traceService.getByIds(List.of(trace.id()))).thenReturn(Flux.just(trace));
            whenFindAllLlmEvaluators(evaluator);

            onlineScoringSampler.onTracesUpdated(event);

            verify(onlineScorePublisher).enqueueMessage(List.of(toLlmMessage(evaluator, trace)),
                    AutomationRuleEvaluatorType.LLM_AS_JUDGE);
        }

        @Test
        void skipsOnTracesUpdatedWhenEndTimeIsNull() {
            var traceUpdate = TraceUpdate.builder().build();
            var event = new TracesUpdated(Set.of(projectId), Set.of(UUID.randomUUID()),
                    workspaceId, userName, traceUpdate);

            onlineScoringSampler.onTracesUpdated(event);

            verifyNoInteractions(traceService);
            verifyNoInteractions(ruleEvaluatorService);
            verifyNoInteractions(onlineScorePublisher);
        }

        @Test
        void filtersOutPartialTracesReturnedByTraceService() {
            var partialTrace = createTrace(Source.SDK).toBuilder().endTime(null).build();
            var traceUpdate = TraceUpdate.builder().endTime(Instant.now()).build();
            var event = new TracesUpdated(Set.of(projectId), Set.of(partialTrace.id()),
                    workspaceId, userName, traceUpdate);

            when(traceService.getByIds(List.of(partialTrace.id()))).thenReturn(Flux.just(partialTrace));

            onlineScoringSampler.onTracesUpdated(event);

            verifyNoInteractions(ruleEvaluatorService);
            verifyNoInteractions(onlineScorePublisher);
        }

        @ParameterizedTest
        @EnumSource(value = Source.class, mode = EnumSource.Mode.EXCLUDE, names = {"SDK"})
        void skipsNonSdkTracesViaTracesUpdatedPath(Source source) {
            var trace = createTrace(source);
            var traceUpdate = TraceUpdate.builder().endTime(Instant.now()).build();
            var event = new TracesUpdated(Set.of(projectId), Set.of(trace.id()),
                    workspaceId, userName, traceUpdate);

            when(traceService.getByIds(List.of(trace.id()))).thenReturn(Flux.just(trace));

            onlineScoringSampler.onTracesUpdated(event);

            verifyNoInteractions(ruleEvaluatorService);
            verifyNoInteractions(onlineScorePublisher);
        }
    }

    private Trace createTrace(Source source) {
        return podamFactory.manufacturePojo(Trace.class).toBuilder()
                .projectId(projectId)
                .endTime(Instant.now())
                .source(source)
                .build();
    }

    private AutomationRuleEvaluatorLlmAsJudge createLlmEvaluator(
            boolean enabled, float samplingRate, List<TraceFilter> filters) {
        return podamFactory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                .projects(toProjects(Set.of(projectId)))
                .samplingRate(samplingRate)
                .enabled(enabled)
                .filters(filters)
                .build();
    }

    private AutomationRuleEvaluatorUserDefinedMetricPython createPythonEvaluator(float samplingRate) {
        return podamFactory.manufacturePojo(AutomationRuleEvaluatorUserDefinedMetricPython.class).toBuilder()
                .projects(toProjects(Set.of(projectId)))
                .samplingRate(samplingRate)
                .enabled(true)
                .filters(List.of())
                .build();
    }

    private void whenFindAllLlmEvaluators(AutomationRuleEvaluatorLlmAsJudge... evaluators) {
        when(ruleEvaluatorService.<LlmAsJudgeCode, TraceFilter, AutomationRuleEvaluatorLlmAsJudge>findAll(
                projectId, workspaceId))
                .thenReturn(List.of(evaluators));
    }

    private void whenFindAllPythonEvaluators(AutomationRuleEvaluatorUserDefinedMetricPython... evaluators) {
        when(ruleEvaluatorService.<AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode, TraceFilter, AutomationRuleEvaluatorUserDefinedMetricPython>findAll(
                projectId, workspaceId))
                .thenReturn(List.of(evaluators));
    }

    private TraceToScoreLlmAsJudge toLlmMessage(AutomationRuleEvaluatorLlmAsJudge evaluator, Trace trace) {
        return TraceToScoreLlmAsJudge.builder()
                .trace(trace)
                .ruleId(evaluator.getId())
                .ruleName(evaluator.getName())
                .llmAsJudgeCode(evaluator.getCode())
                .workspaceId(workspaceId)
                .userName(userName)
                .scoreNameMapping(Map.of())
                .promptType(PromptType.MUSTACHE)
                .build();
    }

    private ObjectNode metadataWithRuleIds(UUID... ruleIds) {
        var metadata = JsonUtils.createObjectNode();
        var array = metadata.putArray("selected_rule_ids");
        for (var id : ruleIds) {
            array.add(id.toString());
        }
        return metadata;
    }
}
