package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class OnlineScoringConfig {

    @Valid @JsonProperty
    private String consumerGroupName;

    @Valid @JsonProperty
    @Min(1) private int consumerBatchSize;

    @Valid @JsonProperty
    @Min(100) private int poolingIntervalMs;

    @Valid @JsonProperty
    private String llmAsJudgeStream;

    public static final String PAYLOAD_FIELD = "message";
}
