package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.EncryptedAuthConfig;
import lombok.Builder;
import lombok.ToString;

import java.util.Map;
import java.util.UUID;

@Builder
public record LlmProviderClientApiConfig(@ToString.Exclude String apiKey, Map<String, String> headers, String baseUrl,
        Map<String, String> configuration, UUID providerId, String workspaceId, EncryptedAuthConfig authConfig) {

    @Override
    public String toString() {
        return "LlmProviderClientConfig{" +
                "apiKey='*********'" +
                ", headers=" + headers +
                ", baseUrl='" + baseUrl + '\'' +
                ", configuration=" + configuration +
                ", providerId=" + providerId +
                ", workspaceId='" + workspaceId + '\'' +
                ", authConfig=" + authConfig +
                '}';
    }
}
