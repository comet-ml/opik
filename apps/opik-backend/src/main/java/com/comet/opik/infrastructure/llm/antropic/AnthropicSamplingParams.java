package com.comet.opik.infrastructure.llm.antropic;

import java.util.Set;

/**
 * Anthropic models that reject any non-default value for {@code temperature},
 * {@code top_p}, or {@code top_k}. Add a new model id here when its docs say
 * the same. Mirrors {@code MODELS_WITHOUT_SAMPLING_PARAMS} on the frontend.
 */
final class AnthropicSamplingParams {

    private static final Set<String> MODELS_WITHOUT_SAMPLING_PARAMS = Set.of(
            "claude-opus-4-7");

    private AnthropicSamplingParams() {
    }

    static boolean supportsSamplingParams(String modelName) {
        return modelName == null || !MODELS_WITHOUT_SAMPLING_PARAMS.contains(modelName);
    }
}
