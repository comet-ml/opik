package com.comet.opik.infrastructure.llm.openrouter;

public record OpenRouterErrorMessage(OpenRouterError error) {

    public record OpenRouterError(String message, Integer code) {
    }

}
