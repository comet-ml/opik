package com.comet.opik.infrastructure.llm.openrouter.decisions;

import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.Optional;

/**
 * The OpenRouter base URL has no decisions URL. Its message is built by Opik, not by the provider, so the rule
 * logs can show it.
 */
public class DecisionsUrlException extends BadRequestException {

    public DecisionsUrlException(String message) {
        super(message);
    }

    public static Optional<DecisionsUrlException> find(@NonNull Throwable error) {
        return Optional.ofNullable(ExceptionUtils.throwableOfType(error, DecisionsUrlException.class));
    }
}
