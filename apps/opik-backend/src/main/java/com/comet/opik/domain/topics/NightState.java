package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A project's topic list during one nightly update: read at the start, changed by steps a to l, written at the end.
 * {@link #topics()} holds the topics live at the start in list order (born, then creation), then the ones born tonight in
 * birth order; a topic retired tonight stays in it, marked. Labels in a night are indices into this list, so a topic
 * keeps its index all night. The live topics in this order are the stand's {@code tax.topics}.
 */
@Getter
public class NightState {

    private final List<Topic> topics;
    private List<float[]> otherPrototypes;
    private final int rebuilds;
    private final LocalDate firstBuild;
    private final LocalDate night;
    private final Supplier<UUID> ids;

    @Builder
    private NightState(@NonNull List<Topic> topics, @NonNull List<float[]> otherPrototypes, int rebuilds,
            @NonNull LocalDate firstBuild, @NonNull LocalDate night, @NonNull Supplier<UUID> ids) {
        this.topics = new ArrayList<>(topics);
        this.otherPrototypes = new ArrayList<>(otherPrototypes);
        this.rebuilds = rebuilds;
        this.firstBuild = firstBuild;
        this.night = night;
        this.ids = ids;
    }

    /** The live topics' indices, in list order. */
    public List<Integer> live() {
        var out = new ArrayList<Integer>();
        for (int t = 0; t < topics.size(); t++) {
            if (topics.get(t).isLive()) {
                out.add(t);
            }
        }
        return out;
    }

    public int liveCount() {
        return (int) topics.stream().filter(Topic::isLive).count();
    }

    public Topic topic(int index) {
        return topics.get(index);
    }

    /** A topic born tonight, appended to the list; returns its index. */
    public int born(@NonNull String name, @NonNull String definition) {
        topics.add(Topic.builder().id(ids.get()).name(name).definition(definition).born(night)
                .prototypes(new ArrayList<>()).build());
        return topics.size() - 1;
    }

    /** Retires a live topic tonight: it leaves the list's live part, so its prototypes no longer assign anything. */
    public void retire(int index, @NonNull RetiredReason reason) {
        var topic = topics.get(index);
        topic.setRetiredAt(night);
        topic.setRetiredReason(reason);
    }

    public void setOtherPrototypes(@NonNull List<float[]> prototypes) {
        this.otherPrototypes = new ArrayList<>(prototypes);
    }

    /** Why a topic was retired; stored in {@code topics.retired_reason}. */
    public enum RetiredReason {
        /** Under 0.5% of the window on 3 saved nights in a row. */
        LOW_SHARE,
        /** More than 10 live topics after a night: the smallest goes. */
        CAP,
        /** Dropped by a review night's merge into another topic. */
        MERGED,
        /** Retired by a review night's answer. */
        REVIEW,
        /** Replaced by a forced new topic on a full list. */
        REPLACED;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A topic of the night. {@code prototypes} are unit vectors in assignment order; {@code lowNights}, {@code lo7},
     * {@code hi7} and {@code inactiveSince} are the lifecycle streaks; {@code size} its count in the window after the
     * night.
     */
    @Getter
    @Setter
    public static class Topic {
        private final UUID id;
        private final String name;
        private String definition;
        private final LocalDate born;
        private List<float[]> prototypes;
        private int lowNights;
        private int lo7;
        private int hi7;
        private LocalDate inactiveSince;
        private LocalDate retiredAt;
        private RetiredReason retiredReason;
        private int size;

        @Builder(toBuilder = true)
        private Topic(@NonNull UUID id, @NonNull String name, @NonNull String definition, @NonNull LocalDate born,
                @NonNull List<float[]> prototypes, int lowNights, int lo7, int hi7, LocalDate inactiveSince,
                int size) {
            this.id = id;
            this.name = name;
            this.definition = definition;
            this.born = born;
            this.prototypes = new ArrayList<>(prototypes);
            this.lowNights = lowNights;
            this.lo7 = lo7;
            this.hi7 = hi7;
            this.inactiveSince = inactiveSince;
            this.size = size;
        }

        public boolean isLive() {
            return retiredAt == null;
        }

        public void setPrototypes(@NonNull List<float[]> prototypes) {
            this.prototypes = new ArrayList<>(prototypes);
        }
    }
}
