package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PromptVersion(
        @JsonView( {
                PromptVersion.View.Public.class,
                PromptVersion.View.Detail.class}) @Schema(description = "version unique identifier, generated if absent") UUID id,
        @JsonView({PromptVersion.View.Public.class,
                PromptVersion.View.Detail.class}) @Schema(description = "version short unique identifier, generated if absent") String commit,
        @JsonView({PromptVersion.View.Detail.class}) @NotNull String template,
        @JsonView({
                PromptVersion.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Set<String> variables,
        @JsonView({PromptVersion.View.Public.class,
                PromptVersion.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({PromptVersion.View.Public.class,
                PromptVersion.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy){

    public static class View {
        public static class Public {
        }

        public static class Detail {
        }
    }

    @Builder
    public record PromptVersionPage(
            @JsonView( {
                    PromptVersion.View.Public.class}) int page,
            @JsonView({PromptVersion.View.Public.class}) int size,
            @JsonView({PromptVersion.View.Public.class}) long total,
            @JsonView({PromptVersion.View.Public.class}) List<PromptVersion> content)
            implements
                Page<PromptVersion>{

        public static PromptVersion.PromptVersionPage empty(int page) {
            return new PromptVersion.PromptVersionPage(page, 0, 0, List.of());
        }
    }
}
