package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/** The jobs found, each as {@code GET /v1/batch/jobs/{id}?results=false} returns it; a job not found is left out. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchJobStatusResponse(@NonNull List<JobView> jobs) {
}
