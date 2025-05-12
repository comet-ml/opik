package com.comet.opik.infrastructure.llm.vertexai;

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
public enum VertexAIModelName {

    GEMINI_2_5_PRO_PREVIEW_04_17("vertex_ai/gemini-2.5-flash-preview-04-17", "gemini-2.5-flash-preview-04-17"),
    GEMINI_2_5_PRO_PREVIEW_05_06("vertex_ai/gemini-2.5-pro-preview-05-06", "gemini-2.5-pro-preview-05-06"),
    GEMINI_2_5_PRO_PREVIEW_03_25("vertex_ai/gemini-2.5-pro-preview-03-25", "gemini-2.5-pro-preview-03-25"),
    GEMINI_2_5_PRO_EXP_03_25("vertex_ai/gemini-2.5-pro-exp-03-25", "gemini-2.5-pro-exp-03-25"),
    GEMINI_2_0_FLASH("vertex_ai/gemini-2.0-flash-001", "gemini-2.0-flash-001"),
    GEMINI_2_0_FLASH_LITE("vertex_ai/gemini-2.0-flash-lite-001", "gemini-2.0-flash-lite-001"),
    ;

    private static final String WARNING_UNKNOWN_MODEL = "could not find VertexAIModelName with name '{}'";

    private final String qualifiedName;
    private final String value;

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
