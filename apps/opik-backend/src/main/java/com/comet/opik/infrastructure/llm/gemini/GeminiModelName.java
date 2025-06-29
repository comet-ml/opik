package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.llm.ModelDefinition;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Optional;

/**
 * This information is taken from <a href="https://ai.google.dev/gemini-api/docs/models/gemini">gemini docs</a>
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public enum GeminiModelName implements ModelDefinition {
    GEMINI_1_5_PRO_LATEST("gemini-1.5-pro-latest", true),
    GEMINI_1_5_FLASH_LATEST("gemini-1.5-flash-latest", true),
    GEMINI_1_0_PRO("gemini-1.0-pro", true),
    GEMINI_PRO_VISION("gemini-pro-vision", true);

    private static final String WARNING_UNKNOWN_MODEL = "could not find GeminiModelName with value '{}'";

    private final String value;
    private final boolean structuredOutputSupported;

    @Override
    public boolean isStructuredOutputSupported() {
        return this.structuredOutputSupported;
    }

    @Override
    public LlmProvider getProvider() {
        return LlmProvider.GEMINI;
    }

    public static Optional<GeminiModelName> byValue(String value) {
        var response = Arrays.stream(GeminiModelName.values())
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
