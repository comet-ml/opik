package com.comet.opik.domain.llmproviders;

import com.comet.opik.domain.TokenUsage;
import lombok.Builder;

import java.util.Map;

@Builder
public record LlmResponse(String message, TokenUsage tokenUsage, Map<String, Object> metadata) {
}
