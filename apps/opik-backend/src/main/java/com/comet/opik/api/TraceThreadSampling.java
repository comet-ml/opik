package com.comet.opik.api;

import lombok.NonNull;

import java.util.Map;
import java.util.UUID;

public record TraceThreadSampling(@NonNull UUID threadModelId, @NonNull Map<UUID, Boolean> samplingPerRule) {
}
