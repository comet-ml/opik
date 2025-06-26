package com.comet.opik.infrastructure.llm.vllm;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum VllmModelName {
    LLAMA2("llama-2");

    private final String value;

    @Override
    public String toString() {
        return value;
    }
}
