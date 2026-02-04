package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Set;

@Getter
@RequiredArgsConstructor
public enum ExperimentType {
    REGULAR("regular"),
    TRIAL("trial"),
    MINI_BATCH("mini-batch"),
    LIVE("live"),
    AB("ab"),
    OPTIMIZER("optimizer");

    @JsonValue
    private final String value;

    private static final Set<ExperimentType> DATASET_REQUIRED_TYPES = Set.of(REGULAR, TRIAL, MINI_BATCH);

    @JsonCreator
    public static ExperimentType fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Experiment Type '%s'".formatted(value)));
    }

    public boolean requiresDataset() {
        return DATASET_REQUIRED_TYPES.contains(this);
    }
}
