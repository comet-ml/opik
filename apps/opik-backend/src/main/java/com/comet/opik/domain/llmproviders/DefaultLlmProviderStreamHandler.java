package com.comet.opik.domain.llmproviders;

import com.comet.opik.utils.JsonUtils;
import dev.ai4j.openai4j.OpenAiHttpException;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.server.ChunkedOutput;

import java.io.IOException;
import java.io.UncheckedIOException;

@Slf4j
public class DefaultLlmProviderStreamHandler implements LlmProviderStreamHandler {
    private static final String UNEXPECTED_ERROR_CALLING_LLM_PROVIDER = "Unexpected error calling LLM provider";

    @Override
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

    @Override
    public void handleClose(ChunkedOutput<String> chunkedOutput) {
        try {
            chunkedOutput.close();
        } catch (IOException ioException) {
            log.error("Failed to close output stream", ioException);
        }
    }

    @Override
    public void handleError(Throwable throwable, ChunkedOutput<String> chunkedOutput) {
        log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, throwable);
        var errorMessage = new ErrorMessage(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
        if (throwable instanceof OpenAiHttpException openAiHttpException) {
            errorMessage = new ErrorMessage(openAiHttpException.code(), openAiHttpException.getMessage());
        }
        try {
            handleMessage(errorMessage, chunkedOutput);
        } catch (UncheckedIOException uncheckedIOException) {
            log.error("Failed to stream error message to client", uncheckedIOException);
        }
        handleClose(chunkedOutput);
    }
}
