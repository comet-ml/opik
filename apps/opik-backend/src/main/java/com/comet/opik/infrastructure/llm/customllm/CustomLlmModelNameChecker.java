package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.LlmProvider;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CustomLlmModelNameChecker {
    public static final String CUSTOM_LLM_MODEL_PREFIX = LlmProvider.CUSTOM_LLM.getValue() + "/";

    public static boolean isCustomLlmModel(String model) {
        try {
            return model.startsWith(CUSTOM_LLM_MODEL_PREFIX);
        } catch (Exception e) {
            // Keeping this try catch just in case for the future
            log.warn("Failed to check if model {} is a custom LLM: {}", model, e.getMessage());
            return false;
        }
    }
}