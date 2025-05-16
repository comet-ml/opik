package com.comet.opik.infrastructure.llm.vertexai.internal;

import com.google.cloud.vertexai.api.GenerateContentResponse;
import dev.langchain4j.model.output.TokenUsage;

public class TokenUsageMapper {

    static TokenUsage map(GenerateContentResponse.UsageMetadata usageMetadata) {
        return new TokenUsage(
                usageMetadata.getPromptTokenCount(),
                usageMetadata.getCandidatesTokenCount(),
                usageMetadata.getTotalTokenCount());
    }
}
