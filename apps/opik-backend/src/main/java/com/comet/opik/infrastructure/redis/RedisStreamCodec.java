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
    JAVA(Constants.JAVA, Suppliers.memoize(() -> faultTolerant(new CompositeCodec(
            lengthBoundedLz4Codec(new LZ4CodecV2()), new JsonJacksonCodec(buildStreamMapper()))))),
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
     * Delegates encoding untouched; every decoder is wrapped so no decode path can throw.
     * <p>
     * All three are wrapped deliberately, because each is a distinct wire path and a throw on any of
     * them lands in {@code CommandDecoder} with no {@code StreamMessageId}:
     * <ul>
     * <li>{@code getMapValueDecoder} — the payload. This is the OPIK-8164 path.</li>
     * <li>{@code getMapKeyDecoder} — the entry's field name. On {@link #JAVA} this resolves to
     * {@code LZ4CodecV2} over Kryo5, and both LZ4 frame validation and Kryo deserialization throw on
     * corruption or a format skew across a version bump. This is why the wrapper goes <em>around</em>
     * the {@link CompositeCodec} rather than around its value codec: the composite routes the map-key
     * path to its {@code mapKeyCodec}, so wrapping the inner JSON codec would never have covered it.
     * Field names are a fixed constant written only by Opik publishers, so a failure here is remote —
     * but its blast radius is identical to the incident. A sentinel key misses the payload-field
     * lookup, which the null-payload guard in {@code BaseRedisSubscriber.processMessage} then retires
     * like any other undecodable entry.</li>
     * <li>{@code getValueDecoder} — plain-value reads. Not reachable through {@link #JAVA}: the
     * two-arg {@link CompositeCodec} constructor leaves {@code valueCodec} null, so
     * {@code CompositeCodec.getValueDecoder()} throws {@link NullPointerException} while <em>obtaining</em>
     * the decoder, which is before any wrapper can intervene. Wrapped anyway because this class is a
     * general wrapper, not a JAVA-specific one, and a three-arg rewiring would otherwise silently
     * reopen the hole.</li>
     * </ul>
     * The catch is {@link Exception}, not {@link Throwable}: an {@link Error} — an
     * {@link OutOfMemoryError} while Jackson materializes a multi-megabyte String is the plausible one
     * here — still propagates, because a JVM in that state should not have its failure recorded as a
     * routine per-message drop.
     */
    /**
     * Ceiling on a field name's declared LZ4-decompressed length, checked before {@link LZ4CodecV2}
     * ever allocates for it.
     * <p>
     * {@code LZ4CodecV2.decode} reads a 4-byte declared length straight off the wire and hands it to
     * {@code ByteBufAllocator.DEFAULT.buffer(declaredLength)} with no bound -- verified against
     * Redisson 4.7.0 bytecode: {@code readInt()} then {@code buffer(int)}, before Kryo5 or
     * {@link FaultTolerantCodec} is ever consulted. A corrupted or truncated frame can therefore claim
     * any 31-bit length, and the resulting {@link OutOfMemoryError} -- or, under a Netty pool with a
     * configured direct-memory cap, the smaller {@code OutOfDirectMemoryError} -- is an {@link Error},
     * which {@link FaultTolerantCodec} deliberately does not absorb (an OOM from a legitimate
     * multi-hundred-MB document is meant to propagate). Left unguarded this reproduces the OPIK-8164
     * wedge through a corrupted length header instead of an oversized string: the allocation throws
     * before any {@code StreamMessageId} exists, so the entry can never be acked, retried or removed.
     * <p>
     * On this codec {@code LZ4CodecV2} is used only for the map-KEY (field-name) path -- see the
     * {@link CompositeCodec} arguments above -- and every stream field name in this codebase is the
     * seven-character constant {@code "message"} (e.g. {@code OnlineScoringConfig.PAYLOAD_FIELD}). 4 KB
     * is generous by three orders of magnitude, not a tight fit to today's constant: rejecting above it
     * costs nothing, because no legitimate field name will ever approach it.
     */
    @VisibleForTesting
    static final int MAX_MAP_KEY_DECODED_LENGTH = 4096;

    private static Codec lengthBoundedLz4Codec(LZ4CodecV2 lz4) {
        return new LengthBoundedLz4Codec(lz4);
    }

    /**
     * Rejects an implausible declared length before {@code LZ4CodecV2} ever allocates for it. See
     * {@link #MAX_MAP_KEY_DECODED_LENGTH} for the vulnerability and why the bound is safe.
     * <p>
     * Deliberately narrow rather than folded into {@link FaultTolerantCodec}'s generic wrapper: this
     * class assumes a specific wire layout (a 4-byte declared-length header), which only holds for
     * {@link LZ4CodecV2}. {@link FaultTolerantCodec} stays codec-agnostic on purpose -- its own javadoc
     * says as much -- so a peek this specific does not belong inside it.
     * <p>
     * Both {@code getMapKeyDecoder} and {@code getValueDecoder} are bounded, not just the one this
     * codec's wiring exercises ({@code getMapKeyDecoder}, which {@code BaseCodec} falls through to
     * {@code getValueDecoder} for): {@link LZ4CodecV2} could be composed as a value codec elsewhere,
     * and the same allocation is reachable through either accessor.
     */
    @RequiredArgsConstructor
    private static final class LengthBoundedLz4Codec implements Codec {

        private final LZ4CodecV2 delegate;

        private static Decoder<Object> bounded(Decoder<Object> decoder) {
            return (buf, state) -> {
                if (buf.readableBytes() >= Integer.BYTES) {
                    int declaredLength = buf.getInt(buf.readerIndex());
                    if (declaredLength < 0 || declaredLength > MAX_MAP_KEY_DECODED_LENGTH) {
                        int encodedBytes = buf.readableBytes();
                        buf.skipBytes(encodedBytes);
                        return UndecodableStreamMessage.builder()
                                .encodedBytes(encodedBytes)
                                .cause(new IllegalStateException(
                                        "LZ4 frame declares a %d-byte decompressed length, over the %d-byte "
                                                + "sanity ceiling for a stream field name"
                                                        .formatted(declaredLength, MAX_MAP_KEY_DECODED_LENGTH)))
                                .build();
                    }
                }
                return decoder.decode(buf, state);
            };
        }

        @Override
        public Decoder<Object> getMapValueDecoder() {
            return delegate.getMapValueDecoder();
        }

        @Override
        public Encoder getMapValueEncoder() {
            return delegate.getMapValueEncoder();
        }

        @Override
        public Decoder<Object> getMapKeyDecoder() {
            return bounded(delegate.getMapKeyDecoder());
        }

        @Override
        public Encoder getMapKeyEncoder() {
            return delegate.getMapKeyEncoder();
        }

        @Override
        public Decoder<Object> getValueDecoder() {
            return bounded(delegate.getValueDecoder());
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

    @RequiredArgsConstructor
    private static final class FaultTolerantCodec implements Codec {

        private final Codec delegate;

        private static Decoder<Object> tolerant(Decoder<Object> decoder) {
            return (buf, state) -> {
                int encodedBytes = buf.readableBytes();
                try {
                    return decoder.decode(buf, state);
                } catch (Exception decodeFailure) {
                    // Belt-and-braces: Redisson hands us a bounded readSlice and never inspects its
                    // reader index afterwards, so this is not required for framing. Drained anyway so
                    // the wrapper stays correct if it is ever handed an unsliced buffer.
                    if (buf.isReadable()) {
                        buf.skipBytes(buf.readableBytes());
                    }
                    return UndecodableStreamMessage.builder()
                            .encodedBytes(encodedBytes)
                            .cause(decodeFailure)
                            .build();
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
            return tolerant(delegate.getMapKeyDecoder());
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
