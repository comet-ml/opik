package com.comet.opik.infrastructure.llm;

import jakarta.ws.rs.BadRequestException;

/** No API key of the workspace serves the requested model, so the call is never attempted. */
public class LlmProviderKeyNotConfiguredException extends BadRequestException {

    public LlmProviderKeyNotConfiguredException(String message) {
        super(message);
    }
}
