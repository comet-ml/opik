package com.comet.opik.infrastructure.events;

import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import java.util.Arrays;

import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.TRACER_NAME;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

@Slf4j
class EventInterceptor implements MethodInterceptor {

    private static final AttributeKey<String> LISTENER_KEY = stringKey("listener");

    private final LongCounter consumedCounter;
    private final LongCounter errorCounter;

    EventInterceptor() {
        var meter = GlobalOpenTelemetry.get().getMeter(TRACER_NAME);
        this.consumedCounter = meter
                .counterBuilder("opik.event.consumed")
                .setDescription("Events successfully consumed, by listener")
                .build();
        this.errorCounter = meter
                .counterBuilder("opik.event.error")
                .setDescription("Event processing errors, by listener and error type")
                .build();
    }

    @Override
    public Object invoke(MethodInvocation methodInvocation) {
        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

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
            consumedCounter.add(1, Attributes.of(LISTENER_KEY, listenerName));
            log.info("Event intercepted: {}", event);
            return result;
        } catch (Throwable e) {
            errorCounter.add(1, Attributes.of(
                    LISTENER_KEY, listenerName,
                    ErrorMetricsResolver.ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(e)));

            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "Failed to process event");
            log.error("Failed to process event", e);
            return null;
        } finally {
            span.end();
        }
    }

}
