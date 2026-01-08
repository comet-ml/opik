package com.comet.opik.api;

import com.comet.opik.utils.EncryptionDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Map;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProviderApiKeyUpdate(
        @JsonView( {
                ProviderApiKey.View.Public.class,
                ProviderApiKey.View.Write.class}) @JsonDeserialize(using = EncryptionDeserializer.class) String apiKey,
        @JsonView({ProviderApiKey.View.Public.class, ProviderApiKey.View.Write.class}) @Size(max = 150) String name,
        @JsonView({ProviderApiKey.View.Public.class,
                ProviderApiKey.View.Write.class}) @Size(max = 150) @Schema(description = "Provider name - can be set to migrate legacy custom LLM or Bedrock providers to the new multi-provider format. "
                        +
                        "Once set, it cannot be changed. Should only be set for custom LLM and Bedrock providers.", example = "ollama", requiredMode = Schema.RequiredMode.NOT_REQUIRED) String providerName,
        @JsonView({ProviderApiKey.View.Public.class, ProviderApiKey.View.Write.class}) Map<String, String> headers,
        @JsonView({ProviderApiKey.View.Public.class,
                ProviderApiKey.View.Write.class}) Map<String, String> configuration,
        @JsonView({ProviderApiKey.View.Public.class, ProviderApiKey.View.Write.class}) String baseUrl){

    @Override
    public String toString() {
        return "ProviderApiKeyUpdate{}";
    }
}
