package com.comet.opik.infrastructure.log;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@UtilityClass
public class LogContextAware {

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Closable implements AutoCloseable {

        private final List<MDC.MDCCloseable> context;

        @Override
        public void close() {
            context.forEach(MDC.MDCCloseable::close);
        }
    }

    public static Runnable wrapWithMdc(Runnable task) {
        var contextMap = MDC.getCopyOfContextMap();

        return () -> {

            if (contextMap != null) {
                MDC.setContextMap(contextMap);
            }

            try {
                task.run();
            } finally {
                MDC.clear();
            }
        };
    }

    public static <T> Consumer<T> wrapWithMdc(Consumer<T> task) {
        var contextMap = MDC.getCopyOfContextMap();
        return item -> {
            if (contextMap != null) {
                MDC.setContextMap(contextMap);
            }

            try {
                task.accept(item);
            } finally {
                MDC.clear();
            }
        };
    }

    public static Closable wrapWithMdc(Map<String, String> contextMap) {
        List<MDC.MDCCloseable> context = contextMap.entrySet()
                .stream()
                .map(entry -> MDC.putCloseable(entry.getKey(), entry.getValue()))
                .toList();

        return new Closable(context);
    }
}
