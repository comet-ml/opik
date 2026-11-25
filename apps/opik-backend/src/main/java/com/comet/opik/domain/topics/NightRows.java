package com.comet.opik.domain.topics;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a nightly update saves, with the counts its metrics carry after the commit: the MySQL save, the retired
 * topics that were stored, one {@code trace_topics} row per window trace (none when no topic stayed live), and the
 * night's stats. No I/O, no clock.
 */
@Builder(toBuilder = true)
record NightRows(@NonNull NightList list, @NonNull NightSave save, @NonNull List<NightTopic> retired,
        @NonNull List<TraceTopic> traceTopics, int born, int live, double otherShare, @NonNull TopicChurn.Churn churn,
        @NonNull ObjectNode stats) {

    /** Saved, but its project was deleted meanwhile: no {@code trace_topics} to write. */
    NightRows discarded() {
        return toBuilder().traceTopics(List.of()).build();
    }

    /**
     * The rows of {@code list}'s state after the night's {@code result} over the window's {@code traces}, in the run's
     * order; {@code topicsBefore} is the live count the night started with. A topic is stored when its id is in the
     * list read; a live topic is updated if stored and inserted if not, and a retired one is deleted if stored. Fails
     * with an {@link IllegalArgumentException} when the result does not label the window.
     */
    static NightRows of(@NonNull TopicRun run, @NonNull List<WindowTrace> traces, @NonNull NightList list,
            int topicsBefore, @NonNull TopicUpdate result) {
        var labels = result.labels();
        if (labels.length != traces.size()) {
            throw new IllegalArgumentException("the update labels '%s' traces, the window has '%s'"
                    .formatted(labels.length, traces.size()));
        }
        var state = list.state();
        boolean keep = state.liveCount() > 0;
        int other = 0;
        var topicIds = new UUID[labels.length];
        for (int i = 0; i < labels.length; i++) {
            other += labels[i] == TopicAlgorithm.OTHER ? 1 : 0;
            topicIds[i] = labels[i] == TopicAlgorithm.OTHER ? TraceTopic.OTHER : state.topic(labels[i]).getId();
        }
        var traceTopics = keep
                ? TopicRunRows.traceTopics(run.workspaceId(), run.projectId(), run.runId(), traces, topicIds)
                : List.<TraceTopic>of();
        var inserted = new ArrayList<Topic>();
        var updated = new ArrayList<Topic>();
        var retired = new ArrayList<NightTopic>();
        var retiredByReason = JsonUtils.createObjectNode();
        for (var topic : state.getTopics()) {
            boolean stored = list.topicIds().contains(topic.getId());
            if (!topic.isLive()) {
                var reason = topic.getRetiredReason().getValue();
                retiredByReason.put(reason, retiredByReason.path(reason).asInt() + 1);
                if (stored) {
                    retired.add(topic);
                }
                continue;
            }
            (stored ? updated : inserted).add(Topic.builder()
                    .id(topic.getId())
                    .workspaceId(run.workspaceId())
                    .projectId(run.projectId())
                    .name(topic.getName())
                    .definition(topic.getDefinition())
                    .born(topic.getBorn())
                    .lowNights(topic.getLowNights())
                    .size(topic.getSize())
                    .vectors(new PrototypeVectors(topic.getPrototypes()))
                    .build());
        }
        int born = (int) state.getTopics().stream().filter(topic -> !list.topicIds().contains(topic.getId())).count();
        double otherShare = (double) other / labels.length;
        var churn = TopicChurn.of(result.before(), labels);
        var stats = result.stats().deepCopy();
        stats.put("saved_runs_before", state.getRebuilds());
        stats.put("topics_before", topicsBefore);
        stats.set("retired", retiredByReason);
        stats.put("born", born);
        stats.put("topics", state.liveCount());
        stats.put("other_share", otherShare);
        stats.put("other_prototypes", state.getOtherPrototypes().size());
        stats.put("churn", churn.churn());
        stats.put("churn_any", churn.churnAny());
        var save = NightSave.builder()
                .keep(keep)
                .inserted(List.copyOf(inserted))
                .updated(List.copyOf(updated))
                .retired(retired.stream().map(NightTopic::getId).toList())
                .otherVectors(!keep || state.getOtherPrototypes().isEmpty()
                        ? null
                        : new PrototypeVectors(state.getOtherPrototypes()))
                .build();
        return NightRows.builder()
                .list(list)
                .save(save)
                .retired(List.copyOf(retired))
                .traceTopics(traceTopics)
                .born(born)
                .live(state.liveCount())
                .otherShare(otherShare)
                .churn(churn)
                .stats(stats)
                .build();
    }
}
