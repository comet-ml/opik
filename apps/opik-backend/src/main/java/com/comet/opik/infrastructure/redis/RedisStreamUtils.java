package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.StreamConfiguration;
import lombok.experimental.UtilityClass;
import org.redisson.api.stream.StreamAddArgs;

@UtilityClass
public class RedisStreamUtils {

    public static <K, V> StreamAddArgs<K, V> buildAddArgs(K key, V value, StreamConfiguration config) {
        return StreamAddArgs.<K, V>entry(key, value)
                .trimNonStrict()
                .maxLen(config.getStreamMaxLen())
                .limit(config.getStreamTrimLimit());
    }
}
