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
     * @return The provider name (e.g., "ollama"), or null for legacy providers
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

    /**
     * Extracts the actual model name by stripping both the "custom-llm/" prefix and provider name.
     * Example: "custom-llm/ollama/llama-3.2" → "llama-3.2"
     * Example: "custom-llm/llama-3.2" → "llama-3.2" (legacy format)
     *
     * @param model The full model identifier
     * @return The actual model name without prefixes
     * @throws IllegalArgumentException if the model is not a custom LLM model
     */
    public static String extractModelName(String model) {
        if (!isCustomLlmModel(model)) {
            throw new IllegalArgumentException("Not a custom LLM model: " + model);
        }

        // Remove "custom-llm/" prefix
        String withoutPrefix = model.substring(CUSTOM_LLM_MODEL_PREFIX.length());

        // Find the next separator
        int separatorIndex = withoutPrefix.indexOf(CUSTOM_LLM_SEPARATOR);
        if (separatorIndex == -1) {
            // Legacy format: "custom-llm/model-name"
            return withoutPrefix;
        }

        // New format: "custom-llm/provider-name/model-name"
        // Return everything after the provider name
        return withoutPrefix.substring(separatorIndex + 1);
    }
}