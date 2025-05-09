package com.comet.opik.infrastructure.llm;

import lombok.ToString;

import java.util.Map;

public record LlmProviderClientApiConfig(@ToString.Exclude String apiKey, Map<String, String> headers, String baseUrl) {

    @Override
    public String toString() {
        return "LlmProviderClientConfig{" +
                "apiKey='*********'" +
                ", headers=" + headers +
                ", baseUrl='" + baseUrl + '\'' +
                '}';
    }
}
