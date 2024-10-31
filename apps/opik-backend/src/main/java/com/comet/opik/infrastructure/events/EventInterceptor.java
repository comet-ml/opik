package com.comet.opik.infrastructure.events;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import java.util.Arrays;

import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.TRACER_NAME;

@Slf4j
class EventInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation methodInvocation) {
        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);
        Meter meter = GlobalOpenTelemetry.get().getMeter(TRACER_NAME);

        var event = Arrays.stream(methodInvocation.getArguments())
                .findFirst()
                .map(obj -> obj.getClass().getSimpleName())
                .orElse("Event");

        var eventClass = methodInvocation.getMethod().getDeclaringClass().getSimpleName();

        String listenerName = "%s.%s.%s".formatted(eventClass, methodInvocation.getMethod().getName(), event);

        Span span = tracer
                .spanBuilder(listenerName)
                .startSpan();

        if (methodInvocation.getArguments().length > 0
                && methodInvocation.getArguments()[0] instanceof BaseEvent baseEvent) {
            span.setAttribute("parent_trace_id", baseEvent.traceId());
        }

        try (Scope scope = span.makeCurrent()) {
            Object result = methodInvocation.proceed();
            log.info("Event intercepted: {}", event);
            return result;
        } catch (Throwable e) {
            meter.counterBuilder("opik.event.error")
                    .setDescription("Event processing error in OpiK")
                    .build()
                    .add(1);

            meter.counterBuilder("opik.event.error.%s".formatted(listenerName.toLowerCase()))
                    .setDescription("Event Listener processing error in OpiK")
                    .build()
                    .add(1);

            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "Failed to process event");
            log.error("Failed to process event", e);
            return null;
        } finally {
            span.end();
        }
    }
}
