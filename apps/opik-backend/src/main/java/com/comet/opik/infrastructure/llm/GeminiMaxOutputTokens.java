package com.comet.opik.infrastructure.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@UtilityClass
public class GeminiMaxOutputTokens {

    // Zero is never sent: the slider goes down to 0, and a zero cap could only ever produce an empty answer.
    public static Optional<Integer> firstPositive(@NonNull ChatCompletionRequest request) {
        return Stream.of(request.maxCompletionTokens(), request.maxTokens())
                .filter(Objects::nonNull)
                .filter(tokens -> tokens > 0)
                .findFirst();
    }
}
