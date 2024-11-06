package com.comet.opik.api.sorting;

import com.comet.opik.api.Experiment;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SortingField(
        @JsonView( {
                Experiment.View.Public.class, Experiment.View.Write.class}) @NotBlank String field,
        @JsonView({
                Experiment.View.Public.class, Experiment.View.Write.class}) Direction direction){
}
