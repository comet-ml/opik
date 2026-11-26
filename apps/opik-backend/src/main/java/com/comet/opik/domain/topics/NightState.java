package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.HasValue;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * A project's topic list during one nightly update: read at the start, changed by steps a to g, written at the end.
 * {@link #getTopics()} holds the topics live at the start in list order (born, then creation), then the ones born
 * tonight in birth order. Labels in a night are indices into this list, so a topic keeps its index all night: a topic
 * retired tonight stays in it, marked with its reason, and the save deletes its row (the reason goes to the log and
 * the retired-topics metric).
 */
class NightState {

    private final List<NightTopic> topics;
    private List<float[]> otherPrototypes;
    @Getter
    private final int rebuilds;
    @Getter
    private final LocalDate night;
    private final Supplier<UUID> ids;

    @Builder
    private NightState(@NonNull List<NightTopic> topics, @NonNull List<float[]> otherPrototypes, int rebuilds,
            @NonNull LocalDate night, @NonNull Supplier<UUID> ids) {
        this.topics = new ArrayList<>(topics);
        this.otherPrototypes = new ArrayList<>(otherPrototypes);
        this.rebuilds = rebuilds;
        this.night = night;
        this.ids = ids;
    }

    List<NightTopic> getTopics() {
        return Collections.unmodifiableList(topics);
    }

    List<float[]> getOtherPrototypes() {
        return Collections.unmodifiableList(otherPrototypes);
    }

    void setOtherPrototypes(@NonNull List<float[]> prototypes) {
        this.otherPrototypes = new ArrayList<>(prototypes);
    }

    /** The live topics' indices, in list order. */
    int[] live() {
        return IntStream.range(0, topics.size()).filter(t -> topics.get(t).isLive()).toArray();
    }

    int liveCount() {
        return (int) topics.stream().filter(NightTopic::isLive).count();
    }

    NightTopic topic(int index) {
        return topics.get(index);
    }

    /** A topic born tonight, appended to the list; returns its index. */
    int born(@NonNull String name, @NonNull String definition) {
        topics.add(NightTopic.builder().id(ids.get()).name(name).definition(definition).born(night)
                .prototypes(List.of()).build());
        return topics.size() - 1;
    }

    /** Retires a live topic tonight: it leaves the list's live part, so its prototypes no longer assign anything. */
    void retire(int index, @NonNull RetiredReason reason) {
        topics.get(index).retire(reason);
    }

    /**
     * Folds each live topic that covers the same traces as a larger one into it ({@link TopicMerger}): the larger keeps
     * its name and takes the other's prototypes, the other retires as {@code merged}. Returns whether any merged.
     */
    boolean mergeOverlapping(@NonNull float[] vectors, @NonNull int[] labels, int dimensions, double mergeCosine) {
        var merges = TopicMerger.merges(vectors, labels, topics.size(), live(), dimensions, mergeCosine);
        for (var merge : merges) {
            var kept = topics.get(merge.kept());
            var prototypes = new ArrayList<>(kept.getPrototypes());
            prototypes.addAll(topics.get(merge.absorbed()).getPrototypes());
            kept.setPrototypes(prototypes);
            topics.get(merge.absorbed()).retire(RetiredReason.MERGED);
        }
        return !merges.isEmpty();
    }

    /**
     * Step f, after the final assignment. A topic's share is its count over the window's traces; a topic born after
     * the discovery run is measured as any other. A live topic that holds no trace retires tonight. Otherwise its streak
     * grows on a night with a share below {@code retireShare} and goes back to 0 on any other; at {@code retireNights}
     * it retires. No cap rule is needed: a birth on a full list replaces a topic, so the live list never grows past the
     * maximum.
     *
     * @param labels per window trace, a topic index or {@link TopicAlgorithm#OTHER}
     */
    void retireLowShare(@NonNull int[] labels, double retireShare, int retireNights) {
        var counts = counts(labels);
        for (int t : live()) {
            var topic = topics.get(t);
            if (counts[t] == 0 || topic.recordNight((double) counts[t] / labels.length < retireShare) >= retireNights) {
                topic.retire(RetiredReason.LOW_SHARE);
            }
        }
    }

    /** Each live topic's size: its count in the labels after the retire reassignment. */
    void recordSizes(@NonNull int[] labels) {
        var counts = counts(labels);
        for (int t : live()) {
            topics.get(t).recordSize(counts[t]);
        }
    }

    private int[] counts(int[] labels) {
        var counts = new int[topics.size()];
        for (int label : labels) {
            if (label != TopicAlgorithm.OTHER) {
                counts[label]++;
            }
        }
        return counts;
    }

    /** Why a topic was retired: the log line's and the retired-topics metric's reason. */
    @Getter
    @RequiredArgsConstructor
    enum RetiredReason implements HasValue {
        /** No trace in the window, or a share under {@code retireShare} on {@code retireNights} nights in a row. */
        LOW_SHARE("low_share"),
        /** Replaced by a forced new topic on a full list. */
        REPLACED("replaced"),
        /** Folded into a live topic that covers the same traces ({@link TopicMerger}). */
        MERGED("merged");

        private final String value;
    }
}
