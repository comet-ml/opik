package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PromptVersionLink(
        @JsonView( {
                Prompt.View.Public.class, Prompt.View.Detail.class}) UUID promptVersionId,
        @JsonView({Prompt.View.Public.class, Prompt.View.Detail.class}) String commit,
        @JsonView({Prompt.View.Public.class, Prompt.View.Detail.class}) @Nullable Prompt prompt){
}
