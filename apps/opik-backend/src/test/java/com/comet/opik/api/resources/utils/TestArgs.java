package com.comet.opik.api.resources.utils;

import java.util.List;

public record TestArgs<T>(List<T> all, List<T> expected, List<T> unexpected) {
    public static <T> TestArgs<T> of(List<T> all, List<T> expected, List<T> unexpected) {
        return new TestArgs<>(all, expected, unexpected);
    }
}
