package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.NightState.RetiredReason;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A topic of one nightly update. {@code prototypes} are unit vectors in assignment order; {@code lowNights} is the
 * lifecycle streak; {@code size} its count in the window after the night. A topic retired tonight carries its reason
 * until the save deletes it.
 */
@Getter
class NightTopic {

    private final UUID id;
    private final String name;
    private final String definition;
    private final LocalDate born;
    private List<float[]> prototypes;
    private int lowNights;
    private RetiredReason retiredReason;
    private int size;

    @Builder(toBuilder = true)
    private NightTopic(@NonNull UUID id, @NonNull String name, @NonNull String definition, @NonNull LocalDate born,
            @NonNull List<float[]> prototypes, int lowNights, RetiredReason retiredReason, int size) {
        this.id = id;
        this.name = name;
        this.definition = definition;
        this.born = born;
        this.prototypes = new ArrayList<>(prototypes);
        this.lowNights = lowNights;
        this.retiredReason = retiredReason;
        this.size = size;
    }

    boolean isLive() {
        return retiredReason == null;
    }

    List<float[]> getPrototypes() {
        return Collections.unmodifiableList(prototypes);
    }

    void setPrototypes(@NonNull List<float[]> prototypes) {
        this.prototypes = new ArrayList<>(prototypes);
    }

    /** The streak grows on a low night and goes back to 0 on any other; returns it. */
    int recordNight(boolean low) {
        lowNights = low ? lowNights + 1 : 0;
        return lowNights;
    }

    void retire(@NonNull RetiredReason reason) {
        retiredReason = reason;
    }

    void recordSize(int size) {
        this.size = size;
    }
}
