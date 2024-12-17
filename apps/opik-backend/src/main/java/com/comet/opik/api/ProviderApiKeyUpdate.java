package com.comet.opik.api;

import com.comet.opik.utils.ProviderApiKeyDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProviderApiKeyUpdate(
        @NotBlank @JsonDeserialize(using = ProviderApiKeyDeserializer.class) String apiKey
){

    @Override
    public String toString() {
        return "ProviderApiKeyUpdate{}";
    }
}
