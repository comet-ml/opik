package com.comet.opik.domain.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

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
        // Some Claude models refuse both outright rather than refusing them together, and extended
        // thinking refuses them on any Claude: "temperature may only be set to 1 when thinking is
        // enabled", "top_p must be greater than or equal to 0.95 or unset when thinking is enabled".
        if (ModelCapabilities.rejectsSamplingParams(request.model()) || thinkingEnabled(request)) {
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

    /**
     * Extended thinking counts as enabled only when {@code custom_parameters.thinking.type} is an
     * explicit, non-blank value other than {@code "disabled"} — so {@code "enabled"},
     * {@code "adaptive"} and any future type gate sampling params off, while a missing or blank type
     * leaves them untouched.
     *
     * <p>Shared with {@code LlmProviderAnthropicMapper}, which applies the same rule on the Anthropic
     * provider's own path. The rule belongs to the model rather than the route, exactly as the
     * capability rule does.
     */
    public boolean thinkingEnabled(@NonNull ChatCompletionRequest request) {
        if (request.customParameters() == null
                || !(request.customParameters().get("thinking") instanceof Map<?, ?> thinking)) {
            return false;
        }
        return thinking.get("type") instanceof String type
                && StringUtils.isNotBlank(type)
                && !"disabled".equalsIgnoreCase(type);
    }
}
