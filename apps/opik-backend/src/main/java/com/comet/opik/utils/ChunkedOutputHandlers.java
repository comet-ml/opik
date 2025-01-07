package com.comet.opik.utils;

import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.server.ChunkedOutput;

import java.io.IOException;
import java.io.UncheckedIOException;

@Slf4j
@RequiredArgsConstructor
public class ChunkedOutputHandlers {
    private final @NonNull ChunkedOutput<String> chunkedOutput;

    public void handleMessage(@NonNull Object item) {
        if (chunkedOutput.isClosed()) {
            log.warn("Output stream is already closed");
            return;
        }
        try {
            chunkedOutput.write(JsonUtils.writeValueAsString(item));
        } catch (IOException ioException) {
            throw new UncheckedIOException(ioException);
        }
    }

    public void handleClose() {
        try {
            chunkedOutput.close();
        } catch (IOException ioException) {
            log.error("Failed to close output stream", ioException);
        }
    }

    public void handleError(@NonNull ErrorMessage errorMessage) {
        try {
            handleMessage(errorMessage);
        } catch (UncheckedIOException uncheckedIOException) {
            log.error("Failed to stream error message to client", uncheckedIOException);
        }
        handleClose();
    }
}
