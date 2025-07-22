package com.comet.opik.infrastructure.events;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.TRACER_NAME;

@SuperBuilder(toBuilder = true)
@Data
@Accessors(fluent = true)
public abstract class BaseEvent {

    private static final Tracer TRACER = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

    private final String traceId;
    protected final String workspaceId;
    protected final String userName;

    protected BaseEvent(String workspaceId, String userName) {
        this.traceId = Span.current().getSpanContext().getTraceId();
        this.workspaceId = workspaceId;
        this.userName = userName;
    }

}
