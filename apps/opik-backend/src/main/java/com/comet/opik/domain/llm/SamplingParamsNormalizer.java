package com.comet.opik.domain.llm;

import com.comet.opik.infrastructure.llm.antropic.AnthropicModelName;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

@UtilityClass
public class SamplingParamsNormalizer {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

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

    /**
     * Whether an effort level turns thinking on: on a model that thinks only when asked, the Anthropic mapper and
     * judge generator send adaptive thinking next to the level, unless the request sets thinking itself.
     */
    public boolean effortTurnsOnThinking(String model, Map<String, Object> customParameters) {
        return AnthropicModelName.thinksOnlyWhenAsked(model)
                && customParameters != null
                && customParameters.get("thinking") == null
                && customParameters.get("output_config") instanceof Map<?, ?> outputConfig
                && outputConfig.get("effort") != null;
    }

    /** The same rule for a judge rule's {@code custom_parameters}. */
    public boolean effortTurnsOnThinking(String model, JsonNode customParameters) {
        return customParameters != null
                && customParameters.isObject()
                && effortTurnsOnThinking(model, JsonUtils.getMapper().convertValue(customParameters, MAP_TYPE));
    }

    private boolean isEnabledType(String type) {
        return StringUtils.isNotBlank(type) && !"disabled".equalsIgnoreCase(type);
    }
}
