package com.comet.opik.infrastructure.llm;

/**
 * Configuration for the OpenAI client.
 * <p>
 * {@code url} is optional and empty by default in the shipped configuration: callers fall back to the SDK default
 * when it is blank, so it carries no validation constraint.
 */
public record OpenAiClientConfig(String url) {
}
