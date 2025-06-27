package com.comet.opik.api;

import com.comet.opik.infrastructure.ratelimit.RateEventContainer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Valid public record FeedbackScoreBatch(
        @NotNull @Size(min = 1, max = 1000) List<@Valid FeedbackScoreBatchItem> scores) implements RateEventContainer {

    public static class View {

        public interface Tracing {
        }

        public interface Thread extends Default {
        }
    }

    @Override
    public long eventCount() {
        return scores.size();
    }
}
