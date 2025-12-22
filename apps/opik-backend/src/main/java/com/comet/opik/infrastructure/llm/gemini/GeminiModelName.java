package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
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
public enum GeminiModelName implements StructuredOutputSupported {
    GEMINI_3_FLASH("gemini-3-flash-preview", true),
    GEMINI_3_PRO("gemini-3-pro-preview", true),
    GEMINI_2_0_FLASH("gemini-2.0-flash-exp", true),
    GEMINI_2_0_FLASH_LITE("gemini-2.0-flash-lite", true),
    GEMINI_2_5_PRO("gemini-2.5-pro", true),
    GEMINI_2_5_FLASH("gemini-2.5-flash", true),
    GEMINI_2_5_FLASH_LITE("gemini-2.5-flash-lite", true),
    GEMINI_1_5_PRO_LATEST("gemini-1.5-pro-latest", true),
    GEMINI_1_5_FLASH("gemini-1.5-flash", false),
    GEMINI_1_5_FLASH_LATEST("gemini-1.5-flash-latest", true),
    GEMINI_1_5_FLASH_8B("gemini-1.5-flash-8b", false),
    GEMINI_1_5_PRO("gemini-1.5-pro", true),
    TEXT_EMBEDDING("text-embedding-004", false),
    AQA("aqa", false),
    GEMINI_1_0_PRO("gemini-1.0-pro", false),
    GEMINI_PRO_VISION("gemini-pro-vision", false);

    private static final String WARNING_UNKNOWN_MODEL = "could not find GeminiModelName with value '{}'";

    private final String value;
    private final boolean structuredOutputSupported;

    @Override
    public boolean isStructuredOutputSupported() {
        return this.structuredOutputSupported;
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
