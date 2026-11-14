package com.comet.opik.domain.topics;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.ProjectTopics.DayCount;
import com.comet.opik.api.ProjectTopics.TopicStats;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

@DisplayName("topics read API fold")
class TopicsCountsTest {

    private static final int WINDOW_DAYS = 30;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    private static final LocalDate FIRST_BUILD = TODAY.minusDays(20);
    private static final String WORKSPACE_ID = "ws-1";
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final Instant LAST_RUN = TODAY.atTime(2, 40).toInstant(ZoneOffset.UTC);
    private static final TopicProjectState STATE = TopicProjectState.builder()
            .projectId(PROJECT_ID)
            .workspaceId(WORKSPACE_ID)
            .rebuilds(3)
            .firstBuiltAt(FIRST_BUILD.atTime(2, 0).toInstant(ZoneOffset.UTC))
            .lastSuccessAt(LAST_RUN)
            .windowUntil(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC))
            .labelsComplete(true)
            .build();

    @Test
    @DisplayName("the fields of every status: today, the window's first day, and no topic")
    void window() {
        var body = TopicsCounts.window(TODAY, WINDOW_DAYS).status(ProjectTopics.Status.DISABLED).build();

        assertThat(body.today()).isEqualTo(TODAY);
        assertThat(body.windowFrom()).isEqualTo(TODAY.minusDays(29));
        assertThat(body.topics()).isEmpty();
        assertThat(TopicsCounts.window(TODAY, 14).build().windowFrom()).isEqualTo(TODAY.minusDays(13));
    }

    @Test
    @DisplayName("ready body: the window, the last run and the total; a series of 30 days, oldest first, today last, "
            + "missing days as 0")
    void series() {
        var topic = topic(FIRST_BUILD);
        var body = fold(List.of(topic), List.of(
                row(topic, TODAY.minusDays(29), 2),
                row(topic, TODAY.minusDays(10), 5),
                row(topic, TODAY, 1)));

        assertThat(body.status()).isEqualTo(ProjectTopics.Status.READY);
        assertThat(body.today()).isEqualTo(TODAY);
        assertThat(body.windowFrom()).isEqualTo(TODAY.minusDays(29));
        assertThat(body.lastRunAt()).isEqualTo(LAST_RUN);
        assertThat(body.totalTraces()).isEqualTo(8L);
        var series = body.topics().getFirst().series();
        assertThat(series).hasSize(30);
        assertThat(series.getFirst()).isEqualTo(DayCount.builder().day(TODAY.minusDays(29)).count(2).build());
        assertThat(series.get(19)).isEqualTo(DayCount.builder().day(TODAY.minusDays(10)).count(5).build());
        assertThat(series.getLast()).isEqualTo(DayCount.builder().day(TODAY).count(1).build());
        assertThat(series).extracting(DayCount::day)
                .containsExactlyElementsOf(TODAY.minusDays(29).datesUntil(TODAY.plusDays(1)).toList());
        assertThat(series).filteredOn(day -> day.count() > 0).hasSize(3);
        assertThat(body.topics().getFirst().count()).isEqualTo(8);
        assertThat(body.other().series()).hasSize(30).allSatisfy(day -> assertThat(day.count()).isZero());
    }

    @Test
    @DisplayName("a 14-day window: series of 14 days for the topics and Other, the window starts at today - 13")
    void shorterWindow() {
        var topic = topic(FIRST_BUILD);
        var body = TopicsCounts.ready(TODAY, 14, List.of(topic), STATE, List.of(
                row(topic, TODAY.minusDays(13), 4),
                TopicDayCount.builder().topicId(TraceTopic.OTHER).day(TODAY).traces(1).build()));

        assertThat(body.windowFrom()).isEqualTo(TODAY.minusDays(13));
        assertThat(body.totalTraces()).isEqualTo(5L);
        assertThat(body.topics().getFirst().series()).hasSize(14)
                .first().isEqualTo(DayCount.builder().day(TODAY.minusDays(13)).count(4).build());
        assertThat(body.other().series()).hasSize(14)
                .last().isEqualTo(DayCount.builder().day(TODAY).count(1).build());
    }

    @Test
    @DisplayName("rank: by count, most first; ties by born, then id")
    void rank() {
        var big = topic(FIRST_BUILD);
        var biggest = topic(FIRST_BUILD);
        var tieOlder = topic(FIRST_BUILD);
        var tieYounger = topic(FIRST_BUILD.plusDays(2));
        var lowId = topic(new UUID(1L, 1L), FIRST_BUILD.plusDays(5));
        var highId = topic(new UUID(9L, 9L), FIRST_BUILD.plusDays(5));
        var body = fold(List.of(highId, tieYounger, biggest, lowId, tieOlder, big), List.of(
                row(big, TODAY, 50),
                row(biggest, TODAY, 90),
                row(tieOlder, TODAY, 10),
                row(tieYounger, TODAY, 10),
                row(lowId, TODAY, 4),
                row(highId, TODAY, 4)));

        assertThat(body.topics()).extracting(TopicStats::id, TopicStats::rank).containsExactly(
                tupleOf(biggest, 1), tupleOf(big, 2), tupleOf(tieOlder, 3), tupleOf(tieYounger, 4),
                tupleOf(lowId, 5), tupleOf(highId, 6));
    }

    @Test
    @DisplayName("share: count over total traces; the topics' shares and Other's series add up to 1; 0 with no rows")
    void share() {
        var first = topic(FIRST_BUILD);
        var second = topic(FIRST_BUILD);
        var body = fold(List.of(first, second), List.of(
                row(first, TODAY, 3),
                row(second, TODAY.minusDays(4), 2),
                TopicDayCount.builder().topicId(TraceTopic.OTHER).day(TODAY).traces(2).build()));

        assertThat(stats(body, first).share()).isCloseTo(3.0 / 7, within(1e-12));
        var otherCount = body.other().series().stream().mapToLong(DayCount::count).sum();
        var sum = body.topics().stream().mapToDouble(TopicStats::share).sum()
                + (double) otherCount / body.totalTraces();
        assertThat(sum).isCloseTo(1.0, within(1e-12));

        var empty = fold(List.of(first), List.of());
        assertThat(empty.totalTraces()).isZero();
        assertThat(empty.topics().getFirst().share()).isZero();
    }

    @Test
    @DisplayName("is_new: never a first build's topic; a later topic born today - 6 is New, born today - 7 is not")
    void isNew() {
        var firstBuild = topic(FIRST_BUILD);
        var sixDays = topic(TODAY.minusDays(6));
        var sevenDays = topic(TODAY.minusDays(7));
        var body = fold(List.of(firstBuild, sixDays, sevenDays), List.of());

        assertThat(stats(body, firstBuild).isNew()).isFalse();
        assertThat(stats(body, sixDays).isNew()).isTrue();
        assertThat(stats(body, sevenDays).isNew()).isFalse();

        var freshBuild = STATE.toBuilder().firstBuiltAt(TODAY.minusDays(2).atTime(2, 0).toInstant(ZoneOffset.UTC))
                .build();
        var builtTwoDaysAgo = topic(TODAY.minusDays(2));
        assertThat(TopicsCounts.ready(TODAY, WINDOW_DAYS, List.of(builtTwoDaysAgo), freshBuild, List.of())
                .topics().getFirst().isNew()).isFalse();
    }

    @Test
    @DisplayName("Other: rows of ids that are not live count as Other, with Other's own rows")
    void notLiveIdsCountAsOther() {
        var live = topic(FIRST_BUILD);
        var retiredId = UUID.randomUUID();
        var body = fold(List.of(live), List.of(
                row(live, TODAY, 5),
                TopicDayCount.builder().topicId(retiredId).day(TODAY.minusDays(1)).traces(3).build(),
                TopicDayCount.builder().topicId(retiredId).day(TODAY).traces(1).build(),
                TopicDayCount.builder().topicId(TraceTopic.OTHER).day(TODAY).traces(2).build()));

        assertThat(body.topics()).extracting(TopicStats::id).containsExactly(live.id());
        var other = body.other().series();
        assertThat(other.getLast()).isEqualTo(DayCount.builder().day(TODAY).count(3).build());
        assertThat(other.get(other.size() - 2)).isEqualTo(DayCount.builder().day(TODAY.minusDays(1)).count(3).build());
        assertThat(body.totalTraces()).isEqualTo(11L);
    }

    @Test
    @DisplayName("a live topic with no rows is listed with 30 zero days")
    void topicWithoutRows() {
        var quiet = topic(FIRST_BUILD);
        var busy = topic(FIRST_BUILD);
        var body = fold(List.of(quiet, busy), List.of(row(busy, TODAY, 1)));

        var stats = stats(body, quiet);
        assertThat(stats.count()).isZero();
        assertThat(stats.series()).hasSize(30).allSatisfy(day -> assertThat(day.count()).isZero());
    }

    @Test
    @DisplayName("labelled_from: the first day with any row, Other included; days before it are 0; omitted with no "
            + "rows")
    void labelledFrom() {
        var topic = topic(FIRST_BUILD);
        var body = fold(List.of(topic), List.of(
                TopicDayCount.builder().topicId(UUID.randomUUID()).day(TODAY.minusDays(4)).traces(1).build(),
                row(topic, TODAY.minusDays(3), 6),
                row(topic, TODAY, 2)));

        assertThat(body.labelledFrom()).isEqualTo(TODAY.minusDays(4));
        assertThat(body.topics().getFirst().series().subList(0, 26))
                .allSatisfy(day -> assertThat(day.count()).isZero());

        assertThat(fold(List.of(topic), List.of()).labelledFrom()).isNull();
    }

    @Test
    @DisplayName("attempt: running and failed runs show; a saved run or no run shows none")
    void attempt() {
        var running = run(TopicRun.Status.RUNNING);

        assertThat(TopicsCounts.attempt(running)).isEqualTo(
                ProjectTopics.Attempt.builder().status(ProjectTopics.Attempt.Status.RUNNING).build());
        assertThat(TopicsCounts.attempt(run(TopicRun.Status.FAILED))).isEqualTo(
                ProjectTopics.Attempt.builder().status(ProjectTopics.Attempt.Status.FAILED).build());
        assertThat(TopicsCounts.attempt(run(TopicRun.Status.SAVED))).isNull();
        assertThat(TopicsCounts.attempt(null)).isNull();
    }

    @Test
    @DisplayName("last_run_at: the state's last success, none without a state")
    void lastRunAt() {
        assertThat(TopicsCounts.lastRunAt(STATE)).isEqualTo(LAST_RUN);
        assertThat(TopicsCounts.lastRunAt(null)).isNull();
    }

    private static ProjectTopics fold(List<Topic> live, List<TopicDayCount> rows) {
        return TopicsCounts.ready(TODAY, WINDOW_DAYS, live, STATE, rows);
    }

    private static TopicRun run(TopicRun.Status status) {
        return TopicRun.builder()
                .runId(UUID.randomUUID())
                .workspaceId(WORKSPACE_ID)
                .projectId(PROJECT_ID)
                .mode(TopicRun.Mode.FIRST_BUILD)
                .status(status)
                .startedAt(LAST_RUN)
                .build();
    }

    private static TopicStats stats(ProjectTopics body, Topic topic) {
        return body.topics().stream().filter(stats -> stats.id().equals(topic.id())).findFirst().orElseThrow();
    }

    private static TopicDayCount row(Topic topic, LocalDate day, long traces) {
        return TopicDayCount.builder().topicId(topic.id()).day(day).traces(traces).build();
    }

    private static Tuple tupleOf(Topic topic, int rank) {
        return tuple(topic.id(), rank);
    }

    private static Topic topic(LocalDate born) {
        return topic(UUID.randomUUID(), born);
    }

    private static Topic topic(UUID id, LocalDate born) {
        return Topic.builder()
                .id(id)
                .workspaceId(WORKSPACE_ID)
                .projectId(PROJECT_ID)
                .name("Topic " + id)
                .definition("Definition of " + id)
                .born(born)
                .build();
    }
}
