package com.comet.opik.infrastructure.llm.vllm;

import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum VllmModelName implements StructuredOutputSupported {
    LLAMA2("llama-2", false);

    private final String value;
    private final boolean structuredOutputSupported;

    @Override
    public boolean isStructuredOutputSupported() {
        return this.structuredOutputSupported;
    }

    @Override
    public String toString() {
        return value;
    }
}
