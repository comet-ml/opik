package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Source;
import com.comet.opik.api.TraceThreadSampling;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.events.TraceThreadsCreated;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.TraceThreadFilterEvaluationService;
import com.comet.opik.domain.threads.TraceThreadModel;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.podam.PodamFactoryUtils;
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
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.AutomationRuleEvaluatorTestUtils.toProjects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceThreadOnlineScoringSamplerListenerTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Mock
    private AutomationRuleEvaluatorService ruleEvaluatorService;

    @Mock
    private TraceThreadFilterEvaluationService filterEvaluationService;

    @Mock
    private TraceThreadService traceThreadService;

    private TraceThreadOnlineScoringSamplerListener listener;
    private MockedStatic<UserFacingLoggingFactory> mockedFactory;

    private UUID projectId;
    private String workspaceId;
    private String userName;

    @BeforeEach
    void setUp() {
        mockedFactory = mockStatic(UserFacingLoggingFactory.class);
        mockedFactory.when(() -> UserFacingLoggingFactory.getLogger(any(Class.class)))
                .thenReturn(mock(Logger.class));

        listener = new TraceThreadOnlineScoringSamplerListener(
                ruleEvaluatorService,
                filterEvaluationService,
                traceThreadService);

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
    class SourceFilteringTests {

        @ParameterizedTest
        @EnumSource(value = Source.class, names = {"SDK"})
        @NullSource
        void processesThreadsWithSdkOrNullSource(Source source) {
            var thread = createThread(source);
            var evaluator = createEvaluator();
            var event = new TraceThreadsCreated(List.of(thread), projectId, workspaceId, userName);

            doReturn(List.of(evaluator))
                    .when(ruleEvaluatorService).findAll(projectId, workspaceId);
            when(traceThreadService.updateThreadSampledValue(eq(projectId), any()))
                    .thenReturn(Mono.empty());

            listener.onTraceThreadOnlineScoringSampled(event);

            verify(ruleEvaluatorService).findAll(projectId, workspaceId);

            ArgumentCaptor<List<TraceThreadSampling>> captor = ArgumentCaptor.forClass(List.class);
            verify(traceThreadService).updateThreadSampledValue(eq(projectId), captor.capture());
            assertThat(captor.getValue()).isNotEmpty();
            assertThat(captor.getValue())
                    .allSatisfy(sampling -> assertThat(sampling.threadModelId()).isEqualTo(thread.id()));
        }

        @ParameterizedTest
        @EnumSource(value = Source.class, mode = EnumSource.Mode.EXCLUDE, names = {"SDK"})
        void skipsNonSdkThreads(Source source) {
            var thread = createThread(source);
            var event = new TraceThreadsCreated(List.of(thread), projectId, workspaceId, userName);

            listener.onTraceThreadOnlineScoringSampled(event);

            verifyNoInteractions(ruleEvaluatorService);
            verifyNoInteractions(traceThreadService);
        }

        @Test
        void filtersMixedBatchKeepingOnlySdkThreads() {
            var sdkThread = createThread(Source.SDK);
            var playgroundThread = createThread(Source.PLAYGROUND);
            var evaluator = createEvaluator();
            var event = new TraceThreadsCreated(List.of(sdkThread, playgroundThread), projectId,
                    workspaceId, userName);

            doReturn(List.of(evaluator))
                    .when(ruleEvaluatorService).findAll(projectId, workspaceId);
            when(traceThreadService.updateThreadSampledValue(eq(projectId), any()))
                    .thenReturn(Mono.empty());

            listener.onTraceThreadOnlineScoringSampled(event);

            verify(ruleEvaluatorService).findAll(projectId, workspaceId);

            ArgumentCaptor<List<TraceThreadSampling>> captor = ArgumentCaptor.forClass(List.class);
            verify(traceThreadService).updateThreadSampledValue(eq(projectId), captor.capture());

            // Only the SDK thread should appear in sampling results — playground was filtered out
            assertThat(captor.getValue())
                    .allSatisfy(sampling -> assertThat(sampling.threadModelId()).isEqualTo(sdkThread.id()));
            assertThat(captor.getValue())
                    .noneSatisfy(sampling -> assertThat(sampling.threadModelId()).isEqualTo(playgroundThread.id()));
        }
    }

    @Nested
    class EmptyCasesTests {

        @Test
        void handlesEmptyThreadsList() {
            var event = new TraceThreadsCreated(List.of(), projectId, workspaceId, userName);

            listener.onTraceThreadOnlineScoringSampled(event);

            verifyNoInteractions(ruleEvaluatorService);
            verifyNoInteractions(traceThreadService);
        }

        @Test
        void handlesNoEvaluatorsFound() {
            var thread = createThread(Source.SDK);
            var event = new TraceThreadsCreated(List.of(thread), projectId, workspaceId, userName);

            doReturn(List.of())
                    .when(ruleEvaluatorService).findAll(projectId, workspaceId);

            listener.onTraceThreadOnlineScoringSampled(event);

            verify(ruleEvaluatorService).findAll(projectId, workspaceId);
            verify(traceThreadService, never()).updateThreadSampledValue(any(), any());
        }
    }

    private TraceThreadModel createThread(Source source) {
        return podamFactory.manufacturePojo(TraceThreadModel.class).toBuilder()
                .projectId(projectId)
                .source(source)
                .build();
    }

    private AutomationRuleEvaluatorTraceThreadLlmAsJudge createEvaluator() {
        return podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                .projects(toProjects(Set.of(projectId)))
                .samplingRate(1.0f)
                .enabled(true)
                .filters(List.of())
                .build();
    }
}
