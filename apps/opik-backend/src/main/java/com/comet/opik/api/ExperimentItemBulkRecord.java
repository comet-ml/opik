package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.ExperimentItemBulkUpload.View;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExperimentItemBulkRecord(
        @JsonView( {
                View.ExperimentItemBulkWriteView.class}) @NotNull UUID datasetItemId,
        @JsonView({View.ExperimentItemBulkWriteView.class}) @Valid Trace trace,
        @JsonView({View.ExperimentItemBulkWriteView.class}) @Size(max = 100) List<@Valid Span> spans,
        @Size(max = 100) List<@Valid FeedbackScore> feedbackScores){
}
