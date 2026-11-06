package com.comet.opik.domain.topics;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * The one rank rule of a project's live topics, for a saved run's counts and for the topics API: by traces, most first,
 * then the earlier born, then the id as text (the order of {@code topics.id} in MySQL).
 */
@UtilityClass
public class TopicRanking {

    /** The rank order when {@code traces} gives each topic's count. */
    public static Comparator<Topic> order(@NonNull ToLongFunction<Topic> traces) {
        return Comparator.comparingLong(traces).reversed()
                .thenComparing(Topic::born)
                .thenComparing(topic -> topic.id().toString());
    }

    /**
     * A saved run's {@code topic_run_counts} rows: one per live topic, with its {@code size} as traces and its rank by
     * {@link #order} on the sizes, then the Other row with {@code other} traces and no rank.
     */
    public static List<TopicRunCount> runCounts(@NonNull UUID runId, @NonNull String workspaceId,
            @NonNull UUID projectId, @NonNull List<Topic> live, long other, @NonNull Instant savedAt) {
        var ranked = live.stream().sorted(order(Topic::size)).toList();
        var rows = new ArrayList<TopicRunCount>(ranked.size() + 1);
        for (int i = 0; i < ranked.size(); i++) {
            rows.add(TopicRunCount.builder()
                    .runId(runId)
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .topicId(ranked.get(i).id())
                    .traces(ranked.get(i).size())
                    .rank(i + 1)
                    .savedAt(savedAt)
                    .build());
        }
        rows.add(TopicRunCount.builder()
                .runId(runId)
                .workspaceId(workspaceId)
                .projectId(projectId)
                .topicId(TraceTopic.OTHER)
                .traces(other)
                .savedAt(savedAt)
                .build());
        return rows;
    }
}
