package com.comet.opik.api;

import com.comet.opik.utils.ProviderApiKeyDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProviderApiKey(
        @JsonView( {
                View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({View.Public.class, View.Write.class}) @NotNull LlmProvider provider,
        @JsonView({View.Public.class,
                View.Write.class}) @NotBlank @JsonDeserialize(using = ProviderApiKeyDeserializer.class) String apiKey,
        @JsonView({View.Public.class, View.Write.class}) @Size(max = 150) String name,
        @JsonView({View.Public.class, View.Write.class}) Map<String, String> headers,
        @JsonView({View.Public.class, View.Write.class}) Map<String, String> configuration,
        @JsonView({View.Public.class,
                View.Write.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String baseUrl,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

    @Override
    public String toString() {
        return "ProviderApiKey{" +
                "id=" + id +
                ", provider=" + provider +
                ", apiKey='*******'" +
                ", name='" + name + '\'' +
                ", headers=" + headers +
                ", baseUrl='" + baseUrl + '\'' +
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

    @Builder(toBuilder = true)
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
