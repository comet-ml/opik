package com.comet.opik.api;

import com.comet.opik.api.validation.MaxRequestSize;
import com.comet.opik.infrastructure.ratelimit.RateEventContainer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;

/**
 * Request object for bulk uploading experiment items.
 * The total size of the request is limited to 4MB.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@MaxRequestSize // 4MB limit
public record ExperimentItemBulkUpload(
        @JsonView( {
                ExperimentItemBulkUpload.View.Write.class}) @NotBlank String experimentName,
        @JsonView({ExperimentItemBulkUpload.View.Write.class}) @NotBlank String datasetName,
        @JsonView({
                ExperimentItemBulkUpload.View.Write.class}) @NotNull @Size(min = 1, max = 250) @Valid List<ExperimentItemBulkRecord> items)
        implements
            RateEventContainer{

    public static class View {
        public static class Write extends Trace.View.Write {
        }
    }

    @Override
    public long eventCount() {
        // 1 event for the experiment item, and 1 event for each trace
        return (items.size() * 2L) + items.stream()
                // 1 event for each span
                .map(ExperimentItemBulkRecord::spans)
                .filter(CollectionUtils::isNotEmpty)
                .mapToLong(List::size)
                .sum()
                + items.stream()
                        // 1 event for each feedback score
                        .map(ExperimentItemBulkRecord::feedbackScores)
                        .filter(CollectionUtils::isNotEmpty)
                        .mapToLong(List::size)
                        .sum();
    }
}
