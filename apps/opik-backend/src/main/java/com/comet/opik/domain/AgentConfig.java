package com.comet.opik.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentConfig(
        UUID id,
        UUID projectId,
        String createdBy,
        Instant createdAt,
        String lastUpdatedBy,
        Instant lastUpdatedAt) {

    public static class View {
        public static class Write {
        }

        public static class Public {
        }

        public static class History {
        }
    }
}
