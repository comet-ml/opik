package com.comet.opik.domain;

import lombok.Builder;

@Builder
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
}
