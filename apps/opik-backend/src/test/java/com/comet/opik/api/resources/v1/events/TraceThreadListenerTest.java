package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Source;
import com.comet.opik.api.ThreadTimestamps;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.db.IdGeneratorImpl;
import com.comet.opik.podam.PodamFactoryUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceThreadListenerTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();
    private final IdGenerator idGenerator = new IdGeneratorImpl();

    @Mock
    private TraceThreadService traceThreadService;

    @InjectMocks
    private TraceThreadListener listener;

    private UUID projectId;
    private String workspaceId;
    private String userName;

    @BeforeEach
    void setUp() {
        projectId = idGenerator.generateId();
        workspaceId = UUID.randomUUID().toString();
        userName = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);
    }

    @Nested
    class SourcePropagationTests {

        @ParameterizedTest
        @EnumSource(Source.class)
        @NullSource
        void propagatesSourceFromSingleTrace(Source source) {
            var threadId = randomThreadId();
            var now = Instant.now();
            var trace = createTrace(projectId, threadId, source, now);
            var event = new TracesCreated(List.of(trace), workspaceId, userName);

            when(traceThreadService.processTraceThreads(any(), eq(projectId)))
                    .thenReturn(Mono.empty());

            listener.onTracesCreated(event);

            var captured = captureThreadInfo(projectId);
            assertThat(captured).containsExactlyEntriesOf(Map.of(
                    threadId, ThreadTimestamps.builder()
                            .firstTraceId(trace.id())
                            .maxLastUpdatedAt(now)
                            .firstTraceSource(source)
                            .build()));
        }

        static Stream<Arguments> sourceAlwaysMatchesEarliestTrace() {
            return Stream.of(
                    // processLaterFirst=true: later trace processed first, then earlier swaps firstTraceId + source
                    Arguments.of(true, Source.SDK, Source.PLAYGROUND),
                    // processLaterFirst=false: earlier trace processed first, later doesn't swap
                    Arguments.of(false, Source.SDK, Source.PLAYGROUND),
                    Arguments.of(true, Source.PLAYGROUND, Source.SDK),
                    Arguments.of(false, Source.PLAYGROUND, Source.SDK));
        }

        @ParameterizedTest
        @MethodSource
        void sourceAlwaysMatchesEarliestTrace(boolean processLaterFirst,
                Source earlierSource, Source laterSource) {
            var threadId = randomThreadId();
            var now = Instant.now();
            var earlierTime = now.minus(1, ChronoUnit.SECONDS);

            var earlierTrace = createTrace(projectId, threadId, earlierSource, earlierTime);
            var laterTrace = createTrace(projectId, threadId, laterSource, now);

            var traces = processLaterFirst
                    ? List.of(laterTrace, earlierTrace)
                    : List.of(earlierTrace, laterTrace);
            var event = new TracesCreated(traces, workspaceId, userName);

            when(traceThreadService.processTraceThreads(any(), eq(projectId)))
                    .thenReturn(Mono.empty());

            listener.onTracesCreated(event);

            var captured = captureThreadInfo(projectId);
            assertThat(captured.get(threadId)).isEqualTo(ThreadTimestamps.builder()
                    .firstTraceId(earlierTrace.id())
                    .maxLastUpdatedAt(now)
                    .firstTraceSource(earlierSource)
                    .build());
        }
    }

    @Nested
    class ThreadIdFilteringTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        void skipsTracesWithBlankOrNullThreadId(String threadId) {
            var trace = createTrace(projectId, threadId, Source.SDK, Instant.now());
            var event = new TracesCreated(List.of(trace), workspaceId, userName);

            listener.onTracesCreated(event);

            verifyNoInteractions(traceThreadService);
        }

        @Test
        void processesTraceWithNonBlankThreadId() {
            var threadId = randomThreadId();
            var now = Instant.now();
            var trace = createTrace(projectId, threadId, Source.SDK, now);
            var event = new TracesCreated(List.of(trace), workspaceId, userName);

            when(traceThreadService.processTraceThreads(any(), eq(projectId)))
                    .thenReturn(Mono.empty());

            listener.onTracesCreated(event);

            var captured = captureThreadInfo(projectId);
            assertThat(captured).containsExactlyEntriesOf(Map.of(
                    threadId, ThreadTimestamps.builder()
                            .firstTraceId(trace.id())
                            .maxLastUpdatedAt(now)
                            .firstTraceSource(Source.SDK)
                            .build()));
        }
    }

    @Nested
    class TimestampAggregationTests {

        @Test
        void keepsMaxLastUpdatedAtAcrossMultipleTraces() {
            var threadId = randomThreadId();
            var earlier = Instant.now().minus(10, ChronoUnit.SECONDS);
            var later = Instant.now();

            var trace1 = createTrace(projectId, threadId, Source.SDK, earlier);
            var trace2 = createTrace(projectId, threadId, Source.SDK, later);
            var event = new TracesCreated(List.of(trace1, trace2), workspaceId, userName);

            when(traceThreadService.processTraceThreads(any(), eq(projectId)))
                    .thenReturn(Mono.empty());

            listener.onTracesCreated(event);

            var captured = captureThreadInfo(projectId);
            assertThat(captured.get(threadId)).isEqualTo(ThreadTimestamps.builder()
                    .firstTraceId(trace1.id())
                    .maxLastUpdatedAt(later)
                    .firstTraceSource(Source.SDK)
                    .build());
        }

        @Test
        void fallsBackToInstantNowWhenLastUpdatedAtIsNull() {
            var threadId = randomThreadId();
            var before = Instant.now().minus(1, ChronoUnit.SECONDS);

            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.generateId())
                    .projectId(projectId)
                    .threadId(threadId)
                    .source(Source.SDK)
                    .lastUpdatedAt(null)
                    .build();
            var event = new TracesCreated(List.of(trace), workspaceId, userName);

            when(traceThreadService.processTraceThreads(any(), eq(projectId)))
                    .thenReturn(Mono.empty());

            listener.onTracesCreated(event);

            var timestamps = captureThreadInfo(projectId).get(threadId);
            assertThat(timestamps.maxLastUpdatedAt()).isAfter(before);
            // Full-object assertion using the captured maxLastUpdatedAt (unpredictable Instant.now())
            assertThat(timestamps).isEqualTo(ThreadTimestamps.builder()
                    .firstTraceId(trace.id())
                    .maxLastUpdatedAt(timestamps.maxLastUpdatedAt())
                    .firstTraceSource(Source.SDK)
                    .build());
        }
    }

    @Nested
    class MultiProjectTests {

        @Test
        void groupsTracesByProjectAndCallsServicePerProject() {
            var projectId2 = idGenerator.generateId();
            var threadId1 = randomThreadId();
            var threadId2 = randomThreadId();
            var now = Instant.now();

            var trace1 = createTrace(projectId, threadId1, Source.SDK, now);
            var trace2 = createTrace(projectId2, threadId2, Source.PLAYGROUND, now);
            var event = new TracesCreated(List.of(trace1, trace2), workspaceId, userName);

            when(traceThreadService.processTraceThreads(any(), any()))
                    .thenReturn(Mono.empty());

            listener.onTracesCreated(event);

            var captured1 = captureThreadInfo(projectId);
            assertThat(captured1).containsExactlyEntriesOf(Map.of(
                    threadId1, ThreadTimestamps.builder()
                            .firstTraceId(trace1.id())
                            .maxLastUpdatedAt(now)
                            .firstTraceSource(Source.SDK)
                            .build()));

            var captured2 = captureThreadInfo(projectId2);
            assertThat(captured2).containsExactlyEntriesOf(Map.of(
                    threadId2, ThreadTimestamps.builder()
                            .firstTraceId(trace2.id())
                            .maxLastUpdatedAt(now)
                            .firstTraceSource(Source.PLAYGROUND)
                            .build()));
        }
    }

    @Nested
    class MultipleThreadsTests {

        @Test
        void handlesMultipleThreadsInSameProject() {
            var threadId1 = randomThreadId();
            var threadId2 = randomThreadId();
            var now = Instant.now();

            var trace1 = createTrace(projectId, threadId1, Source.SDK, now);
            var trace2 = createTrace(projectId, threadId2, Source.PLAYGROUND, now);
            var event = new TracesCreated(List.of(trace1, trace2), workspaceId, userName);

            when(traceThreadService.processTraceThreads(any(), eq(projectId)))
                    .thenReturn(Mono.empty());

            listener.onTracesCreated(event);

            var captured = captureThreadInfo(projectId);
            assertThat(captured).containsExactlyInAnyOrderEntriesOf(Map.of(
                    threadId1, ThreadTimestamps.builder()
                            .firstTraceId(trace1.id())
                            .maxLastUpdatedAt(now)
                            .firstTraceSource(Source.SDK)
                            .build(),
                    threadId2, ThreadTimestamps.builder()
                            .firstTraceId(trace2.id())
                            .maxLastUpdatedAt(now)
                            .firstTraceSource(Source.PLAYGROUND)
                            .build()));
        }
    }

    @Nested
    class EmptyCasesTests {

        @Test
        void handlesEmptyTracesList() {
            var event = new TracesCreated(List.of(), workspaceId, userName);

            listener.onTracesCreated(event);

            verifyNoInteractions(traceThreadService);
        }

        @Test
        void handlesAllTracesWithoutThreadId() {
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.generateId())
                    .projectId(projectId)
                    .threadId(null)
                    .build();
            var event = new TracesCreated(List.of(trace), workspaceId, userName);

            listener.onTracesCreated(event);

            verifyNoInteractions(traceThreadService);
        }
    }

    @Nested
    class ErrorHandlingTests {

        @Test
        void handlesServiceErrorGracefully() {
            var threadId = randomThreadId();
            var trace = createTrace(projectId, threadId, Source.SDK, Instant.now());
            var event = new TracesCreated(List.of(trace), workspaceId, userName);

            when(traceThreadService.processTraceThreads(any(), eq(projectId)))
                    .thenReturn(Mono.error(new RuntimeException("DB connection failed")));

            listener.onTracesCreated(event);

            verify(traceThreadService).processTraceThreads(any(), eq(projectId));
        }
    }

    // Helper methods

    private Trace createTrace(UUID traceProjectId, String threadId, Source source, Instant lastUpdatedAt) {
        return podamFactory.manufacturePojo(Trace.class).toBuilder()
                .id(idGenerator.generateId(lastUpdatedAt))
                .projectId(traceProjectId)
                .threadId(threadId)
                .source(source)
                .lastUpdatedAt(lastUpdatedAt)
                .build();
    }

    private String randomThreadId() {
        return "thread-" + RandomStringUtils.secure().nextAlphanumeric(32);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ThreadTimestamps> captureThreadInfo(UUID expectedProjectId) {
        ArgumentCaptor<Map<String, ThreadTimestamps>> captor = ArgumentCaptor.forClass(Map.class);
        verify(traceThreadService).processTraceThreads(captor.capture(), eq(expectedProjectId));
        return captor.getValue();
    }
}
