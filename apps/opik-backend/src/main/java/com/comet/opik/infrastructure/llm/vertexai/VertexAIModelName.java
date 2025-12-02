package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Getter
@Accessors(fluent = true)
public enum VertexAIModelName implements StructuredOutputSupported {

    GEMINI_3_PRO("vertex_ai/gemini-3-pro-preview", "gemini-3-pro-preview", true),
    GEMINI_2_5_PRO_PREVIEW_04_17("vertex_ai/gemini-2.5-flash-preview-04-17", "gemini-2.5-flash-preview-04-17", true),
    GEMINI_2_5_PRO_PREVIEW_05_06("vertex_ai/gemini-2.5-pro-preview-05-06", "gemini-2.5-pro-preview-05-06", true),
    GEMINI_2_5_PRO_PREVIEW_03_25("vertex_ai/gemini-2.5-pro-preview-03-25", "gemini-2.5-pro-preview-03-25", true),
    GEMINI_2_5_PRO_EXP_03_25("vertex_ai/gemini-2.5-pro-exp-03-25", "gemini-2.5-pro-exp-03-25", true),
    GEMINI_2_0_FLASH("vertex_ai/gemini-2.0-flash-001", "gemini-2.0-flash-001", true),
    GEMINI_2_0_FLASH_LITE("vertex_ai/gemini-2.0-flash-lite-001", "gemini-2.0-flash-lite-001", false),
    GEMINI_2_5_PRO("vertex_ai/gemini-2.5-pro", "gemini-2.5-pro", true),
    GEMINI_2_5_FLASH("vertex_ai/gemini-2.5-flash", "gemini-2.5-flash", true),
    GEMINI_2_5_FLASH_LITE_PREVIEW_06_17("vertex_ai/gemini-2.5-flash-lite-preview-06-17",
            "gemini-2.5-flash-lite-preview-06-17", true),
            ;

    private static final String WARNING_UNKNOWN_MODEL = "could not find VertexAIModelName with name '{}'";

    private final String qualifiedName;
    private final String value;
    private final boolean structuredOutputSupported;

    @Override
    public boolean isStructuredOutputSupported() {
        return this.structuredOutputSupported;
    }

    public static Optional<VertexAIModelName> byQualifiedName(String qualifiedName) {
        var response = Arrays.stream(VertexAIModelName.values())
                .filter(modelName -> modelName.qualifiedName.equals(qualifiedName))
                .findFirst();
        if (response.isEmpty()) {
            log.warn(WARNING_UNKNOWN_MODEL, qualifiedName);
        }
        return response;
    }

    @Override
    public String toString() {
        return value;
    }

}
