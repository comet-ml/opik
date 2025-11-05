package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.LlmProvider;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import static org.apache.commons.lang3.StringUtils.isBlank;

@Slf4j
public class CustomLlmModelNameChecker {
    public static final String CUSTOM_LLM_MODEL_PREFIX = LlmProvider.CUSTOM_LLM.getValue() + "/";
    private static final String CUSTOM_LLM_SEPARATOR = "/";

    public static boolean isCustomLlmModel(@NonNull String model) {
        return model.startsWith(CUSTOM_LLM_MODEL_PREFIX);
    }

    /**
     * Extracts the actual model name by stripping the "custom-llm/" prefix and optionally the provider name.
     * For legacy providers (providerName = null):
     *   - Input: "custom-llm/mistralai/Mistral-7B-Instruct-v0.3"
     *   - Output: "mistralai/Mistral-7B-Instruct-v0.3"
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
    public static String extractModelName(@NonNull String model, String providerName) {
        if (!isCustomLlmModel(model)) {
            throw new IllegalArgumentException("Not a custom LLM model: " + model);
        }

        // Remove "custom-llm/" prefix
        String withoutCustomPrefix = model.substring(CUSTOM_LLM_MODEL_PREFIX.length());

        // Validate that we have a non-blank model name after removing the prefix
        if (isBlank(withoutCustomPrefix)) {
            throw new IllegalArgumentException(
                    "Invalid custom LLM model format: model name is blank after prefix. Model: " + model);
        }

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
}
