package com.comet.opik.domain.topics;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.ProjectTopics.DayCount;
import com.comet.opik.api.ProjectTopics.OtherRunChange;
import com.comet.opik.api.ProjectTopics.RunChange;
import com.comet.opik.api.ProjectTopics.TopicStats;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
            .build();

    @Test
    @DisplayName("the fields of every status: today, the 29 days before it, and no topic")
    void window() {
        var body = TopicsCounts.window(TODAY).status(ProjectTopics.Status.DISABLED).build();

        assertThat(body.today()).isEqualTo(TODAY);
        assertThat(body.windowFrom()).isEqualTo(TODAY.minusDays(29));
        assertThat(body.windowUntil()).isEqualTo(TODAY);
        assertThat(body.topics()).isEmpty();
    }

    @Test
    @DisplayName("ready body: the window, the last run and the total; a series of 30 days, oldest first, today last, "
            + "missing days as 0")
    void series() {
        var topic = topic(FIRST_BUILD, null);
        var body = fold(List.of(topic), List.of(
                row(topic, TODAY.minusDays(29), 2),
                row(topic, TODAY.minusDays(10), 5),
                row(topic, TODAY, 1)));

        assertThat(body.status()).isEqualTo(ProjectTopics.Status.READY);
        assertThat(body.today()).isEqualTo(TODAY);
        assertThat(body.windowFrom()).isEqualTo(TODAY.minusDays(29));
        assertThat(body.windowUntil()).isEqualTo(TODAY);
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
    }

    @Test
    @DisplayName("rank: active topics by count, then inactive ones by count; ties by born, then id")
    void rank() {
        var big = topic(FIRST_BUILD, null);
        var inactiveBigger = topic(FIRST_BUILD, TODAY.minusDays(3));
        var tieOlder = topic(FIRST_BUILD, null);
        var tieYounger = topic(FIRST_BUILD.plusDays(2), null);
        var lowId = topic(new UUID(1L, 1L), FIRST_BUILD.plusDays(5), null);
        var highId = topic(new UUID(9L, 9L), FIRST_BUILD.plusDays(5), null);
        var body = fold(List.of(highId, tieYounger, inactiveBigger, lowId, tieOlder, big), List.of(
                row(big, TODAY, 50),
                row(inactiveBigger, TODAY, 90),
                row(tieOlder, TODAY, 10),
                row(tieYounger, TODAY, 10),
                row(lowId, TODAY, 4),
                row(highId, TODAY, 4)));

        assertThat(body.topics()).extracting(TopicStats::id, TopicStats::rank).containsExactly(
                tupleOf(big, 1), tupleOf(tieOlder, 2), tupleOf(tieYounger, 3), tupleOf(lowId, 4),
                tupleOf(highId, 5), tupleOf(inactiveBigger, 6));
        assertThat(stats(body, inactiveBigger).inactiveSince()).isEqualTo(TODAY.minusDays(3));
    }

    @Test
    @DisplayName("share: count over total traces; topics and Other sum to 1; 0 with no rows")
    void share() {
        var first = topic(FIRST_BUILD, null);
        var second = topic(FIRST_BUILD, null);
        var body = fold(List.of(first, second), List.of(
                row(first, TODAY, 3),
                row(second, TODAY.minusDays(4), 2),
                TopicDayCount.builder().topicId(TraceTopic.OTHER).day(TODAY).traces(2).build()));

        assertThat(stats(body, first).share()).isCloseTo(3.0 / 7, within(1e-12));
        var sum = body.topics().stream().mapToDouble(TopicStats::share).sum() + body.other().share();
        assertThat(sum).isCloseTo(1.0, within(1e-12));

        var empty = fold(List.of(first), List.of());
        assertThat(empty.totalTraces()).isZero();
        assertThat(empty.topics().getFirst().share()).isZero();
        assertThat(empty.other().share()).isZero();
    }

    @Test
    @DisplayName("is_new: never a first build's topic; a later topic born today - 6 is New, born today - 7 is not")
    void isNew() {
        var firstBuild = topic(FIRST_BUILD, null);
        var sixDays = topic(TODAY.minusDays(6), null);
        var sevenDays = topic(TODAY.minusDays(7), null);
        var body = fold(List.of(firstBuild, sixDays, sevenDays), List.of());

        assertThat(stats(body, firstBuild).isNew()).isFalse();
        assertThat(stats(body, sixDays).isNew()).isTrue();
        assertThat(stats(body, sevenDays).isNew()).isFalse();

        var freshBuild = STATE.toBuilder().firstBuiltAt(TODAY.minusDays(2).atTime(2, 0).toInstant(ZoneOffset.UTC))
                .build();
        var builtTwoDaysAgo = topic(TODAY.minusDays(2), null);
        assertThat(TopicsCounts.ready(TODAY, List.of(builtTwoDaysAgo), freshBuild, List.of(), List.of()).topics()
                .getFirst()
                .isNew()).isFalse();
    }

    @Test
    @DisplayName("Other: rows of ids that are not live count as Other and as stale; Other's own rows are not stale")
    void staleIdsCountAsOther() {
        var live = topic(FIRST_BUILD, null);
        var retiredId = UUID.randomUUID();
        var body = fold(List.of(live), List.of(
                row(live, TODAY, 5),
                TopicDayCount.builder().topicId(retiredId).day(TODAY.minusDays(1)).traces(3).build(),
                TopicDayCount.builder().topicId(retiredId).day(TODAY).traces(1).build(),
                TopicDayCount.builder().topicId(TraceTopic.OTHER).day(TODAY).traces(2).build()));

        assertThat(body.topics()).extracting(TopicStats::id).containsExactly(live.id());
        var other = body.other();
        assertThat(other.id()).isEqualTo(TraceTopic.OTHER);
        assertThat(other.count()).isEqualTo(6);
        assertThat(other.stale()).isEqualTo(4);
        assertThat(other.series().getLast()).isEqualTo(DayCount.builder().day(TODAY).count(3).build());
        assertThat(other.share()).isCloseTo(6.0 / 11, within(1e-12));
        assertThat(body.totalTraces()).isEqualTo(11L);
    }

    @Test
    @DisplayName("a live topic with no rows is listed with 30 zero days")
    void topicWithoutRows() {
        var quiet = topic(FIRST_BUILD, null);
        var busy = topic(FIRST_BUILD, null);
        var body = fold(List.of(quiet, busy), List.of(row(busy, TODAY, 1)));

        var stats = stats(body, quiet);
        assertThat(stats.count()).isZero();
        assertThat(stats.series()).hasSize(30).allSatisfy(day -> assertThat(day.count()).isZero());
    }

    @Test
    @DisplayName("labelled_from: the first day with any row, Other and stale included; days before it are 0 (a "
            + "first build that labelled only the newest days); omitted with no rows")
    void labelledFrom() {
        var topic = topic(FIRST_BUILD, null);
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
    @DisplayName("a project with no saved run yet: no last run, no topic is New")
    void noState() {
        var topic = topic(TODAY, null);
        var body = TopicsCounts.ready(TODAY, List.of(topic), null, List.of(row(topic, TODAY, 1)), List.of());

        assertThat(body.lastRunAt()).isNull();
        assertThat(body.topics().getFirst().isNew()).isFalse();
    }

    @Test
    @DisplayName("a row outside the window is skipped")
    void rowOutsideWindow() {
        var topic = topic(FIRST_BUILD, null);

        var body = fold(List.of(topic), List.of(row(topic, TODAY.plusDays(1), 1), row(topic, TODAY.minusDays(30), 1),
                row(topic, TODAY, 2)));

        assertThat(body.totalTraces()).isEqualTo(2L);
        assertThat(stats(body, topic).count()).isEqualTo(2);
        assertThat(stats(body, topic).series().getLast()).isEqualTo(DayCount.builder().day(TODAY).count(2).build());
    }

    @Test
    @DisplayName("run_change: none with no saved run's counts, and none with one run's")
    void runChangeNeedsTwoRuns() {
        var topic = topic(FIRST_BUILD, null);
        var rows = List.of(row(topic, TODAY, 3));
        var runId = UUID.randomUUID();

        var none = fold(List.of(topic), rows, List.of());
        var one = fold(List.of(topic), rows, List.of(
                runCount(runId, LAST_RUN, topic.id(), 3, 1),
                runCount(runId, LAST_RUN, TraceTopic.OTHER, 1, null)));

        assertThat(none.topics().getFirst().runChange()).isNull();
        assertThat(none.other().runChange()).isNull();
        assertThat(one.topics().getFirst().runChange()).isNull();
        assertThat(one.other().runChange()).isNull();
    }

    @Test
    @DisplayName("run_change: the run with the later saved_at against the one before; count, ratio, share in share "
            + "units, rank up and down, Other without a rank")
    void runChange() {
        var grew = topic(FIRST_BUILD, null);
        var shrank = topic(FIRST_BUILD, null);
        var previous = UUID.randomUUID();
        var last = UUID.randomUUID();
        var before = LAST_RUN.minus(Duration.ofDays(1));
        // In no order: the fold finds the last run by saved_at.
        var body = fold(List.of(grew, shrank), List.of(row(grew, TODAY, 1)), List.of(
                runCount(last, LAST_RUN, grew.id(), 60, 1),
                runCount(previous, before, shrank.id(), 40, 1),
                runCount(last, LAST_RUN, TraceTopic.OTHER, 10, null),
                runCount(previous, before, grew.id(), 20, 2),
                runCount(last, LAST_RUN, shrank.id(), 30, 2),
                runCount(previous, before, TraceTopic.OTHER, 40, null)));

        assertThat(stats(body, grew).runChange()).usingRecursiveComparison().isEqualTo(RunChange.builder()
                .count(40).countRatio(2.0).share(60.0 / 100 - 20.0 / 100).rank(1).build());
        assertThat(stats(body, shrank).runChange()).usingRecursiveComparison().isEqualTo(RunChange.builder()
                .count(-10).countRatio(-0.25).share(30.0 / 100 - 40.0 / 100).rank(-1).build());
        assertThat(body.other().runChange()).usingRecursiveComparison().isEqualTo(OtherRunChange.builder()
                .count(-30).countRatio(-0.75).share(10.0 / 100 - 40.0 / 100).build());
    }

    @Test
    @DisplayName("run_change: none for a topic new in the last run; no count ratio when the run before had 0 traces")
    void runChangeNewTopicAndZero() {
        var newborn = topic(TODAY.minusDays(1), null);
        var quiet = topic(FIRST_BUILD, null);
        var previous = UUID.randomUUID();
        var last = UUID.randomUUID();
        var before = LAST_RUN.minus(Duration.ofDays(1));
        var body = fold(List.of(newborn, quiet), List.of(), List.of(
                runCount(previous, before, quiet.id(), 0, 1),
                runCount(previous, before, TraceTopic.OTHER, 10, null),
                runCount(last, LAST_RUN, newborn.id(), 6, 1),
                runCount(last, LAST_RUN, quiet.id(), 4, 2),
                runCount(last, LAST_RUN, TraceTopic.OTHER, 0, null)));

        assertThat(stats(body, newborn).runChange()).isNull();
        assertThat(stats(body, quiet).runChange()).usingRecursiveComparison().isEqualTo(RunChange.builder()
                .count(4).countRatio(null).share(4.0 / 10 - 0.0 / 10).rank(-1).build());
        assertThat(body.other().runChange()).usingRecursiveComparison().isEqualTo(OtherRunChange.builder()
                .count(-10).countRatio(-1.0).share(0.0 / 10 - 10.0 / 10).build());
    }

    private static ProjectTopics fold(List<Topic> live, List<TopicDayCount> rows) {
        return fold(live, rows, List.of());
    }

    private static ProjectTopics fold(List<Topic> live, List<TopicDayCount> rows, List<TopicRunCount> runCounts) {
        return TopicsCounts.ready(TODAY, live, STATE, rows, runCounts);
    }

    private static TopicRunCount runCount(UUID runId, Instant savedAt, UUID topicId, long traces, Integer rank) {
        return TopicRunCount.builder()
                .runId(runId)
                .workspaceId(WORKSPACE_ID)
                .projectId(PROJECT_ID)
                .topicId(topicId)
                .traces(traces)
                .rank(rank)
                .savedAt(savedAt)
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

    private static Topic topic(LocalDate born, LocalDate inactiveSince) {
        return topic(UUID.randomUUID(), born, inactiveSince);
    }

    private static Topic topic(UUID id, LocalDate born, LocalDate inactiveSince) {
        return Topic.builder()
                .id(id)
                .workspaceId(WORKSPACE_ID)
                .projectId(PROJECT_ID)
                .name("Topic " + id)
                .definition("Definition of " + id)
                .born(born)
                .inactiveSince(inactiveSince)
                .build();
    }
}
