package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of {@code topics}: a live topic of a project. A retired topic's row is deleted.
 *
 * @param born the UTC date of the night that made the topic, not the run time: the first build writes the night after
 *        its start, and a re-run of a past night writes that night
 * @param lowNights the nightly update's lifecycle streak; the first build writes 0
 * @param size the topic's count among the traces the last saved run read, first build or nightly update
 * @param vectors the topic's prototype vectors; the topics API does not read them
 */
@Builder(toBuilder = true)
public record Topic(
        @NonNull UUID id,
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull String name,
        @NonNull String definition,
        @NonNull LocalDate born,
        int lowNights,
        int size,
        @NonNull PrototypeVectors vectors) {
}
