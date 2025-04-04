package com.comet.opik.infrastructure.log;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
        var contextMap = MDC.getCopyOfContextMap(); // capture from current thread
        return item -> {
            if (contextMap != null) {
                MDC.setContextMap(contextMap); // set in worker thread
            }

            try {
                task.accept(item);
            } finally {
                MDC.clear(); // always clear to prevent leakage
            }
        };
    }

    public static Closable wrapWithClosableMdc(Map<String, String> contextMap) {
        List<MDC.MDCCloseable> context = contextMap.entrySet()
                .stream()
                .map(entry -> MDC.putCloseable(entry.getKey(), entry.getValue()))
                .toList();

        return new Closable(context);
    }
}
