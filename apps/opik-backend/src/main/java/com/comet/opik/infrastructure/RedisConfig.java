package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.redis.RedisUrl;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.Data;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;

import java.util.Objects;

@Data
public class RedisConfig {

    @Valid @JsonProperty
    private String singleNodeUrl;

    public Config build() {
        Config config = new Config();

        var redisUrl = RedisUrl.parse(singleNodeUrl);

        Objects.requireNonNull(singleNodeUrl, "singleNodeUrl must not be null");

        config.useSingleServer()
                .setAddress(singleNodeUrl)
                .setDatabase(redisUrl.database());

        config.setCodec(new JsonJacksonCodec(JsonUtils.MAPPER));
        return config;
    }

}
