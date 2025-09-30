package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.LlmProvider;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CustomLlmModelNameChecker {
    public static final String CUSTOM_LLM_MODEL_PREFIX = LlmProvider.CUSTOM_LLM.getValue() + "/";
    private static final String CUSTOM_LLM_SEPARATOR = "/";

    public static boolean isCustomLlmModel(String model) {
        try {
            return model.startsWith(CUSTOM_LLM_MODEL_PREFIX);
        } catch (Exception e) {
            // Keeping this try catch just in case for the future
            log.warn("Failed to check if model {} is a custom LLM: {}", model, e.getMessage());
            return false;
        }
    }

    /**
     * Extracts provider name from model string.
     * Example: "custom-llm/ollama/llama-3.2" → "ollama"
     *
     * @param model The full model identifier (e.g., "custom-llm/ollama/llama-3.2")
     * @return The provider name (e.g., "ollama")
     * @throws IllegalArgumentException if the model is not a custom LLM model or format is invalid
     */
    public static String extractProviderName(String model) {
        if (!isCustomLlmModel(model)) {
            throw new IllegalArgumentException("Not a custom LLM model: " + model);
        }

        // Remove "custom-llm/" prefix
        String withoutPrefix = model.substring(CUSTOM_LLM_MODEL_PREFIX.length());

        // Find the next separator to extract provider name
        int separatorIndex = withoutPrefix.indexOf(CUSTOM_LLM_SEPARATOR);
        if (separatorIndex == -1) {
            // legacy provider will not have a provider name
            return null;
        }

        return withoutPrefix.substring(0, separatorIndex);
    }
}