package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnnotationQueue(
        @JsonView( {
                AnnotationQueue.View.Public.class, AnnotationQueue.View.Write.class}) @Nullable UUID id,
        @JsonView({AnnotationQueue.View.Public.class, AnnotationQueue.View.Write.class}) @NotNull UUID projectId,
        @JsonView({AnnotationQueue.View.Public.class, AnnotationQueue.View.Write.class}) @NotBlank String name,
        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) String description,
        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) String instructions,
        @JsonView({AnnotationQueue.View.Public.class, AnnotationQueue.View.Write.class}) @NotNull AnnotationScope scope,
        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @Nullable Boolean commentsEnabled,
        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @Nullable List<UUID> feedbackDefinitions,
        @JsonView({
                AnnotationQueue.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String workspaceId,
        @JsonView({
                AnnotationQueue.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({
                AnnotationQueue.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({
                AnnotationQueue.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({
                AnnotationQueue.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

    @Getter
    @RequiredArgsConstructor
    public enum AnnotationScope {
        TRACE("trace"),
        THREAD("thread");

        @JsonValue
        private final String value;

        @JsonCreator
        public static AnnotationScope fromString(String value) {
            return Arrays.stream(values())
                    .filter(scope -> scope.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown annotation scope '%s'".formatted(value)));
        }
    }

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }
}
