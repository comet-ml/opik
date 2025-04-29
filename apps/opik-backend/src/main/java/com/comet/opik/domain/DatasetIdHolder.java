package com.comet.opik.domain;

import lombok.Builder;

import java.util.UUID;

@Builder
public record DatasetIdHolder(UUID datasetId) {
}