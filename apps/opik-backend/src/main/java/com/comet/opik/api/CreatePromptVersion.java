package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreatePromptVersion(@JsonView( {
        PromptVersion.View.Detail.class}) @NotBlank String name,
        @JsonView({PromptVersion.View.Detail.class}) @NotNull @Valid PromptVersion version,
        @JsonView({PromptVersion.View.Detail.class}) TemplateStructure templateStructure){
}
