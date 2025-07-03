package com.comet.opik.domain;

import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
public record ExperimentItemSearchCriteria(
        String experimentName,
        Integer limit,
        UUID lastRetrievedId,
        boolean truncate) {
}
