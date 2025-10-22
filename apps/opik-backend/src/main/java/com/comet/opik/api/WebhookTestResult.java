package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WebhookTestResult(
        Status status,
        int statusCode,
        String requestBody,
        String errorMessage) {

    @Getter
    @RequiredArgsConstructor
    public enum Status {
        SUCCESS("success"),
        FAILURE("failure");

        @JsonValue
        private final String value;
    }
}
