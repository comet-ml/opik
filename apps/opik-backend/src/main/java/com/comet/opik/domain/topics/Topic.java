package com.comet.opik.domain.topics;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of {@code topics}. {@code lowNights} is the nightly update's lifecycle streak; the first build leaves it 0.
 * {@code size} is the topic's count in the last saved night's window.
 */
@Builder(toBuilder = true)
public record Topic(
        @NonNull UUID id,
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull String name,
        @NonNull String definition,
        @NonNull LocalDate born,
        @Nullable LocalDate retiredAt,
        @Nullable String retiredReason,
        int lowNights,
        int size) {
}
