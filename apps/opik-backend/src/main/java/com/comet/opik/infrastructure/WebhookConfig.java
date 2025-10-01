package com.comet.opik.infrastructure;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.redisson.client.codec.Codec;
import org.redisson.codec.CompositeCodec;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.codec.LZ4CodecV2;

import java.util.concurrent.TimeUnit;

@Data
public class WebhookConfig implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";

    private static final CompositeCodec CODEC = new CompositeCodec(new LZ4CodecV2(),
            new JsonJacksonCodec(JsonUtils.MAPPER));

    @Valid @JsonProperty
    private boolean enabled = false;

    @Valid @NotBlank @JsonProperty
    private String streamName = "webhook-events";

    @Valid @NotBlank @JsonProperty
    private String consumerGroupName = "webhook-consumers";

    @Valid @JsonProperty
    @Min(1) @Max(100) private int consumerBatchSize = 10;

    @Valid @JsonProperty
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration poolingInterval = Duration.seconds(1);

    // Webhook-specific configuration
    @Valid @JsonProperty
    @Min(1) @Max(10) private int maxRetries = 3;

    @Valid @JsonProperty
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration initialRetryDelay = Duration.milliseconds(500);

    @Valid @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration maxRetryDelay = Duration.seconds(30);

    @Valid @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration requestTimeout = Duration.seconds(10);

    @Valid @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration connectionTimeout = Duration.seconds(5);

    @Override
    @JsonIgnore
    public Codec getCodec() {
        return CODEC;
    }
}
