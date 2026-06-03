package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.events.TraceToSummarize;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.TraceSummaryPublisher;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceSummarizationListenerTest {

    private static final String WORKSPACE_ID = "workspace-" + UUID.randomUUID();
    private static final String USER = "user-" + UUID.randomUUID();
    private static final double ALWAYS = 1.0;
    private static final double NEVER = 0.0;

    @Mock
    private TraceSummaryPublisher traceSummaryPublisher;

    @Mock
    private ServiceTogglesConfig serviceTogglesConfig;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private TraceSummarizationListener listener(double samplingRate) {
        return new TraceSummarizationListener(traceSummaryPublisher, serviceTogglesConfig, samplingRate);
    }

    private Trace completeTrace() {
        return podamFactory.manufacturePojo(Trace.class).toBuilder().endTime(Instant.now()).build();
    }

    @Test
    @DisplayName("when toggle is disabled, then do nothing")
    void onTracesCreated__whenToggleDisabled__thenDoNothing() {
        when(serviceTogglesConfig.isTraceSummarizationEnabled()).thenReturn(false);

        listener(ALWAYS).onTracesCreated(new TracesCreated(List.of(completeTrace()), WORKSPACE_ID, USER));

        verifyNoInteractions(traceSummaryPublisher);
    }

    @Test
    @DisplayName("when created traces are complete and sampled, then enqueue only the complete ids")
    void onTracesCreated__whenCompleteAndSampled__thenEnqueueCompleteOnly() {
        when(serviceTogglesConfig.isTraceSummarizationEnabled()).thenReturn(true);
        when(traceSummaryPublisher.enqueue(anyList())).thenReturn(Mono.empty());

        var complete = completeTrace();
        var incomplete = podamFactory.manufacturePojo(Trace.class).toBuilder().endTime(null).build();

        listener(ALWAYS).onTracesCreated(new TracesCreated(List.of(complete, incomplete), WORKSPACE_ID, USER));

        var captor = ArgumentCaptor.forClass(List.class);
        verify(traceSummaryPublisher, timeout(2_000)).enqueue(captor.capture());

        List<TraceToSummarize> enqueued = captor.getValue();
        assertThat(enqueued).hasSize(1);
        assertThat(enqueued.getFirst().traceId()).isEqualTo(complete.id());
        assertThat(enqueued.getFirst().workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(enqueued.getFirst().userName()).isEqualTo(USER);
    }

    @Test
    @DisplayName("when sampling rate is zero, then never enqueue")
    void onTracesCreated__whenSampledOut__thenNeverEnqueue() {
        when(serviceTogglesConfig.isTraceSummarizationEnabled()).thenReturn(true);

        listener(NEVER).onTracesCreated(new TracesCreated(List.of(completeTrace(), completeTrace()), WORKSPACE_ID,
                USER));

        verify(traceSummaryPublisher, never()).enqueue(anyList());
    }

    @Test
    @DisplayName("when updated trace has no end_time, then do not enqueue")
    void onTracesUpdated__whenNoEndTime__thenDoNothing() {
        when(serviceTogglesConfig.isTraceSummarizationEnabled()).thenReturn(true);

        var update = podamFactory.manufacturePojo(TraceUpdate.class).toBuilder().endTime(null).build();
        listener(ALWAYS).onTracesUpdated(new TracesUpdated(Set.of(UUID.randomUUID()), Set.of(UUID.randomUUID()),
                WORKSPACE_ID, USER, update));

        verify(traceSummaryPublisher, never()).enqueue(anyList());
    }

    @Test
    @DisplayName("when update sets end_time and is sampled, then enqueue the sampled ids")
    void onTracesUpdated__whenEndTimeSetAndSampled__thenEnqueueIds() {
        when(serviceTogglesConfig.isTraceSummarizationEnabled()).thenReturn(true);
        when(traceSummaryPublisher.enqueue(anyList())).thenReturn(Mono.empty());

        var traceId = UUID.randomUUID();
        var update = podamFactory.manufacturePojo(TraceUpdate.class).toBuilder().endTime(Instant.now()).build();
        listener(ALWAYS).onTracesUpdated(new TracesUpdated(Set.of(UUID.randomUUID()), Set.of(traceId),
                WORKSPACE_ID, USER, update));

        var captor = ArgumentCaptor.forClass(List.class);
        verify(traceSummaryPublisher, timeout(2_000)).enqueue(captor.capture());

        List<TraceToSummarize> enqueued = captor.getValue();
        assertThat(enqueued).hasSize(1);
        assertThat(enqueued.getFirst().traceId()).isEqualTo(traceId);
    }

    @Test
    @DisplayName("when update is sampled out, then never enqueue")
    void onTracesUpdated__whenSampledOut__thenDoNothing() {
        when(serviceTogglesConfig.isTraceSummarizationEnabled()).thenReturn(true);

        var update = podamFactory.manufacturePojo(TraceUpdate.class).toBuilder().endTime(Instant.now()).build();
        listener(NEVER).onTracesUpdated(new TracesUpdated(Set.of(UUID.randomUUID()), Set.of(UUID.randomUUID()),
                WORKSPACE_ID, USER, update));

        verify(traceSummaryPublisher, never()).enqueue(anyList());
    }
}
