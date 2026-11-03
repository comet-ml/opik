package com.comet.opik.domain.topics;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.ProjectTopics.DayCount;
import com.comet.opik.api.ProjectTopics.OtherStats;
import com.comet.opik.api.ProjectTopics.TopicStats;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import com.google.common.base.Preconditions;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * The topics read API's {@code ready} body from a project's live topics, its state and the per topic and day counts of
 * {@link TraceTopicsDAO#countByTopicAndDay}. The window is the 30 UTC days that end with today, the window of
 * tomorrow's night.
 * <ul>
 * <li>A row of an id that is neither a live topic nor Other (a topic retired since the trace was labelled) counts as
 * Other and as {@code stale}.</li>
 * <li>{@code change} is today minus yesterday; {@code share} is over all the window's traces, Other included.</li>
 * <li>Rank: active topics by count, then inactive ones by count; ties by born, then id (the order of
 * {@link TopicDAO#findLive}).</li>
 * <li>New: born after the first build's date and at most {@value #NEW_DAYS} days ago, so a first build's topics are
 * never New, as in the night's newborn rule.</li>
 * </ul>
 */
@UtilityClass
public class TopicsCounts {

    static final int WINDOW_DAYS = 30;
    static final int NEW_DAYS = 7;

    private static final Comparator<Topic> RANK_TIES = Comparator.comparing(Topic::born)
            .thenComparing(topic -> topic.id().toString());

    /** The UTC date of the request. */
    public static LocalDate today(@NonNull Clock clock) {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** The window's first day; the last is {@code today}. */
    public static LocalDate windowFrom(@NonNull LocalDate today) {
        return today.minusDays(WINDOW_DAYS - 1);
    }

    public static ProjectTopics ready(@NonNull LocalDate today, @NonNull List<Topic> live,
            @Nullable TopicProjectState state, @NonNull List<TopicDayCount> rows) {
        var from = windowFrom(today);
        var days = new HashMap<UUID, long[]>();
        live.forEach(topic -> days.put(topic.id(), new long[WINDOW_DAYS]));
        var other = new long[WINDOW_DAYS];
        long stale = 0;
        for (var row : rows) {
            int index = (int) ChronoUnit.DAYS.between(from, row.day());
            Preconditions.checkArgument(index >= 0 && index < WINDOW_DAYS,
                    "Row day '%s' is outside the window '%s' to '%s'", row.day(), from, today);
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
        ranked.sort(Comparator.<Topic, Boolean>comparing(topic -> topic.inactiveSince() != null)
                .thenComparing(topic -> sum(days.get(topic.id())), Comparator.reverseOrder())
                .thenComparing(RANK_TIES));
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
                            .change(change(topicDays))
                            .rank(i + 1)
                            .series(series(from, topicDays))
                            .build();
                })
                .toList();

        return ProjectTopics.builder()
                .status(ProjectTopics.Status.READY)
                .today(today)
                .windowFrom(from)
                .windowUntil(today)
                .lastRunAt(state == null ? null : state.lastSuccessAt())
                .totalTraces(total)
                .labelledFrom(labelledFrom(from, other, days.values()))
                .topics(topics)
                .other(OtherStats.builder()
                        .id(TraceTopic.OTHER)
                        .count(sum(other))
                        .stale(stale)
                        .share(share(sum(other), total))
                        .change(change(other))
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
                .mapToObj(i -> new DayCount(from.plusDays(i), days[i]))
                .toList();
    }

    private static long change(long[] days) {
        return days[WINDOW_DAYS - 1] - days[WINDOW_DAYS - 2];
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
