package com.comet.opik.api.resources.v1.events;

import com.comet.opik.utils.JsonUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.codec.LZ4CodecV2;

import java.util.Arrays;

@AllArgsConstructor
@Getter
public enum OnlineScoringCodecs {

    JAVA(Constants.JAVA, new CompositeCodec(new LZ4CodecV2(), new JsonJacksonCodec(JsonUtils.getMapper()))),
    JSON(Constants.JSON, StringCodec.INSTANCE);

    private final String name;
    private final Codec codec;

    public static OnlineScoringCodecs fromString(String configValue) {
        return Arrays.stream(values())
                .filter(v -> v.name.equalsIgnoreCase(configValue)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown codec name: " + configValue));
    }

    @UtilityClass
    public static class Constants {
        public static final String JAVA = "java";
        public static final String JSON = "json";
    }
}
