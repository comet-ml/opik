package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnnotationQueueUpdate(
        @JsonView( {
                AnnotationQueue.View.Update.class}) @NotBlank String name,

        @JsonView({
                AnnotationQueue.View.Update.class}) @Nullable @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String description,

        @JsonView({
                AnnotationQueue.View.Update.class}) @Nullable @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String instructions,

        @JsonView({AnnotationQueue.View.Update.class}) @NotNull Boolean commentsEnabled,

        @JsonView({AnnotationQueue.View.Update.class}) @NotNull List<UUID> feedbackDefinitions){
}
