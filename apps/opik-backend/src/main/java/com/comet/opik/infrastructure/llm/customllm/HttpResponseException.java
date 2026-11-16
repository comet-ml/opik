package com.comet.opik.infrastructure.llm.customllm;

import dev.langchain4j.exception.HttpException;
import lombok.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A non-2xx response with its headers kept, for a provider that names its error in a header rather than in the body.
 */
class HttpResponseException extends HttpException {

    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    HttpResponseException(int statusCode, String body, @NonNull Map<String, List<String>> headers) {
        super(statusCode, body);
        this.headers.putAll(headers);
    }

    Optional<String> header(@NonNull String name) {
        return Optional.ofNullable(headers.get(name))
                .flatMap(values -> values.stream().findFirst());
    }
}
