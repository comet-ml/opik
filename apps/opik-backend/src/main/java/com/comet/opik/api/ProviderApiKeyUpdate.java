package com.comet.opik.api;

import com.comet.opik.utils.EncryptionDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
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
                ProviderApiKey.View.Write.class}) @Size(max = 100) String providerName,
        @JsonView({ProviderApiKey.View.Public.class, ProviderApiKey.View.Write.class}) Map<String, String> headers,
        @JsonView({ProviderApiKey.View.Public.class,
                ProviderApiKey.View.Write.class}) Map<String, String> configuration,
        @JsonView({ProviderApiKey.View.Public.class, ProviderApiKey.View.Write.class}) String baseUrl){

    @Override
    public String toString() {
        return "ProviderApiKeyUpdate{}";
    }
}
