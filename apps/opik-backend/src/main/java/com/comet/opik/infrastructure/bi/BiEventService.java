package com.comet.opik.infrastructure.bi;

import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@ImplementedBy(BiEventServiceImpl.class)
interface BiEventService {
    void reportEvent(String anonymousId, String eventType, String biEventType, Map<String, String> eventProperties);
}

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class BiEventServiceImpl implements BiEventService {

    private final @NonNull UsageReportService usageReport;
    private final @NonNull StatsClient statsClient;

    public void reportEvent(@NonNull String anonymousId, @NonNull String eventType, @NonNull String biEventType,
            @NonNull Map<String, String> eventProperties) {

        var event = BiEvent.builder()
                .anonymousId(anonymousId)
                .eventType(biEventType)
                .eventProperties(eventProperties)
                .build();

        if (statsClient.sendEvent(event)) {
            usageReport.markEventAsReported(eventType);
        }
    }
}
