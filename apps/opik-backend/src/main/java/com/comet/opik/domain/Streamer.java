package com.comet.opik.domain;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.jersey.errors.ErrorMessage;
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

    public <T> ChunkedOutput<JsonNode> getOutputStream(@NonNull Flux<T> flux) {
        return getOutputStream(flux, () -> {
        });
    }

    public <T> ChunkedOutput<JsonNode> getOutputStream(@NonNull Flux<T> flux, Runnable onCompleted) {
        var outputStream = new ChunkedOutput<JsonNode>(JsonNode.class, "\r\n");
        Schedulers.boundedElastic()
                .schedule(() -> flux.doOnNext(item -> sendItem(item, outputStream))
                        .onErrorResume(throwable -> handleError(throwable, outputStream))
                        .doFinally(signalType -> {
                            close(outputStream);
                            onCompleted.run();
                        })
                        .subscribe());
        return outputStream;
    }

    private <T> void sendItem(T item, ChunkedOutput<JsonNode> outputStream) {
        try {
            outputStream.write(JsonUtils.readTree(item));
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
