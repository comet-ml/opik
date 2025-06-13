package com.comet.opik.infrastructure;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.redisson.client.codec.Codec;
import org.redisson.codec.CompositeCodec;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.codec.LZ4CodecV2;

import java.util.concurrent.TimeUnit;

@Data
public class TraceThreadConfig implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";

    private static final CompositeCodec CODEC = new CompositeCodec(new LZ4CodecV2(),
            new JsonJacksonCodec(JsonUtils.MAPPER));

    @Valid @NotBlank @JsonProperty
    private String streamName;

    @Valid @NotBlank @JsonProperty
    private String consumerGroupName;

    @Valid @JsonProperty
    @Min(1) private int consumerBatchSize;

    @Valid @JsonProperty
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration poolingInterval;

    @Valid @JsonProperty
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration timeoutToMarkThreadAsInactive;

    @Override
    public Codec getCodec() {
        return CODEC;
    }
}
