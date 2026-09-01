package com.comet.opik.infrastructure.redis;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;
import org.redisson.codec.CompositeCodec;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.codec.LZ4CodecV2;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.Supplier;

@AllArgsConstructor
@Getter
public enum RedisStreamCodec {
    JAVA(Constants.JAVA, Suppliers.memoize(() -> new CompositeCodec(new LZ4CodecV2(),
            faultTolerant(new JsonJacksonCodec(buildStreamMapper()))))),
    JSON(Constants.JSON, () -> StringCodec.INSTANCE);

    /**
     * Returns an {@link ObjectMapper} dedicated to the Redis stream codec, registering
     * {@link LenientUUIDDeserializer} so {@link UUID} fields parse from both plain-string
     * and Jackson polymorphic {@code As.WRAPPER_ARRAY} shapes. Old stuck messages produced
     * by previous opik-backend versions used one shape, the current version may produce
     * the other; tolerating both keeps {@code XAUTOCLAIM} from looping on a decode error
     * every {@code pending-message-duration} window.
     * <p>
     * Unknown properties are also ignored so that, during a rolling upgrade, a consumer on the older
     * version can still decode messages produced by a newer version that added a field to the payload
     * (e.g. a new {@code workspace_name}); otherwise the decode error would make {@code XAUTOCLAIM} loop
     * on the message indefinitely.
     */
    @VisibleForTesting
    static ObjectMapper buildStreamMapper() {
        ObjectMapper mapper = JsonUtils.getMapper().copy();
        mapper.registerModule(new SimpleModule().addDeserializer(UUID.class, LenientUUIDDeserializer.INSTANCE));
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    /**
     * Wraps a codec so a payload it cannot decode yields an {@link UndecodableStreamMessage} instead of
     * throwing.
     * <p>
     * A throw here happens inside Redisson's {@code CommandDecoder}, below {@code BaseRedisSubscriber} and
     * before any {@code StreamMessageId} exists, so the entry can never be acked or removed and the stream
     * wedges permanently (OPIK-8164). Returning a value keeps the failure in the normal message flow, where
     * the id is known and the entry can be dropped, counted and logged.
     * <p>
     * Encoders are untouched, so the wire format is byte-identical and a rolling upgrade is safe in both
     * directions: a pod on either build writes what the other can read.
     */
    @VisibleForTesting
    static Codec faultTolerant(Codec delegate) {
        return new FaultTolerantCodec(delegate);
    }

    /**
     * Delegates everything except decoding, which cannot throw.
     * <p>
     * Both the map-value and plain-value decoders are wrapped: streams decode the payload through
     * {@code getMapValueDecoder}, but {@link CompositeCodec} may reach for either depending on the
     * operation, and a decoder that throws on one path defeats the purpose.
     */
    @RequiredArgsConstructor
    private static final class FaultTolerantCodec implements Codec {

        private final Codec delegate;

        private static Decoder<Object> tolerant(Decoder<Object> decoder) {
            return (buf, state) -> {
                int payloadBytes = buf.readableBytes();
                try {
                    return decoder.decode(buf, state);
                } catch (Exception decodeFailure) {
                    // Consume whatever the failed decode left behind, so the buffer looks the same to
                    // Redisson as it would after a successful decode.
                    if (buf.isReadable()) {
                        buf.skipBytes(buf.readableBytes());
                    }
                    return new UndecodableStreamMessage(payloadBytes, decodeFailure);
                }
            };
        }

        @Override
        public Decoder<Object> getMapValueDecoder() {
            return tolerant(delegate.getMapValueDecoder());
        }

        @Override
        public Encoder getMapValueEncoder() {
            return delegate.getMapValueEncoder();
        }

        @Override
        public Decoder<Object> getMapKeyDecoder() {
            return delegate.getMapKeyDecoder();
        }

        @Override
        public Encoder getMapKeyEncoder() {
            return delegate.getMapKeyEncoder();
        }

        @Override
        public Decoder<Object> getValueDecoder() {
            return tolerant(delegate.getValueDecoder());
        }

        @Override
        public Encoder getValueEncoder() {
            return delegate.getValueEncoder();
        }

        @Override
        public ClassLoader getClassLoader() {
            return delegate.getClassLoader();
        }
    }

    private final String name;
    private final Supplier<Codec> codecSupplier;

    public Codec getCodec() {
        return codecSupplier.get();
    }

    public static RedisStreamCodec fromString(String configValue) {
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
