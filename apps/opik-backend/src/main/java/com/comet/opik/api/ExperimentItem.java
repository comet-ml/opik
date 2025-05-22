package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExperimentItem(
        @JsonView( {
                ExperimentItem.View.Public.class, ExperimentItem.View.Write.class}) UUID id,
        @JsonView({ExperimentItem.View.Public.class, ExperimentItem.View.Write.class}) @NotNull UUID experimentId,
        @JsonView({ExperimentItem.View.Public.class, ExperimentItem.View.Write.class}) @NotNull UUID datasetItemId,
        @JsonView({ExperimentItem.View.Public.class, ExperimentItem.View.Write.class}) @NotNull UUID traceId,
        @JsonView({
                ExperimentItem.View.Compare.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY, implementation = JsonListString.class) JsonNode input,
        @JsonView({
                ExperimentItem.View.Compare.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY, implementation = JsonListString.class) JsonNode output,
        @JsonView({
                ExperimentItem.View.Compare.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<FeedbackScore> feedbackScores,
        @JsonView({
                ExperimentItem.View.Compare.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<Comment> comments,
        @JsonView({
                ExperimentItem.View.Compare.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) BigDecimal totalEstimatedCost,
        @JsonView({
                ExperimentItem.View.Compare.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Double duration,
        @JsonView({
                ExperimentItem.View.Compare.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Map<String, Long> usage,
        @JsonView({
                ExperimentItem.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({
                ExperimentItem.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({
                ExperimentItem.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({
                ExperimentItem.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

    public static class View {
        public static class Write {
        }

        public static class Public extends DatasetItem.View.Public {
        }

        public static class Compare extends Public {
        }
    }
}
