package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Data
public class OnlineScoringConfig {

    public static final String PAYLOAD_FIELD = "message";

    @Valid @JsonProperty
    private String consumerGroupName;

    @Valid @JsonProperty
    @Min(1) private int consumerBatchSize;

    @Valid @JsonProperty
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration poolingInterval;

    @JsonProperty
    private List<StreamConfiguration> streams;

    @Data
    public static class StreamConfiguration {
        @JsonProperty
        private String scorer;

        @JsonProperty
        private String streamName;

        @JsonProperty
        private String codec;

        @JsonProperty
        @Min(1) private Integer consumerBatchSize;

        @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        private Duration poolingInterval;
    }
}
