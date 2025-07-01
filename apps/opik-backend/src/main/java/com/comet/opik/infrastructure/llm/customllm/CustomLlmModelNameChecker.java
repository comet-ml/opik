package com.comet.opik.infrastructure.llm.customllm;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CustomLlmModelNameChecker {
    public static final String CUSTOM_LLM_MODEL_PREFIX = "custom/";

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