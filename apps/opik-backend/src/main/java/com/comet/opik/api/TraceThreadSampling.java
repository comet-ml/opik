package com.comet.opik.api;

import com.comet.opik.domain.threads.TraceThreadModel;
import lombok.NonNull;

import java.util.Map;
import java.util.UUID;

public record TraceThreadSampling(@NonNull TraceThreadModel traceThread, @NonNull Map<UUID, Boolean> samplingPerRule) {

    public UUID threadModelId() {
        return traceThread.id();
    }
}
