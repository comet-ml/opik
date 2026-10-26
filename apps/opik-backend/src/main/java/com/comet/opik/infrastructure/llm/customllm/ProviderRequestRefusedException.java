package com.comet.opik.infrastructure.llm.customllm;

import dev.langchain4j.exception.NonRetriableException;
import lombok.NonNull;

/**
 * An outbound provider request refused before it left Opik, so no attempt can succeed. The message is user-facing.
 */
public class ProviderRequestRefusedException extends NonRetriableException {

    public ProviderRequestRefusedException(@NonNull String message) {
        super(message);
    }
}
