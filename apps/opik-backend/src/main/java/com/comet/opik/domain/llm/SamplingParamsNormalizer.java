package com.comet.opik.domain.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

/**
 * Drops sampling parameters a model will not accept alongside each other.
 *
 * <p>Applied centrally, before the request reaches a provider, because the constraint belongs to the
 * model rather than to whoever routes it. Anthropic's own provider already drops top_p in
 * {@code LlmProviderAnthropicMapper}, but the same Claude models reach us through Bedrock and
 * OpenAI-compatible proxies, which share a generic path with no such rule — and Bedrock answers a
 * request carrying both with {@code "temperature and top_p cannot both be specified for this model"}.
 */
@UtilityClass
public class SamplingParamsNormalizer {

    public ChatCompletionRequest normalizeRequest(@NonNull ChatCompletionRequest request) {
        // Some Claude models refuse both outright rather than refusing them together.
        if (ModelCapabilities.rejectsSamplingParams(request.model())) {
            return request.temperature() == null && request.topP() == null
                    ? request
                    : ChatCompletionRequest.builder().from(request).temperature(null).topP(null).build();
        }

        if (request.temperature() == null
                || request.topP() == null
                || !ModelCapabilities.requiresExclusiveSamplingParams(request.model())) {
            return request;
        }

        // Temperature wins, matching LlmProviderAnthropicMapper and Anthropic's own guidance.
        return ChatCompletionRequest.builder().from(request).topP(null).build();
    }
}
