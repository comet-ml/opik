package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What a run saves: its topics with their sizes and prototype vectors, the known-Other vectors (null when none), one
 * {@code trace_topics} row per window trace, and the list's stats. Pure: no I/O, no clock.
 */
@Builder(toBuilder = true)
record TopicRunRows(@NonNull List<Topic> topics, PrototypeVectors otherVectors, @NonNull List<TraceTopic> traceTopics,
        double otherShare, @NonNull ObjectNode stats) {

    /** The author task version the run's clustering recorded in its stats; null when it recorded none. */
    Integer authorVersion() {
        var version = stats.get("author_task_version");
        return version != null && version.canConvertToInt() ? version.intValue() : null;
    }

    /** Saved, but its project was deleted meanwhile: nothing more to write. */
    TopicRunRows discarded() {
        return toBuilder().traceTopics(List.of()).build();
    }

    /**
     * The rows of {@code built} over the window's {@code traces}, in the run's order. Fails with an
     * {@link IllegalArgumentException} when the list does not match the window or a prototype is not a unit vector.
     */
    static TopicRunRows of(@NonNull TopicRun run, @NonNull List<WindowTrace> traces, @NonNull DiscoveredTopics built,
            @NonNull Supplier<UUID> ids) {
        var labels = built.labels();
        if (labels.length != traces.size()) {
            throw new IllegalArgumentException("the list labels '%s' traces, the window has '%s'"
                    .formatted(labels.length, traces.size()));
        }
        var sizes = new int[built.topics().size()];
        int other = 0;
        for (int label : labels) {
            if (label == TopicAlgorithm.OTHER) {
                other++;
            } else {
                sizes[label]++;
            }
        }
        var topics = new ArrayList<Topic>(sizes.length);
        for (int t = 0; t < sizes.length; t++) {
            var topic = built.topics().get(t);
            topics.add(Topic.builder()
                    .id(ids.get())
                    .workspaceId(run.workspaceId())
                    .projectId(run.projectId())
                    .name(topic.name())
                    .definition(topic.definition())
                    .born(run.born())
                    .size(sizes[t])
                    .vectors(new PrototypeVectors(topic.prototypes()))
                    .build());
        }
        var otherVectors = built.otherPrototypes().isEmpty() ? null : new PrototypeVectors(built.otherPrototypes());
        var topicIds = new UUID[labels.length];
        for (int i = 0; i < labels.length; i++) {
            topicIds[i] = labels[i] == TopicAlgorithm.OTHER ? TraceTopic.OTHER : topics.get(labels[i]).id();
        }
        var traceTopics = traceTopics(run.workspaceId(), run.projectId(), run.runId(), traces, topicIds);
        double otherShare = (double) other / labels.length;
        var stats = built.stats().deepCopy();
        stats.put("topics", topics.size());
        stats.put("other_share", otherShare);
        stats.put("prototypes", topics.stream().mapToInt(topic -> topic.vectors().size()).sum()
                + built.otherPrototypes().size());
        return new TopicRunRows(List.copyOf(topics), otherVectors, traceTopics, otherShare, stats);
    }

    /**
     * The run's {@code trace_topics} rows, as its own insert and the labelling pass write them: trace i gets
     * {@code topicIds[i]}, and a null id gets no row.
     */
    static List<TraceTopic> traceTopics(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID runId,
            @NonNull List<WindowTrace> traces, @NonNull UUID[] topicIds) {
        if (topicIds.length != traces.size()) {
            throw new IllegalArgumentException("'%s' topic ids for '%s' traces".formatted(topicIds.length,
                    traces.size()));
        }
        var rows = new ArrayList<TraceTopic>(traces.size());
        for (int i = 0; i < traces.size(); i++) {
            if (topicIds[i] != null) {
                rows.add(TraceTopic.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .traceId(traces.get(i).traceId())
                        .traceCreatedAt(traces.get(i).traceCreatedAt())
                        .topicId(topicIds[i])
                        .source(TraceTopic.Source.RUN)
                        .runId(runId)
                        .build());
            }
        }
        return List.copyOf(rows);
    }
}
