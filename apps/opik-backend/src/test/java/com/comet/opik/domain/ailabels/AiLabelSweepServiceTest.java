package com.comet.opik.domain.ailabels;

import com.comet.opik.domain.ailabels.AiLabelSweepDAO.SweepTask;
import com.comet.opik.domain.ailabels.AiLabelSweepDAO.WorkspaceHealth;
import com.comet.opik.infrastructure.AiLabelsConfig;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiLabelSweepServiceTest {

    private static final List<List<String>> BUCKETS = List.of(List.of("workspace-a"), List.of("workspace-b"));
    private static final List<SweepTask> TASKS = List.of(
            new SweepTask("summary", List.of(1), List.of(Instant.parse("2026-01-01T00:00:00Z"))));

    private final AiLabelSweepDAO sweepDAO = mock(AiLabelSweepDAO.class);
    private final TransactionTemplate template = mock(TransactionTemplate.class);
    private final AiLabelSweepService service = new AiLabelSweepService(sweepDAO, template, new AiLabelsConfig());

    @Test
    void healthKeepsTheLastCompleteSnapshotWhenABucketsHealthQueryFails() {
        assertKeepsTheLastCompleteSnapshot(() -> when(sweepDAO.health(eq(BUCKETS.get(1)), anyList(), eq(1)))
                .thenReturn(Flux.error(new IllegalStateException("bucket down"))));
    }

    @Test
    void healthKeepsTheLastCompleteSnapshotWhenABucketsNeverEndedQueryFails() {
        assertKeepsTheLastCompleteSnapshot(() -> when(sweepDAO.neverEnded(eq(BUCKETS.get(1)), eq(1)))
                .thenReturn(Mono.error(new IllegalStateException("bucket down"))));
    }

    private void assertKeepsTheLastCompleteSnapshot(Runnable failBucket) {
        var a = new WorkspaceHealth("workspace-a", 10, 2);
        var b = new WorkspaceHealth("workspace-b", 5, 1);
        when(sweepDAO.health(eq(BUCKETS.get(0)), anyList(), eq(0))).thenReturn(Flux.just(a));
        when(sweepDAO.health(eq(BUCKETS.get(1)), anyList(), eq(1))).thenReturn(Flux.just(b));
        when(sweepDAO.neverEnded(anyList(), anyInt())).thenReturn(Mono.just(3L));

        var complete = service.refreshHealth(BUCKETS, TASKS).block();

        failBucket.run();
        var afterFailure = service.refreshHealth(BUCKETS, TASKS).block();

        assertThat(complete.workspaces()).containsExactly(a, b);
        assertThat(complete.neverEnded()).isEqualTo(6);
        assertThat(afterFailure).isEqualTo(complete);
    }

    @Test
    void healthReportsNothingUntilARefreshCompletes() {
        when(sweepDAO.health(anyList(), anyList(), anyInt()))
                .thenReturn(Flux.error(new IllegalStateException("bucket down")));
        when(sweepDAO.neverEnded(anyList(), anyInt())).thenReturn(Mono.just(3L));

        var snapshot = service.refreshHealth(BUCKETS, TASKS).blockOptional();

        assertThat(snapshot).isEmpty();
    }

    @Test
    void failedInFlightReadSkipsOnlyItsBucket() {
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenThrow(new IllegalStateException("mysql down"));

        var candidates = service.sweep(BUCKETS.getFirst(), 0, TASKS).collectList().block();

        assertThat(candidates).isEmpty();
        verify(sweepDAO, never()).sweep(anyList(), anyList(), anyInt(), any());
    }
}
