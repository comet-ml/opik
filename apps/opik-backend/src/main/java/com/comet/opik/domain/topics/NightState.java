package com.comet.opik.domain.topics;

import lombok.AccessLevel;
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
 * A project's topic list during one nightly update: read at the start, changed by steps a to g, written at the end.
 * {@link #topics()} holds the topics live at the start in list order (born, then creation), then the ones born tonight in
 * birth order; a topic retired tonight stays in it, marked. Labels in a night are indices into this list, so a topic
 * keeps its index all night. Topics retired before tonight are not read.
 */
@Getter
public class NightState {

    private final List<Topic> topics;
    private List<float[]> otherPrototypes;
    private final int rebuilds;
    private final LocalDate night;
    @Getter(AccessLevel.NONE)
    private final Supplier<UUID> ids;

    @Builder
    private NightState(@NonNull List<Topic> topics, @NonNull List<float[]> otherPrototypes, int rebuilds,
            @NonNull LocalDate night, @NonNull Supplier<UUID> ids) {
        this.topics = new ArrayList<>(topics);
        this.otherPrototypes = new ArrayList<>(otherPrototypes);
        this.rebuilds = rebuilds;
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
        /** No trace in the window, or under 0.5% of it on 3 saved nights in a row. */
        LOW_SHARE,
        /** Replaced by a forced new topic on a full list. */
        REPLACED;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static RetiredReason of(@NonNull String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    /**
     * A topic of the night. {@code prototypes} are unit vectors in assignment order; {@code lowNights} is the lifecycle
     * streak; {@code size} its count in the window after the night.
     */
    @Getter
    @Setter
    public static class Topic {
        private final UUID id;
        private final String name;
        private final String definition;
        private final LocalDate born;
        private List<float[]> prototypes;
        private int lowNights;
        private LocalDate retiredAt;
        private RetiredReason retiredReason;
        private int size;

        @Builder(toBuilder = true)
        private Topic(@NonNull UUID id, @NonNull String name, @NonNull String definition, @NonNull LocalDate born,
                @NonNull List<float[]> prototypes, int lowNights, LocalDate retiredAt, RetiredReason retiredReason,
                int size) {
            this.id = id;
            this.name = name;
            this.definition = definition;
            this.born = born;
            this.prototypes = new ArrayList<>(prototypes);
            this.lowNights = lowNights;
            this.retiredAt = retiredAt;
            this.retiredReason = retiredReason;
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
