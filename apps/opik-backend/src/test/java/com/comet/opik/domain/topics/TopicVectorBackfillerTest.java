package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.BackfillProject;
import com.comet.opik.domain.topics.TopicVectorsDAO.Slice;
import com.comet.opik.infrastructure.TopicsConfig;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("topic vectors backfiller")
class TopicVectorBackfillerTest {

    private final Instant now = TopicsTestClock.randomInstant();

    @Test
    @DisplayName("projects that drain in one read go first, smallest first, then the large ones, the most recently "
            + "active first among equals")
    void order() {
        int cap = 100;
        var small = backfill(10, now);
        var smallerOlder = backfill(5, now.minusSeconds(60));
        var largeRecent = backfill(500, now);
        var largeOlder = backfill(10_000, now.minusSeconds(60));
        var sameAsSmallOlder = backfill(10, now.minusSeconds(60));

        var ordered = List.of(largeOlder, small, sameAsSmallOlder, largeRecent, smallerOlder).stream()
                .sorted(TopicVectorBackfiller.order(cap)).toList();

        assertThat(ordered).containsExactly(smallerOlder, small, sameAsSmallOlder, largeRecent, largeOlder);
    }

    @Test
    @DisplayName("a discovery the budget cuts short between slices writes nothing; the next tick resumes it at the "
            + "next slice, gives each read the seconds left, and writes it once, as started by the first tick")
    void discoveryResumes() {
        var find = new TopicsConfig.FindConfig();
        find.setMaxExecutionTime(Duration.seconds(30));
        var vectors = new TopicsConfig.VectorsConfig();
        vectors.setFind(find);
        vectors.setEmbedTimeout(Duration.seconds(1));
        vectors.setLongWindowInterval(Duration.days(1));
        var config = new TopicsConfig();
        config.setVectors(vectors);
        var dao = mock(TopicVectorsDAO.class);
        var backfills = mock(TopicVectorBackfillService.class);
        var backfiller = new TopicVectorBackfiller(config, dao, backfills, mock(TopicVectorWriter.class),
                mock(TopicVectorsMetrics.class));
        var clock = new TopicsTestClock(now);
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var newer = new Slice(now.minus(7, ChronoUnit.DAYS), null);
        var older = new Slice(now.minus(14, ChronoUnit.DAYS), now.minus(7, ChronoUnit.DAYS));
        when(dao.slices(any())).thenReturn(List.of(newer, older));
        // Every read takes 5s of the tick.
        when(dao.findBackfillProjects(eq(workspaceId), any(), any(), any(), anyLong())).thenAnswer(invocation -> {
            clock.advance(java.time.Duration.ofSeconds(5));
            return Flux.just(new BackfillProject(projectId, 2, now));
        });
        when(dao.countTracesWithVectorsByProject(eq(workspaceId), any(), any(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    clock.advance(java.time.Duration.ofSeconds(5));
                    return Mono.just(Map.of(projectId, 1L));
                });
        var lowerBound = now.minus(7, ChronoUnit.DAYS);
        // A read starts with at least 1s plus the 1s embed timeout left: 11s fit two reads.
        var tickBudget = java.time.Duration.ofSeconds(11);
        var first = TickBudget.of(now, tickBudget, clock);

        backfiller.discover(List.of(workspaceId), lowerBound, older.from(), first).block();

        verify(dao).findBackfillProjects(workspaceId, TopicEmbedding.SUMMARY_TASK, lowerBound, newer, 10);
        verify(dao).findBackfillProjects(workspaceId, TopicEmbedding.SUMMARY_TASK, lowerBound, older, 5);
        verify(dao, never()).countTracesWithVectorsByProject(any(), any(), any(), any(), anyLong());
        verify(backfills, never()).discovered(any(), any(), any(), any());

        backfiller.discover(List.of(workspaceId), lowerBound, older.from(),
                TickBudget.of(clock.instant(), tickBudget, clock)).block();

        verify(dao, times(2)).findBackfillProjects(any(), any(), any(), any(), anyLong());
        verify(dao, times(2)).countTracesWithVectorsByProject(any(), any(), any(), any(), anyLong());
        verify(backfills).discovered(workspaceId, List.of(TopicBackfill.builder().workspaceId(workspaceId)
                .projectId(projectId).backfillStartedAt(now).backfillLastDoneAt(now).backfillTracesTotal(4)
                .backfillTracesDone(2).build()), now, now.minus(2, ChronoUnit.DAYS));
    }

    private static TopicBackfill backfill(long remaining, Instant lastDoneAt) {
        return TopicBackfill.builder()
                .workspaceId(UUID.randomUUID().toString())
                .projectId(UUID.randomUUID())
                .backfillStartedAt(lastDoneAt)
                .backfillLastDoneAt(lastDoneAt)
                .backfillTracesTotal(remaining)
                .build();
    }
}
