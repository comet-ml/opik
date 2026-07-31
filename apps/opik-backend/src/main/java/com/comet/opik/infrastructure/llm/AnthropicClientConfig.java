package com.comet.opik.infrastructure.llm;

/**
 * Configuration for the Anthropic client.
 * <p>
 * Both components are optional: callers apply them only when non-blank and otherwise fall back to the SDK defaults,
 * so neither carries a validation constraint.
 */
public record AnthropicClientConfig(String url, String version) {
}
