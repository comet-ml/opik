package com.comet.opik.infrastructure.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.MapUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

@UtilityClass
public class CustomParametersOverrides {

    // langchain4j writes custom parameters as extra top-level keys after the typed fields and keeps both when a
    // name matches, so the body could carry "temperature" twice. OpenAI answers that with 400 "duplicate JSON key",
    // OpenRouter and most servers keep the last copy. The custom value wins here as well, since it is the more
    // specific setting and the only way past a slider's range, and the typed field is cleared so the key goes once.
    private static final Map<String, Consumer<ChatCompletionRequest.Builder>> TYPED_FIELD_CLEARERS = Map.of(
            "temperature", builder -> builder.temperature(null),
            "top_p", builder -> builder.topP(null),
            "max_tokens", builder -> builder.maxTokens(null),
            "max_completion_tokens", builder -> builder.maxCompletionTokens(null),
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
                .toList();
        if (clearers.isEmpty()) {
            return request;
        }
        var builder = ChatCompletionRequest.builder().from(request);
        clearers.forEach(clear -> clear.accept(builder));
        return builder.build();
    }
}
