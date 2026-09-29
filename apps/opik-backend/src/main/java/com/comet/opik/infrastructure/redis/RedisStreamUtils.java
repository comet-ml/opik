package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.StreamConfiguration;
import lombok.experimental.UtilityClass;
import org.redisson.api.stream.StreamAddArgs;

@UtilityClass
public class RedisStreamUtils {

    public static <K, V> StreamAddArgs<K, V> buildAddArgs(K key, V value, StreamConfiguration config) {
        return buildAddArgs(key, value, config.getStreamMaxLen(), config.getStreamTrimLimit());
    }

    /**
     * For a producer whose configuration is not a {@link StreamConfiguration} — trimming needs only these two
     * values, and the rest of that interface is the consumer's half.
     */
    public static <K, V> StreamAddArgs<K, V> buildAddArgs(K key, V value, int streamMaxLen, int streamTrimLimit) {
        return StreamAddArgs.<K, V>entry(key, value)
                .trimNonStrict()
                .maxLen(streamMaxLen)
                .limit(streamTrimLimit);
    }
}
