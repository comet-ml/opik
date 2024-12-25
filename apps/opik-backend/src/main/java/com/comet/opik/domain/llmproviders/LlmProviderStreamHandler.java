package com.comet.opik.domain.llmproviders;

import com.comet.opik.utils.JsonUtils;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.server.ChunkedOutput;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
public class LlmProviderStreamHandler {
    private static final String UNEXPECTED_ERROR_CALLING_LLM_PROVIDER = "Unexpected error calling LLM provider";

    public void handleMessage(Object item, ChunkedOutput<String> chunkedOutput) {
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

    public void handleClose(ChunkedOutput<String> chunkedOutput) {
        try {
            chunkedOutput.close();
        } catch (IOException ioException) {
            log.error("Failed to close output stream", ioException);
        }
    }

    public Consumer<Throwable> getErrorHandler(
            Function<Throwable, ErrorMessage> mapper, ChunkedOutput<String> chunkedOutput) {
        return throwable -> {
            log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, throwable);

            var errorMessage = mapper.apply(throwable);
            try {
                handleMessage(errorMessage, chunkedOutput);
            } catch (UncheckedIOException uncheckedIOException) {
                log.error("Failed to stream error message to client", uncheckedIOException);
            }
            handleClose(chunkedOutput);
        };
    }
}
