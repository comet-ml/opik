package com.comet.opik.api;

import lombok.Builder;

@Builder(toBuilder = true)
public record DatasetCriteria(String name) {
}
