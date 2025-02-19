package com.comet.opik.infrastructure.llm.openrouter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Optional;

/**
 * This information is taken from <a href="https://openrouter.ai/models">OpenRouter docs</a>
 */
@Slf4j
@RequiredArgsConstructor
public enum OpenRouterModelName {
    // TODO: extend models list
    GEMINI_2_0_FLASH_LITE_PREVIEW_02_05_FREE("google/gemini-2.0-flash-lite-preview-02-05:free"),
    ;

    private static final String WARNING_UNKNOWN_MODEL = "could not find OpenRouterModelName with value '{}'";

    private final String value;

    public static Optional<OpenRouterModelName> byValue(String value) {
        var response = Arrays.stream(OpenRouterModelName.values())
                .filter(modelName -> modelName.value.equals(value))
                .findFirst();
        if (response.isEmpty()) {
            log.warn(WARNING_UNKNOWN_MODEL, value);
        }
        return response;
    }

    @Override
    public String toString() {
        return value;
    }
}
