package com.comet.opik.domain;

import io.reactivex.rxjava3.annotations.Nullable;

public record ExperimentUpdatePlan(
        @Nullable String targetName,
        @Nullable String targetMetadata,
        boolean changed) {
}