package com.comet.opik.infrastructure.llm.openrouter.decisions;

import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.Optional;

/**
 * The OpenRouter base URL has no decisions URL. Its message is fixed text, without the URL, so the rule logs can
 * show it.
 */
public class DecisionsUrlException extends BadRequestException {

    static final String MESSAGE = "Decision models need an OpenRouter base URL whose path ends with /v1,"
            + " for example https://openrouter.ai/api/v1.";

    public DecisionsUrlException() {
        super(MESSAGE);
    }

    public static Optional<DecisionsUrlException> find(@NonNull Throwable error) {
        return Optional.ofNullable(ExceptionUtils.throwableOfType(error, DecisionsUrlException.class));
    }
}
