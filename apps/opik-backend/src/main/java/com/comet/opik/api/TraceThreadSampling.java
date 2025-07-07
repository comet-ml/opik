package com.comet.opik.api;

import java.util.Map;
import java.util.UUID;

public record TraceThreadSampling(UUID threadModelId, Map<UUID, Boolean> samplingPerRule) {
}
