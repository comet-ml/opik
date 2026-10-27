package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.EncryptedAuthConfig;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.utils.HeaderNames;
import lombok.Builder;
import lombok.ToString;

import java.util.Map;
import java.util.UUID;

@Builder
public record LlmProviderClientApiConfig(@ToString.Exclude String apiKey, Map<String, String> headers, String baseUrl,
        Map<String, String> configuration, UUID providerId, String workspaceId, EncryptedAuthConfig authConfig,
        LlmProvider provider) {

    /** Without the API key, and with only the names of the headers, whose values are never logged. */
    @Override
    public String toString() {
        return """
                LlmProviderClientConfig{apiKey='*********', headers=%s, baseUrl='%s', configuration=%s, \
                providerId=%s, workspaceId='%s', authConfig=%s, provider=%s}""".formatted(HeaderNames.of(headers),
                baseUrl, configuration, providerId, workspaceId, authConfig, provider);
    }

    /** Hand-declared so that Lombok's toString, which would print the API key and the header values, isn't. */
    public static class LlmProviderClientApiConfigBuilder {
        @Override
        public String toString() {
            return "LlmProviderClientApiConfigBuilder{headers=%s, providerId=%s, provider=%s}".formatted(
                    HeaderNames.of(headers), providerId, provider);
        }
    }
}
