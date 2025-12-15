package com.comet.opik.api;

import com.comet.opik.api.validation.CommitValidation;
import com.comet.opik.utils.ValidationUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import org.jdbi.v3.json.Json;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.PromptType.MUSTACHE;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PromptVersion(
        @JsonView( {
                Prompt.View.Detail.class,
                PromptVersion.View.Public.class,
                PromptVersion.View.Detail.class}) @Schema(description = "version unique identifier, generated if absent") UUID id,
        @JsonView({PromptVersion.View.Public.class,
                PromptVersion.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID promptId,
        @JsonView({Prompt.View.Detail.class,
                PromptVersion.View.Public.class,
                PromptVersion.View.Detail.class}) @Schema(description = "version short unique identifier, generated if absent. it must be 8 characters long", requiredMode = Schema.RequiredMode.NOT_REQUIRED, pattern = ValidationUtils.COMMIT_PATTERN) @CommitValidation String commit,
        @JsonView({PromptVersion.View.Public.class, Prompt.View.Detail.class,
                PromptVersion.View.Detail.class}) @NotBlank String template,
        @Json @JsonView({PromptVersion.View.Public.class, Prompt.View.Detail.class,
                PromptVersion.View.Detail.class}) JsonNode metadata,
        @JsonView({PromptVersion.View.Public.class, Prompt.View.Detail.class,
                PromptVersion.View.Detail.class}) PromptType type,
        @JsonView({PromptVersion.View.Public.class, Prompt.View.Detail.class,
                PromptVersion.View.Detail.class}) String changeDescription,
        @JsonView({PromptVersion.View.Public.class, Prompt.View.Detail.class,
                PromptVersion.View.Detail.class}) Set<@NotBlank String> tags,
        @JsonView({Prompt.View.Detail.class,
                PromptVersion.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Set<String> variables,
        @JsonView({Prompt.View.Detail.class,
                PromptVersion.View.Public.class,
                PromptVersion.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable TemplateStructure templateStructure,
        @JsonView({Prompt.View.Detail.class,
                PromptVersion.View.Public.class,
                PromptVersion.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({Prompt.View.Detail.class,
                PromptVersion.View.Public.class,
                PromptVersion.View.Detail.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy){

    public static class View {
        public static class Detail {
        }

        public static class Public {
        }
    }

    @Builder
    public record PromptVersionPage(
            @JsonView( {
                    PromptVersion.View.Public.class}) int page,
            @JsonView({PromptVersion.View.Public.class}) int size,
            @JsonView({PromptVersion.View.Public.class}) long total,
            @JsonView({PromptVersion.View.Public.class}) List<PromptVersion> content,
            @JsonView({PromptVersion.View.Public.class}) List<String> sortableBy)
            implements
                Page<PromptVersion>{

        public static PromptVersion.PromptVersionPage empty(int page, List<String> sortableBy) {
            return new PromptVersion.PromptVersionPage(page, 0, 0, List.of(), sortableBy);
        }
    }

    @Override
    public PromptType type() {
        return type == null ? MUSTACHE : type;
    }
}
