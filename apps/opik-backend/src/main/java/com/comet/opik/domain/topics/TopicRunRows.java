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

    /** Saved, but its project was deleted meanwhile: nothing more to write. */
    TopicRunRows discarded() {
        return toBuilder().traceTopics(List.of()).build();
    }

    /**
     * The rows of {@code built} over the window's {@code traces}, in the run's order. Fails with an
     * {@link IllegalArgumentException} when the list does not match the window or a prototype is not a unit vector.
     */
    static TopicRunRows of(@NonNull TopicRun run, @NonNull List<WindowTrace> traces, @NonNull FirstBuild built,
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
        var traceTopics = new ArrayList<TraceTopic>(traces.size());
        for (int i = 0; i < traces.size(); i++) {
            traceTopics.add(TraceTopic.builder()
                    .workspaceId(run.workspaceId())
                    .projectId(run.projectId())
                    .traceId(traces.get(i).traceId())
                    .traceCreatedAt(traces.get(i).traceCreatedAt())
                    .topicId(labels[i] == TopicAlgorithm.OTHER ? TraceTopic.OTHER : topics.get(labels[i]).id())
                    .source(TraceTopic.Source.RUN)
                    .runId(run.runId())
                    .build());
        }
        double otherShare = (double) other / labels.length;
        var stats = built.stats().deepCopy();
        stats.put("topics", topics.size());
        stats.put("other_share", otherShare);
        stats.put("prototypes", topics.stream().mapToInt(topic -> topic.vectors().size()).sum()
                + built.otherPrototypes().size());
        return new TopicRunRows(List.copyOf(topics), otherVectors, List.copyOf(traceTopics), otherShare, stats);
    }
}
