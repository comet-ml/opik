package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("window read")
class WindowReadTest {

    private static final Duration SLICE = Duration.ofDays(1);
    private static final Duration MAX_EXECUTION_TIME = Duration.ofSeconds(30);
    private static final Duration RESERVE = Duration.ofSeconds(1);

    private final Instant now = TopicsTestClock.randomInstant();
    private final TopicsTestClock clock = new TopicsTestClock(now);
    private final TopicVectorsDAO dao = mock(TopicVectorsDAO.class);
    private final String workspaceId = UUID.randomUUID().toString();

    @Test
    @DisplayName("the read stops at the limit across slices, and each slice asks for the count still missing")
    void limitAcrossSlices() {
        when(dao.findUnvectored(any())).thenReturn(Flux.fromIterable(candidates(3)), Flux.fromIterable(candidates(2)));

        var read = WindowRead.read(dao, find(3, 5), SLICE, budget(Duration.ofSeconds(50)), MAX_EXECUTION_TIME, RESERVE)
                .block();

        assertThat(read.candidates()).hasSize(5);
        assertThat(read.complete()).isFalse();
        var finds = finds(2);
        assertThat(finds).extracting(TopicVectorsFind::limit).containsExactly(5, 2);
        assertThat(finds).extracting(TopicVectorsFind::createdAfter)
                .containsExactly(now.minus(SLICE), now.minus(SLICE.multipliedBy(2)));
    }

    @Test
    @DisplayName("a budget that ends between two slices returns what the read found, not complete")
    void budgetEndsBetweenSlices() {
        // A read starts with at least 1s plus the 1s reserve left: the first read leaves 1s.
        when(dao.findUnvectored(any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(4));
            return Flux.fromIterable(candidates(1));
        });

        var read = WindowRead.read(dao, find(3, 5), SLICE, budget(Duration.ofSeconds(5)), MAX_EXECUTION_TIME, RESERVE)
                .block();

        assertThat(read.candidates()).hasSize(1);
        assertThat(read.complete()).isFalse();
        finds(1);
    }

    @Test
    @DisplayName("the last slice starts exactly at createdAfter: the read is complete and queries no further")
    void lastSliceAtCreatedAfter() {
        when(dao.findUnvectored(any())).thenReturn(Flux.fromIterable(candidates(1)), Flux.empty());

        var read = WindowRead.read(dao, find(2, 5), SLICE, budget(Duration.ofSeconds(50)), MAX_EXECUTION_TIME, RESERVE)
                .block();

        assertThat(read.candidates()).hasSize(1);
        assertThat(read.complete()).isTrue();
        assertThat(finds(2)).extracting(TopicVectorsFind::createdAfter)
                .containsExactly(now.minus(SLICE), now.minus(SLICE.multipliedBy(2)));
    }

    private TopicVectorsFind find(int days, int limit) {
        return TopicVectorsFind.builder()
                .workspaceId(workspaceId)
                .task(TopicEmbedding.SUMMARY_TASK)
                .lowerBound(now.minus(days, ChronoUnit.DAYS))
                .upperBound(now)
                .createdAfter(now.minus(days, ChronoUnit.DAYS))
                .limit(limit)
                .build();
    }

    private TickBudget budget(Duration budget) {
        return TickBudget.of(now, budget, clock);
    }

    private List<TopicVectorsFind> finds(int count) {
        var captor = ArgumentCaptor.forClass(TopicVectorsFind.class);
        verify(dao, times(count)).findUnvectored(captor.capture());
        return captor.getAllValues();
    }

    private List<Candidate> candidates(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Candidate.builder().workspaceId(workspaceId).projectId(UUID.randomUUID())
                        .traceId(UUID.randomUUID()).traceCreatedAt(now).taskVersion(1).statusUpdatedAt(now)
                        .summary("summary").build())
                .toList();
    }
}
