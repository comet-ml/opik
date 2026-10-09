package com.comet.opik.infrastructure.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.MapUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

@UtilityClass
public class CustomParametersOverrides {

    public static final String MAX_TOKENS = "max_tokens";
    public static final String MAX_COMPLETION_TOKENS = "max_completion_tokens";

    // max_tokens and max_completion_tokens are two names for the one output limit, so a custom value under either
    // clears both typed fields. Left in, OpenAI answers 400 "Setting 'max_tokens' and 'max_completion_tokens' at the
    // same time is not supported", and other servers pick one of the two by their own rule.
    private static final Consumer<ChatCompletionRequest.Builder> TOKEN_LIMIT_CLEARER = builder -> builder
            .maxTokens(null)
            .maxCompletionTokens(null);

    // langchain4j writes custom parameters as extra top-level keys after the typed fields and keeps both when a
    // name matches, so the body could carry "temperature" twice. OpenAI answers that with 400 "duplicate JSON key",
    // OpenRouter and most servers keep the last copy. The custom value wins here as well, since it is the more
    // specific setting and the only way past a slider's range, and the typed field is cleared so the key goes once.
    private static final Map<String, Consumer<ChatCompletionRequest.Builder>> TYPED_FIELD_CLEARERS = Map.of(
            "temperature", builder -> builder.temperature(null),
            "top_p", builder -> builder.topP(null),
            MAX_TOKENS, TOKEN_LIMIT_CLEARER,
            MAX_COMPLETION_TOKENS, TOKEN_LIMIT_CLEARER,
            "presence_penalty", builder -> builder.presencePenalty(null),
            "frequency_penalty", builder -> builder.frequencyPenalty(null),
            "seed", builder -> builder.seed(null),
            "reasoning_effort", builder -> builder.reasoningEffort(null));

    public ChatCompletionRequest apply(@NonNull ChatCompletionRequest request) {
        if (MapUtils.isEmpty(request.customParameters())) {
            return request;
        }
        List<Consumer<ChatCompletionRequest.Builder>> clearers = request.customParameters().keySet().stream()
                .map(TYPED_FIELD_CLEARERS::get)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (clearers.isEmpty()) {
            return request;
        }
        var builder = ChatCompletionRequest.builder().from(request);
        clearers.forEach(clear -> clear.accept(builder));
        return builder.build();
    }

    public Map<String, Object> withTokenLimitUnder(@NonNull String limitKey, Map<String, Object> customParameters) {
        var otherKey = MAX_TOKENS.equals(limitKey) ? MAX_COMPLETION_TOKENS : MAX_TOKENS;
        if (MapUtils.isEmpty(customParameters) || !customParameters.containsKey(otherKey)) {
            return customParameters;
        }
        var moved = new LinkedHashMap<>(customParameters);
        var otherValue = moved.remove(otherKey);
        moved.putIfAbsent(limitKey, otherValue);
        return moved;
    }
}
