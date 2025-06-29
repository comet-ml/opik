package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.LlmProvider;

public interface ModelDefinition extends StructuredOutputSupported {
    String toString();

    LlmProvider getProvider();
}
