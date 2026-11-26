package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TopicsRetentionTest {

    private static final UUID CUTOFF = UUID.randomUUID();
    private static final UUID LOWER = UUID.randomUUID();

    private final TransactionTemplate template = mock(TransactionTemplate.class);
    private final TopicVectorsDAO vectorsDAO = mock(TopicVectorsDAO.class);
    private final TraceTopicsDAO traceTopicsDAO = mock(TraceTopicsDAO.class);
    private final TopicsRetention retention = new TopicsRetention(template, vectorsDAO, traceTopicsDAO);

    @Test
    @DisplayName("only the workspaces with Topics data get the deletes, vectors first")
    void onlyWorkspacesWithTopics() {
        when(template.inTransaction(any(TxConfig.class), any())).thenReturn(List.of("b"));
        when(vectorsDAO.deleteForRetention(List.of("b"), CUTOFF, LOWER)).thenReturn(Mono.just(1L));
        when(traceTopicsDAO.deleteForRetention(List.of("b"), CUTOFF, LOWER)).thenReturn(Mono.just(2L));

        var deleted = retention.deleteForRetention(List.of("a", "b"), CUTOFF, LOWER, (table, e) -> Mono.just(-1L))
                .collectList().block();

        assertThat(deleted).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("no workspace with Topics data: no delete")
    void noWorkspaceWithTopics() {
        when(template.inTransaction(any(TxConfig.class), any())).thenReturn(List.of());

        var deleted = retention.deleteForRetentionBounded(Map.of("a", LOWER), CUTOFF, LOWER,
                (table, e) -> Mono.just(-1L)).collectList().block();

        assertThat(deleted).isEmpty();
        verifyNoInteractions(vectorsDAO, traceTopicsDAO);
    }

    @Test
    @DisplayName("the read fails: the deletes run for every workspace of the batch, and a failed delete is the "
            + "caller's fallback")
    void failedReadDeletesForAll() {
        var minIds = new LinkedHashMap<String, UUID>();
        minIds.put("a", LOWER);
        minIds.put("b", CUTOFF);
        when(template.inTransaction(any(TxConfig.class), any())).thenThrow(new IllegalStateException("down"));
        when(vectorsDAO.deleteForRetentionBounded(minIds, CUTOFF, LOWER))
                .thenReturn(Mono.error(new IllegalStateException("delete failed")));
        when(traceTopicsDAO.deleteForRetentionBounded(minIds, CUTOFF, LOWER)).thenReturn(Mono.just(3L));

        var deleted = retention.deleteForRetentionBounded(minIds, CUTOFF, LOWER,
                (table, e) -> Mono.just(table.equals(TopicVectorsDAO.TABLE) ? -1L : -2L)).collectList().block();

        assertThat(deleted).containsExactly(-1L, 3L);
        verify(vectorsDAO).deleteForRetentionBounded(minIds, CUTOFF, LOWER);
    }
}
