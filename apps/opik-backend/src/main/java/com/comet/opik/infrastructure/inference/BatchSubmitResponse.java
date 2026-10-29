package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchSubmitResponse(@NonNull String jobId, int items) {
}
