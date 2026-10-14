package com.comet.opik.domain.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

@UtilityClass
public class SamplingParamsNormalizer {

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
