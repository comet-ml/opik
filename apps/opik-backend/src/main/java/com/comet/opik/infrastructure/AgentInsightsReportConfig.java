package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.redis.RedisStreamCodec;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.redisson.client.codec.Codec;

import java.util.concurrent.TimeUnit;

@Data
public class AgentInsightsReportConfig implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";

    // Platform endpoint that triggers report generation on the Ollie pod (OPIK-6854); defaults to the
    // react service, like the auth service's reactService URL.
    @Valid @NotBlank @JsonProperty
    private String triggerUrl = "http://react-svc:8080/opik/ollie/generate-agent-insights";

    // Quartz cron expression (UTC) for the daily sweep; scheduled programmatically so it is env-configurable.
    @Valid @NotBlank @JsonProperty
    private String schedule = "0 5 0 * * ?";

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    @MaxDuration(value = 1, unit = TimeUnit.HOURS)
    private Duration jobTimeout = Duration.minutes(5);

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    @MaxDuration(value = 60, unit = TimeUnit.SECONDS)
    private Duration lockWaitTime = Duration.seconds(5);

    @Valid @NotBlank @JsonProperty
    private String streamName = "agent-insights-reports";

    @Valid @NotBlank @JsonProperty
    private String consumerGroupName = "agent-insights-report-consumers";

    @Valid @JsonProperty
    @Min(1) @Max(100) private int consumerBatchSize = 5;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration poolingInterval = Duration.seconds(1);

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
    private Duration longPollingDuration = Duration.seconds(5);

    @JsonProperty
    @Min(1) @Max(10) private int maxRetries = 3;

    @JsonProperty
    @Min(2) private int claimIntervalRatio = 2;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration pendingMessageDuration = Duration.minutes(5);

    @JsonProperty
    @Min(1000) @Max(10_000_000) private int streamMaxLen = 10000;

    @JsonProperty
    @Min(0) @Max(10_000) private int streamTrimLimit = 100;

    // Lazy codec creation so it picks up the configured JsonUtils mapper.
    @Override
    @JsonIgnore
    public Codec getCodec() {
        return RedisStreamCodec.JAVA.getCodec();
    }
}
