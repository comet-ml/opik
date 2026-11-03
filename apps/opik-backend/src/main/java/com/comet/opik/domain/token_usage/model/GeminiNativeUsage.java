package com.comet.opik.domain.token_usage.model;

import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record GeminiNativeUsage(
        @NonNull String provider,
        @NonNull String variant,
        long promptTokenCount,
        long cachedContentTokenCount,
        long toolUsePromptTokenCount,
        long candidatesTokenCount,
        long thoughtsTokenCount,
        long totalTokenCount) implements NativeUsage {

    @Override
    public OpenAiLikeUsage toOpenAiLike() {
        // Tool results are fed back to the model as input, so they count on the prompt side.
        long promptTokens = promptTokenCount + toolUsePromptTokenCount;
        // Gemini reports thinking outside the candidates and bills it as output. Some integrations' totals leave it
        // out (LangChain's equals prompt + candidates), so a reported total below the counts gives way to their sum.
        // One above them covers counts the client didn't send, such as a missing prompt, and is kept.
        long completionTokens = candidatesTokenCount + thoughtsTokenCount;
        return OpenAiLikeUsage.of(promptTokens, completionTokens,
                Math.max(totalTokenCount, promptTokens + completionTokens), cachedContentTokenCount);
    }
}
