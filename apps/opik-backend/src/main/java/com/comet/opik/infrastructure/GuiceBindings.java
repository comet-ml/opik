package com.comet.opik.infrastructure;

import com.google.inject.Injector;

import java.util.stream.Stream;

/**
 * Helpers for traversing Guice bindings. Centralizes the {@code getAllBindings()} walk used to auto-discover beans
 * (event listeners, stream subscribers, ...) so the traversal lives in one place and callers only add their own
 * type/annotation filter.
 */
public final class GuiceBindings {

    private GuiceBindings() {
    }

    /**
     * Distinct raw types of every explicit binding in the injector. Callers apply their own filter (assignability,
     * annotation, ...) and resolve instances as needed.
     */
    public static Stream<Class<?>> boundRawTypes(Injector injector) {
        return injector.getAllBindings().keySet().stream()
                .<Class<?>>map(key -> key.getTypeLiteral().getRawType())
                .distinct();
    }
}
