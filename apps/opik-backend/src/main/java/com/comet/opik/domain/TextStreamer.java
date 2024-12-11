package com.comet.opik.domain;

import com.comet.opik.utils.JsonUtils;
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
public class TextStreamer {

    public ChunkedOutput<String> getOutputStream(@NonNull Flux<?> flux) {
        var outputStream = new ChunkedOutput<String>(String.class, "\n");
        Schedulers.boundedElastic()
                .schedule(() -> flux.doOnNext(item -> send(item, outputStream))
                        .onErrorResume(throwable -> handleError(throwable, outputStream))
                        .doFinally(signalType -> close(outputStream))
                        .subscribe());
        return outputStream;
    }

    private void send(Object item, ChunkedOutput<String> outputStream) {
        try {
            outputStream.write(JsonUtils.writeValueAsString(item));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private <T> Flux<T> handleError(Throwable throwable, ChunkedOutput<String> outputStream) {
        if (throwable instanceof TimeoutException) {
            try {
                send(new ErrorMessage(500, "Streaming operation timed out"), outputStream);
            } catch (UncheckedIOException uncheckedIOException) {
                log.error("Failed to stream error message to client", uncheckedIOException);
            }
        }
        return Flux.error(throwable);
    }

    private void close(ChunkedOutput<String> outputStream) {
        try {
            outputStream.close();
        } catch (IOException ioException) {
            log.error("Error while closing output stream", ioException);
        }
    }
}
