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
     * Extracts the actual model name by stripping the "custom-llm/" prefix and optionally the provider name.
     *
     * For legacy providers (providerName = null):
     *   - Input: "custom-llm/mistralai/Mistral-7B-Instruct-v0.3"
     *   - Output: "mistralai/Mistral-7B-Instruct-v0.3"
     *
     * For new providers (providerName set):
     *   - Input: "custom-llm/ollama/llama-3.2", providerName="ollama"
     *   - Output: "llama-3.2"
     *   - Input: "custom-llm/vllm/mistralai/Mistral-7B-Instruct-v0.3", providerName="vllm"
     *   - Output: "mistralai/Mistral-7B-Instruct-v0.3"
     *
     * @param model The full model identifier
     * @param providerName The provider name from the database (null for legacy providers)
     * @return The actual model name without prefixes
     * @throws IllegalArgumentException if the model is not a custom LLM model
     */
    public static String extractModelName(String model, String providerName) {
        if (!isCustomLlmModel(model)) {
            throw new IllegalArgumentException("Not a custom LLM model: " + model);
        }

        // Remove "custom-llm/" prefix
        String withoutCustomPrefix = model.substring(CUSTOM_LLM_MODEL_PREFIX.length());

        // If providerName is null (legacy), return the model as-is after stripping "custom-llm/"
        if (providerName == null) {
            return withoutCustomPrefix;
        }

        // For new format, strip the provider name prefix
        String providerPrefix = providerName + CUSTOM_LLM_SEPARATOR;
        if (!withoutCustomPrefix.startsWith(providerPrefix)) {
            log.warn("Model '{}' does not start with expected provider prefix '{}'", model, providerPrefix);
            return withoutCustomPrefix;
        }

        return withoutCustomPrefix.substring(providerPrefix.length());
    }

    /**
     * Extracts the actual model name by stripping both the "custom-llm/" prefix and provider name.
     * This method attempts to auto-detect the format by finding the first separator.
     *
     * @deprecated Use {@link #extractModelName(String, String)} with explicit providerName for accurate extraction
     * @param model The full model identifier
     * @return The actual model name without prefixes
     * @throws IllegalArgumentException if the model is not a custom LLM model
     */
    @Deprecated
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