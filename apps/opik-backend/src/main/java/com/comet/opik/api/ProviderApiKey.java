package com.comet.opik.api;

import com.comet.opik.utils.ProviderApiKeyDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProviderApiKey(
        @JsonView( {
                View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({View.Public.class, View.Write.class}) @NonNull LlmProvider provider,
        @JsonView({
                View.Write.class}) @NotBlank @JsonDeserialize(using = ProviderApiKeyDeserializer.class) String apiKey,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

    @Override
    public String toString() {
        return "ProviderApiKey{" +
                "id=" + id +
                ", provider='" + provider + '\'' +
                ", createdAt=" + createdAt +
                ", createdBy='" + createdBy + '\'' +
                ", lastUpdatedAt=" + lastUpdatedAt +
                ", lastUpdatedBy='" + lastUpdatedBy + '\'' +
                '}';
    }

    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }

    public record ProviderApiKeyPage(
            @JsonView( {
                    Project.View.Public.class}) int page,
            @JsonView({View.Public.class}) int size,
            @JsonView({View.Public.class}) long total,
            @JsonView({View.Public.class}) List<ProviderApiKey> content,
            @JsonView({View.Public.class}) List<String> sortableBy)
            implements
                com.comet.opik.api.Page<ProviderApiKey>{

        public static ProviderApiKeyPage empty(int page) {
            return new ProviderApiKeyPage(page, 0, 0, List.of(), List.of());
        }
    }
}
