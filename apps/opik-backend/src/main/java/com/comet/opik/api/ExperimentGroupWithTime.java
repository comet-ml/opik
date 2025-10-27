package com.comet.opik.api;

import lombok.Builder;

import java.time.Instant;

@Builder(toBuilder = true)
public record ExperimentGroupWithTime(String name, Instant lastCreatedExperimentAt) {
}
