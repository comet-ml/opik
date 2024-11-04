package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Prompt(
        @JsonView( {
                Prompt.View.Public.class, Prompt.View.Write.class, Prompt.View.Detail.class}) UUID id,
        @JsonView({Prompt.View.Public.class, Prompt.View.Write.class, Prompt.View.Detail.class,
                Prompt.View.Updatable.class}) @NotBlank String name,
        @JsonView({Prompt.View.Public.class,
                Prompt.View.Write.class,
                Prompt.View.Detail.class,
                Prompt.View.Updatable.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String description,
        @JsonView({
                Prompt.View.Write.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Nullable String template,
        @JsonView({Prompt.View.Public.class,
                Prompt.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({Prompt.View.Public.class,
                Prompt.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({Prompt.View.Public.class,
                Prompt.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({Prompt.View.Public.class,
                Prompt.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy,
        @JsonView({Prompt.View.Public.class,
                Prompt.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Long versionCount,
        @JsonView({
                Prompt.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable PromptVersion latestVersion){

    public static class View {
        public static class Write {
        }

        public static class Public {
        }

        public static class Detail {
        }

        public static class Updatable {
        }
    }

    @Builder
    public record PromptPage(
            @JsonView( {
                    Prompt.View.Public.class}) int page,
            @JsonView({Prompt.View.Public.class}) int size,
            @JsonView({Prompt.View.Public.class}) long total,
            @JsonView({Prompt.View.Public.class}) List<Prompt> content)
            implements
                Page<Prompt>{

        public static Prompt.PromptPage empty(int page) {
            return new Prompt.PromptPage(page, 0, 0, List.of());
        }
    }
}
