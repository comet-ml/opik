package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Data
public class OnlineScoringConfig {

    public static final String PAYLOAD_FIELD = "message";

    @JsonProperty
    @NotBlank private String consumerGroupName;

    @JsonProperty
    @Min(1) private int consumerBatchSize;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration poolingInterval;

    @JsonProperty
    @NotEmpty private List<@NotNull StreamConfiguration> streams;

    @Data
    public static class StreamConfiguration {
        @JsonProperty
        @NotBlank private String scorer;

        @JsonProperty
        @NotBlank private String streamName;

        @JsonProperty
        @NotBlank private String codec;

        @JsonProperty
        @Min(1) private Integer consumerBatchSize;

        @Valid @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        private Duration poolingInterval;
    }
}
