package com.comet.opik.infrastructure.llm;

import lombok.Builder;
import lombok.ToString;

import java.util.Map;

@Builder
public record LlmProviderClientApiConfig(@ToString.Exclude String apiKey, Map<String, String> headers, String baseUrl,
        Map<String, String> configuration, Map<String, Object> extraBody) {

    @Override
    public String toString() {
        return "LlmProviderClientConfig{" +
                "apiKey='*********'" +
                ", headers=" + headers +
                ", baseUrl='" + baseUrl + '\'' +
                ", configuration=" + configuration +
                ", extraBody=" + extraBody +
                '}';
    }
}
