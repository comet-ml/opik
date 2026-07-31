package com.comet.opik.infrastructure.llm;

/**
 * Configuration for the OpenAI client.
 * <p>
 * {@code url} carries no constraint on purpose. The shipped {@code config.yml} leaves it empty
 * ({@code url: ${LLM_PROVIDER_OPENAI_URL:-}}), which deserialises to {@code null} and means "use the provider
 * default" — every caller reads it through {@code Optional.ofNullable(...).filter(isNotBlank)}. A {@code @NotNull} or
 * {@code @NotBlank} here would therefore fail startup on a default installation.
 */
public record OpenAiClientConfig(String url) {
}
