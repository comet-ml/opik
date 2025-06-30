package com.comet.opik.infrastructure.llm.vllm;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VllmModelNameChecker {

    public static boolean isVllmModel(String model) {
        try {
            return model.startsWith("vllm/");
        } catch (Exception e) {
            // Keeping this try catch just in case for the future
            log.warn("Failed to check if model {} is VLLM model: {}", model, e.getMessage());
            return false;
        }
    }
}