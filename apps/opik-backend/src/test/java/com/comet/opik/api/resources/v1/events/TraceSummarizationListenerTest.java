package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.TraceSummaryService;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private TraceSummaryService traceSummaryService;

    @Mock
    private TraceService traceService;

    @Mock
    private ServiceTogglesConfig serviceTogglesConfig;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private TraceSummarizationListener listener(double samplingRate) {
        return new TraceSummarizationListener(traceSummaryService, traceService, serviceTogglesConfig, samplingRate);
    }

    private Trace completeTrace() {
        return podamFactory.manufacturePojo(Trace.class).toBuilder().endTime(Instant.now()).build();
    }

    @Test
    @DisplayName("when toggle is disabled, then do nothing")
    void onTracesCreated__whenToggleDisabled__thenDoNothing() {
        when(serviceTogglesConfig.isTraceSummarizationEnabled()).thenReturn(false);

        listener(ALWAYS).onTracesCreated(new TracesCreated(List.of(completeTrace()), WORKSPACE_ID, USER));

        verifyNoInteractions(traceSummaryService);
        verifyNoInteractions(traceService);
    }

    @Test
    @DisplayName("when created traces are complete and sampled, then summarize only the complete ones")
    void onTracesCreated__whenCompleteAndSampled__thenSummarizeCompleteOnly() {
        when(serviceTogglesConfig.isTraceSummarizationEnabled()).thenReturn(true);
        when(traceSummaryService.summarize(anyList(), anyString())).thenReturn(Mono.empty());

        var complete = completeTrace();
        var incomplete = podamFactory.manufacturePojo(Trace.class).toBuilder().endTime(null).build();

        listener(ALWAYS).onTracesCreated(new TracesCreated(List.of(complete, incomplete), WORKSPACE_ID, USER));

        var captor = ArgumentCaptor.forClass(List.class);
        verify(traceSummaryService, timeout(2_000)).summarize(captor.capture(), eq(WORKSPACE_ID));

        List<Trace> summarized = captor.getValue();
        assertThat(summarized).extracting(Trace::id).containsExactly(complete.id());
    }

    @Test
    @DisplayName("when sampling rate is zero, then never summarize")
    void onTracesCreated__whenSampledOut__thenNeverSummarize() {
        when(serviceTogglesConfig.isTraceSummarizationEnabled()).thenReturn(true);

        listener(NEVER).onTracesCreated(new TracesCreated(List.of(completeTrace(), completeTrace()), WORKSPACE_ID,
                USER));

        verify(traceSummaryService, never()).summarize(anyList(), anyString());
    }

    @Test
    @DisplayName("when updated trace has no end_time, then do not fetch or summarize")
    void onTracesUpdated__whenNoEndTime__thenDoNothing() {
        when(serviceTogglesConfig.isTraceSummarizationEnabled()).thenReturn(true);

        var update = podamFactory.manufacturePojo(TraceUpdate.class).toBuilder().endTime(null).build();
        listener(ALWAYS).onTracesUpdated(new TracesUpdated(Set.of(UUID.randomUUID()), Set.of(UUID.randomUUID()),
                WORKSPACE_ID, USER, update));

        verify(traceService, never()).getByIds(anyList());
        verify(traceSummaryService, never()).summarize(anyList(), anyString());
    }

    @Test
    @DisplayName("when update sets end_time and is sampled, then fetch ids and summarize complete traces")
    void onTracesUpdated__whenEndTimeSetAndSampled__thenFetchAndSummarize() {
        when(serviceTogglesConfig.isTraceSummarizationEnabled()).thenReturn(true);
        when(traceSummaryService.summarize(anyList(), anyString())).thenReturn(Mono.empty());

        var complete = completeTrace();
        var stillIncomplete = podamFactory.manufacturePojo(Trace.class).toBuilder().endTime(null).build();
        when(traceService.getByIds(anyList())).thenReturn(Flux.just(complete, stillIncomplete));

        var traceId = complete.id();
        var update = podamFactory.manufacturePojo(TraceUpdate.class).toBuilder().endTime(Instant.now()).build();
        listener(ALWAYS).onTracesUpdated(new TracesUpdated(Set.of(complete.projectId()), Set.of(traceId),
                WORKSPACE_ID, USER, update));

        var idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(traceService, timeout(2_000)).getByIds(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(traceId);

        var summarizeCaptor = ArgumentCaptor.forClass(List.class);
        verify(traceSummaryService, timeout(2_000)).summarize(summarizeCaptor.capture(), eq(WORKSPACE_ID));
        List<Trace> summarized = summarizeCaptor.getValue();
        assertThat(summarized).extracting(Trace::id).containsExactly(complete.id());
    }

    @Test
    @DisplayName("when update is sampled out, then never fetch or summarize")
    void onTracesUpdated__whenSampledOut__thenDoNothing() {
        when(serviceTogglesConfig.isTraceSummarizationEnabled()).thenReturn(true);

        var update = podamFactory.manufacturePojo(TraceUpdate.class).toBuilder().endTime(Instant.now()).build();
        listener(NEVER).onTracesUpdated(new TracesUpdated(Set.of(UUID.randomUUID()), Set.of(UUID.randomUUID()),
                WORKSPACE_ID, USER, update));

        verify(traceService, never()).getByIds(anyList());
        verify(traceSummaryService, never()).summarize(anyList(), anyString());
    }
}
