package com.comet.opik.infrastructure.inference;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

@Builder(toBuilder = true)
public record BatchJobStatusRequest(@NonNull List<String> jobIds) {
}
