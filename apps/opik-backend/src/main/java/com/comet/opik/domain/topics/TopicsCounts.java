package com.comet.opik.domain.topics;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.ProjectTopics.DayCount;
import com.comet.opik.api.ProjectTopics.OtherStats;
import com.comet.opik.api.ProjectTopics.TopicStats;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.ToLongFunction;
import java.util.stream.IntStream;

/**
 * The topics read API's {@code ready} body from a project's live topics, its state and the per topic and day counts of
 * {@link TraceTopicsDAO#countByTopicAndDay}. The window is the {@code windowDays} UTC days that end with today
 * ({@code topics.build.windowDays}), the window of the next build.
 * <ul>
 * <li>A row of an id that is neither a live topic nor Other (a topic retired since the trace was labelled) counts as
 * Other.</li>
 * <li>{@code share} is over all the window's traces, Other included.</li>
 * <li>Rank: by count, most first; ties by born, then the id as text.</li>
 * <li>New: born after the first build's date and at most {@value #NEW_DAYS} days ago, so a first build's topics are
 * never New.</li>
 * </ul>
 */
@UtilityClass
public class TopicsCounts {

    static final int NEW_DAYS = 7;

    public static LocalDate windowFrom(@NonNull LocalDate today, int windowDays) {
        return today.minusDays(windowDays - 1L);
    }

    /** The fields of every status: the window's dates, and no topic. */
    public static ProjectTopics.ProjectTopicsBuilder window(@NonNull LocalDate today, int windowDays) {
        return ProjectTopics.builder()
                .today(today)
                .windowFrom(windowFrom(today, windowDays))
                .topics(List.of());
    }

    /** The last saved run's end, null before the first one. */
    public static Instant lastRunAt(@Nullable TopicProjectState state) {
        return state == null ? null : state.lastSuccessAt();
    }

    /** The collecting body's attempt from the project's newest run: null when that run saved, or with no run. */
    public static ProjectTopics.Attempt attempt(@Nullable TopicRun newest) {
        if (newest == null) {
            return null;
        }
        var status = switch (newest.status()) {
            case RUNNING -> ProjectTopics.Attempt.Status.RUNNING;
            case FAILED -> ProjectTopics.Attempt.Status.FAILED;
            case SAVED -> null;
        };
        return status == null ? null : ProjectTopics.Attempt.builder().status(status).build();
    }

    /** {@code rows} come from a read of the window, so every row's day is in it. */
    public static ProjectTopics ready(@NonNull LocalDate today, int windowDays, @NonNull List<Topic> live,
            @NonNull TopicProjectState state, @NonNull List<TopicDayCount> rows) {
        var from = windowFrom(today, windowDays);
        var days = new HashMap<UUID, long[]>();
        live.forEach(topic -> days.put(topic.id(), new long[windowDays]));
        var other = new long[windowDays];
        for (var row : rows) {
            int index = (int) ChronoUnit.DAYS.between(from, row.day());
            var topicDays = days.get(row.topicId());
            if (topicDays != null) {
                topicDays[index] += row.traces();
            } else {
                other[index] += row.traces();
            }
        }

        long total = sum(other) + days.values().stream().mapToLong(TopicsCounts::sum).sum();
        var firstBuild = state.firstBuiltAt() == null
                ? null
                : LocalDate.ofInstant(state.firstBuiltAt(), ZoneOffset.UTC);
        var ranked = new ArrayList<>(live);
        ranked.sort(rankOrder(topic -> sum(days.get(topic.id()))));
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
                            .count(sum(topicDays))
                            .share(share(sum(topicDays), total))
                            .rank(i + 1)
                            .series(series(from, topicDays))
                            .build();
                })
                .toList();

        return window(today, windowDays)
                .status(ProjectTopics.Status.READY)
                .lastRunAt(lastRunAt(state))
                .totalTraces(total)
                .labelledFrom(labelledFrom(from, other, days.values()))
                .topics(topics)
                .other(OtherStats.builder().series(series(from, other)).build())
                .build();
    }

    /** By count, most first; then the earlier born; then the id as text (the order of {@code topics.id} in MySQL). */
    private static Comparator<Topic> rankOrder(ToLongFunction<Topic> count) {
        return Comparator.comparingLong(count).reversed()
                .thenComparing(Topic::born)
                .thenComparing(topic -> topic.id().toString());
    }

    private static LocalDate labelledFrom(LocalDate from, long[] other, Iterable<long[]> topics) {
        for (int i = 0; i < other.length; i++) {
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
        return IntStream.range(0, days.length)
                .mapToObj(i -> DayCount.builder().day(from.plusDays(i)).count(days[i]).build())
                .toList();
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
