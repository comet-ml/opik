package com.comet.opik.domain;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.redaction.JsonNodeRedactor;
import com.comet.opik.infrastructure.redaction.RedactionRules;
import com.comet.opik.infrastructure.redaction.RedactionService;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.server.ChunkedOutput;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeoutException;

@Singleton
@Slf4j
public class Streamer {

    private final RedactionService redactionService;
    private final Provider<RequestContext> requestContext;

    @Inject
    public Streamer(@NonNull RedactionService redactionService,
            @NonNull Provider<RequestContext> requestContext) {
        this.redactionService = redactionService;
        this.requestContext = requestContext;
    }

    public <T> ChunkedOutput<JsonNode> getOutputStream(@NonNull Flux<T> flux) {
        return getOutputStream(flux, () -> {
        });
    }

    public <T> ChunkedOutput<JsonNode> getOutputStream(@NonNull Flux<T> flux, Runnable onCompleted) {
        var outputStream = new ChunkedOutput<JsonNode>(JsonNode.class, "\r\n");
        // Resolved here, while still on the request thread: the items below are built and written on a
        // scheduler thread, where neither the request scope nor the writer interceptor's thread-local exists.
        var rules = resolveRules();
        Schedulers.boundedElastic()
                .schedule(() -> flux.doOnNext(item -> sendItem(item, outputStream, rules))
                        .onErrorResume(throwable -> handleError(throwable, outputStream))
                        .doFinally(signalType -> {
                            close(outputStream);
                            onCompleted.run();
                        })
                        .subscribe());
        return outputStream;
    }

    private RedactionRules resolveRules() {
        if (!redactionService.isEnabled()) {
            return RedactionRules.empty();
        }

        try {
            var context = requestContext.get();
            return context != null && context.isRedactResponse()
                    ? redactionService.rules()
                    : RedactionRules.empty();
        } catch (RuntimeException outsideRequestScope) {
            // See RedactionService.redactWhenCallerUnknown - one definition, consulted by both write paths.
            return redactionService.redactWhenCallerUnknown() ? redactionService.rules() : RedactionRules.empty();
        }
    }

    private <T> void sendItem(T item, ChunkedOutput<JsonNode> outputStream, RedactionRules rules) {
        try {
            // deepCopy: JsonUtils.readTree is convertValue, which returns the same instance when handed a
            // JsonNode, and the redactor rewrites in place. Today every caller passes a POJO so the tree is
            // already fresh, but a future Flux<JsonNode> would have its source mutated underneath it, silently.
            outputStream.write(JsonNodeRedactor.redact(JsonUtils.readTree(item).deepCopy(), rules,
                    item.getClass()));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private <T> Flux<T> handleError(Throwable throwable, ChunkedOutput<JsonNode> outputStream) {
        if (throwable instanceof TimeoutException) {
            try {
                outputStream.write(JsonUtils.readTree(new ErrorMessage(500, "Streaming operation timed out")));
            } catch (IOException ioException) {
                log.error("Failed to stream error message to client", ioException);
            }
        }
        return Flux.error(throwable);
    }

    private void close(ChunkedOutput<JsonNode> outputStream) {
        try {
            outputStream.close();
        } catch (IOException exception) {
            log.error("Error while closing output stream", exception);
        }
    }
}
