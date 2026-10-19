package com.comet.opik.domain.llm;

import com.fasterxml.jackson.databind.JsonNode;
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
     */
    public boolean thinkingEnabled(@NonNull ChatCompletionRequest request) {
        if (request.customParameters() == null
                || !(request.customParameters().get("thinking") instanceof Map<?, ?> thinking)) {
            return false;
        }
        return thinking.get("type") instanceof String type && isEnabledType(type);
    }

    /** The same rule for a judge rule's {@code custom_parameters}. */
    public boolean thinkingEnabled(JsonNode customParameters) {
        var type = customParameters == null ? null : customParameters.path("thinking").path("type");
        return type != null && type.isTextual() && isEnabledType(type.asText());
    }

    private boolean isEnabledType(String type) {
        return StringUtils.isNotBlank(type) && !"disabled".equalsIgnoreCase(type);
    }
}
