package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobCounts(int pending, int ok, int failed, int cancelled) {
}
