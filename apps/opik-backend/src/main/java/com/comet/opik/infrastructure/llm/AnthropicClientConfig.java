package com.comet.opik.infrastructure.llm;

import jakarta.validation.constraints.NotBlank;

/**
 * Configuration for the Anthropic client.
 */
public record AnthropicClientConfig(@NotBlank String url, @NotBlank String version) {
}
