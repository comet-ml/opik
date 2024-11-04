package com.comet.opik.infrastructure.instrumentation;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

@Slf4j
@UtilityClass
public class InstrumentAsyncUtils {

    public static final String TRACER_NAME = "com.comet.opik";

    public record Segment(Scope scope, Span span) {
    }

    public static Segment startSegment(String segmentName, String product, String operationName) {

        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

        Span span = tracer
                .spanBuilder("custom-reactive-%s".formatted(segmentName))
                .setParent(Context.current().with(Span.current()))
                .startSpan()
                .setAttribute("product", product)
                .setAttribute("operation", operationName);

        return new Segment(span.makeCurrent(), span);
    }

    public static void endSegment(Segment segment) {
        if (segment != null) {
            // Fire and forget logic
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    // End the segment
                    segment.scope().close();
                    segment.span().end();
                } catch (Exception e) {
                    log.warn("Failed to end segment", e);
                }
            });
        }
    }
}
