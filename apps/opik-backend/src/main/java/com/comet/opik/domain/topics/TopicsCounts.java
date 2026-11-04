package com.comet.opik.domain.topics;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.ProjectTopics.DayCount;
import com.comet.opik.api.ProjectTopics.OtherRunChange;
import com.comet.opik.api.ProjectTopics.OtherStats;
import com.comet.opik.api.ProjectTopics.RunChange;
import com.comet.opik.api.ProjectTopics.TopicStats;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * The topics read API's {@code ready} body from a project's live topics, its state, the per topic and day counts of
 * {@link TraceTopicsDAO#countByTopicAndDay} and the counts of its last two saved runs
 * ({@link TopicRunDAO#findLastTwoRunCounts}). The window is the 30 UTC days that end with today, the window of
 * tomorrow's night.
 * <ul>
 * <li>Only the window's days count: a row of another day is skipped.</li>
 * <li>A row of an id that is neither a live topic nor Other (a topic retired since the trace was labelled) counts as
 * Other and as {@code stale}.</li>
 * <li>{@code share} is over all the window's traces, Other included.</li>
 * <li>Rank: {@link TopicRanking} on the window counts: active topics by count, then inactive ones by count; ties by
 * born, then id.</li>
 * <li>{@code run_change}: the last saved run's counts against the run before's. A topic without a row in both runs
 * has none, as has Other with fewer than two runs.</li>
 * <li>New: born after the first build's date and at most {@value #NEW_DAYS} days ago, so a first build's topics are
 * never New, as in the night's newborn rule.</li>
 * </ul>
 */
@UtilityClass
public class TopicsCounts {

    static final int WINDOW_DAYS = 30;
    static final int NEW_DAYS = 7;

    public static LocalDate windowFrom(@NonNull LocalDate today) {
        return today.minusDays(WINDOW_DAYS - 1);
    }

    /** The fields of every status: the window's dates, and no topic. */
    public static ProjectTopics.ProjectTopicsBuilder window(@NonNull LocalDate today) {
        return ProjectTopics.builder()
                .today(today)
                .windowFrom(windowFrom(today))
                .windowUntil(today)
                .topics(List.of());
    }

    /** The last saved night, null before the first one. */
    public static Instant lastRunAt(@Nullable TopicProjectState state) {
        return state == null ? null : state.lastSuccessAt();
    }

    public static ProjectTopics ready(@NonNull LocalDate today, @NonNull List<Topic> live,
            @Nullable TopicProjectState state, @NonNull List<TopicDayCount> rows,
            @NonNull List<TopicRunCount> runCounts) {
        var from = windowFrom(today);
        var days = new HashMap<UUID, long[]>();
        live.forEach(topic -> days.put(topic.id(), new long[WINDOW_DAYS]));
        var other = new long[WINDOW_DAYS];
        long stale = 0;
        for (var row : rows) {
            int index = (int) ChronoUnit.DAYS.between(from, row.day());
            if (index < 0 || index >= WINDOW_DAYS) {
                continue;
            }
            var topicDays = days.get(row.topicId());
            if (topicDays != null) {
                topicDays[index] += row.traces();
            } else {
                other[index] += row.traces();
                if (!TraceTopic.OTHER.equals(row.topicId())) {
                    stale += row.traces();
                }
            }
        }

        long total = sum(other) + days.values().stream().mapToLong(TopicsCounts::sum).sum();
        var firstBuild = state == null || state.firstBuiltAt() == null
                ? null
                : LocalDate.ofInstant(state.firstBuiltAt(), ZoneOffset.UTC);
        var ranked = new ArrayList<>(live);
        ranked.sort(TopicRanking.order(topic -> sum(days.get(topic.id()))));
        var runs = RunPair.of(runCounts);
        var topics = IntStream.range(0, ranked.size())
                .mapToObj(i -> {
                    var topic = ranked.get(i);
                    var topicDays = days.get(topic.id());
                    return TopicStats.builder()
                            .id(topic.id())
                            .name(topic.name())
                            .definition(topic.definition())
                            .born(topic.born())
                            .isNew(firstBuild != null && topic.born().isAfter(firstBuild)
                                    && !topic.born().isBefore(today.minusDays(NEW_DAYS - 1)))
                            .inactiveSince(topic.inactiveSince())
                            .count(sum(topicDays))
                            .share(share(sum(topicDays), total))
                            .rank(i + 1)
                            .runChange(runs.topic(topic.id()))
                            .series(series(from, topicDays))
                            .build();
                })
                .toList();

        return window(today)
                .status(ProjectTopics.Status.READY)
                .lastRunAt(lastRunAt(state))
                .totalTraces(total)
                .labelledFrom(labelledFrom(from, other, days.values()))
                .topics(topics)
                .other(OtherStats.builder()
                        .id(TraceTopic.OTHER)
                        .count(sum(other))
                        .stale(stale)
                        .share(share(sum(other), total))
                        .runChange(runs.other())
                        .series(series(from, other))
                        .build())
                .build();
    }

    private static LocalDate labelledFrom(LocalDate from, long[] other, Iterable<long[]> topics) {
        for (int i = 0; i < WINDOW_DAYS; i++) {
            long day = other[i];
            for (var topicDays : topics) {
                day += topicDays[i];
            }
            if (day > 0) {
                return from.plusDays(i);
            }
        }
        return null;
    }

    private static List<DayCount> series(LocalDate from, long[] days) {
        return IntStream.range(0, WINDOW_DAYS)
                .mapToObj(i -> DayCount.builder().day(from.plusDays(i)).count(days[i]).build())
                .toList();
    }

    /**
     * The rows of the project's last two saved runs by topic id: {@code last} has the latest {@code saved_at} (then
     * the greater run id), {@code previous} the one before. Both are empty with fewer than two runs.
     */
    @Builder(toBuilder = true)
    private record RunPair(Map<UUID, TopicRunCount> last, long lastTotal, Map<UUID, TopicRunCount> previous,
            long previousTotal) {

        static RunPair of(List<TopicRunCount> rows) {
            var runs = new LinkedHashMap<UUID, Map<UUID, TopicRunCount>>();
            rows.stream()
                    .sorted(Comparator.comparing(TopicRunCount::savedAt)
                            .thenComparing(row -> row.runId().toString())
                            .reversed())
                    .forEach(row -> runs.computeIfAbsent(row.runId(), id -> new HashMap<>()).put(row.topicId(), row));
            if (runs.size() < 2) {
                return RunPair.builder().last(Map.of()).previous(Map.of()).build();
            }
            var iterator = runs.values().iterator();
            var last = iterator.next();
            var previous = iterator.next();
            return RunPair.builder()
                    .last(last)
                    .lastTotal(total(last))
                    .previous(previous)
                    .previousTotal(total(previous))
                    .build();
        }

        RunChange topic(UUID topicId) {
            var now = last.get(topicId);
            var before = previous.get(topicId);
            if (now == null || before == null || now.rank() == null || before.rank() == null) {
                return null;
            }
            return RunChange.builder()
                    .count(now.traces() - before.traces())
                    .countRatio(ratio(now, before))
                    .share(share(now.traces(), lastTotal) - share(before.traces(), previousTotal))
                    .rank(before.rank() - now.rank())
                    .build();
        }

        OtherRunChange other() {
            var now = last.get(TraceTopic.OTHER);
            var before = previous.get(TraceTopic.OTHER);
            if (now == null || before == null) {
                return null;
            }
            return OtherRunChange.builder()
                    .count(now.traces() - before.traces())
                    .countRatio(ratio(now, before))
                    .share(share(now.traces(), lastTotal) - share(before.traces(), previousTotal))
                    .build();
        }

        private static Double ratio(TopicRunCount now, TopicRunCount before) {
            return before.traces() == 0 ? null : (double) (now.traces() - before.traces()) / before.traces();
        }

        private static long total(Map<UUID, TopicRunCount> run) {
            return run.values().stream().mapToLong(TopicRunCount::traces).sum();
        }
    }

    private static double share(long count, long total) {
        return total == 0 ? 0 : (double) count / total;
    }

    private static long sum(long[] days) {
        long sum = 0;
        for (long day : days) {
            sum += day;
        }
        return sum;
    }
}
