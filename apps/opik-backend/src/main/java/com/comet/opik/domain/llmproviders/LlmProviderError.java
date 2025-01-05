package com.comet.opik.domain.llmproviders;

import lombok.Builder;

@Builder(toBuilder = true)
public record LlmProviderError(int code, String message) {
}
