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
    JAVA(Constants.JAVA, Suppliers.memoize(() -> faultTolerant(new CompositeCodec(new LZ4CodecV2(),
            new JsonJacksonCodec(buildStreamMapper()))))),
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
    public static Codec faultTolerant(Codec delegate) {
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
     * The catch covers {@link Exception} plus {@link OutOfMemoryError}, and nothing else. Both arms are
     * load-bearing, for different failures on different paths, and the OOM arm is <em>not</em> only a
     * defence against corrupted frames — it also covers the primary payload path. All four rows below
     * were measured through {@link #JAVA}'s own decoders, not reasoned about:
     *
     * <table border="1">
     * <caption>Observed failure modes</caption>
     * <tr><th>Input</th><th>Path</th><th>Result</th><th>Arm</th></tr>
     * <tr><td>String over {@code maxStringLength}</td><td>value</td>
     *     <td>{@code StreamConstraintsException} — the literal OPIK-8164 failure</td><td>Exception</td></tr>
     * <tr><td>String under the limit, over the heap</td><td>value</td>
     *     <td>{@link OutOfMemoryError} inside Jackson's String materialization</td><td><b>OOM</b></td></tr>
     * <tr><td>Negative LZ4 declared length</td><td>field name</td>
     *     <td>{@code NegativeArraySizeException}</td><td>Exception</td></tr>
     * <tr><td>LZ4 declared length beyond the heap</td><td>field name</td>
     *     <td>{@link OutOfMemoryError} from {@code newarray byte}</td><td><b>OOM</b></td></tr>
     * </table>
     *
     * The second row is the one that makes the OOM arm matter in production rather than in theory.
     * {@code jacksonConfig.maxStringLength} ships at 100 MB, so a payload well inside the configured
     * limit can still exhaust a container heap while Jackson materializes it — and at
     * {@code consumerBatchSize: 10} ten such decodes run concurrently. Verified on a {@code -Xmx64m}
     * fork: a 12,000,000-character payload, comfortably under the 20,000,000 default limit, produced
     * {@code cause=java.lang.OutOfMemoryError}. Without this arm that Error escapes into
     * {@code CommandDecoder} with no {@code StreamMessageId} and wedges the stream — the same outcome
     * OPIK-8164 produced, reached by running out of heap rather than by breaching a limit.
     * <p>
     * Rows three and four are the LZ4 field-name path. {@code LZ4CodecV2$1.decode} reads a 4-byte
     * declared decompressed length and immediately does {@code newarray byte} of that size, building the
     * decompressor and validating the frame only afterwards (Redisson 4.7.0: {@code readInt} at 1,
     * {@code newarray} at 6, {@code BlockLZ4CompressorInputStream} at 24, {@code readFully} at 44). Which
     * of the two arms catches a given length depends on {@code -Xmx}: 1.8 GiB is an {@code IOException}
     * on a 9 GB heap and an {@link OutOfMemoryError} on a 1 GB container. Absorbing both makes the
     * behaviour heap-independent.
     * <p>
     * Two honest limits. The failing allocation is unreferenced the moment we return, so the JVM
     * recovers — but an allocation large enough to fail can starve a <em>different</em> thread, which
     * then throws where nothing catches it; and a size the heap can satisfy is still allocated in full
     * before it fails. Bounding the LZ4 declared length pre-allocation would narrow row four, and an
     * earlier revision did that; it was removed because the ceiling was an undocumented number resting
     * on the local observation that every field name here is the constant {@code "message"}. Row four
     * is better fixed by the {@link CompositeCodec} argument-order change, which takes LZ4 off the
     * field-name path entirely. Row two has no such escape: materializing a large document is the
     * legitimate work, so absorbing the OOM is the only option short of a size guard at publish time.
     * <p>
     * <h4>Is absorbing an {@link OutOfMemoryError} recoverable?</h4>
     *
     * For this failure shape, yes, and it was measured rather than assumed. On a {@code -Xmx64m} fork,
     * three consecutive rounds of "oversized payload, then an ordinary message" each absorbed the OOM
     * and then decoded the ordinary message correctly, with free heap stable across rounds — no
     * cumulative degradation. That holds because the array which failed to allocate was never
     * allocated, so the failure consumed nothing, and the oversized buffer is unreferenced the moment
     * this method returns.
     * <p>
     * The operational comparison is the part that settles it. The shipped JVM options
     * ({@code -XX:+UseG1GC -XX:MaxRAMPercentage=80.0} in the helm chart) do <em>not</em> include
     * {@code -XX:+ExitOnOutOfMemoryError}, so the process already survives an OOM today. Not absorbing
     * therefore does not buy a clean restart — it buys a still-running pod with a permanently wedged
     * stream, which is strictly worse than dropping one message. Should the team ever add
     * {@code ExitOnOutOfMemoryError}, the JVM exits at throw time and this arm simply becomes
     * unreachable; nothing here depends on it staying absent.
     * <p>
     * What this does <em>not</em> claim: that a JVM under genuine heap exhaustion is healthy. If the
     * heap is being exhausted by other work, this arm will absorb an OOM that was a symptom rather than
     * a cause, and keep consuming — masking it. The recovery measured above is single-threaded and
     * proves the decode path, not a loaded service; an allocation large enough to fail can still starve
     * a different thread, which throws where nothing catches it. The counter to that is a size guard at
     * publish time, which is what {@code onlineScoring.dropOversizedPayloads} does, not anything this
     * codec can do on read.
     * <p>
     * No other {@link Error} is absorbed — a {@link StackOverflowError} still propagates.
     */
    @RequiredArgsConstructor
    private static final class FaultTolerantCodec implements Codec {

        private final Codec delegate;

        private static Decoder<Object> tolerant(Decoder<Object> decoder) {
            return (buf, state) -> {
                int encodedBytes = buf.readableBytes();
                try {
                    return decoder.decode(buf, state);
                } catch (Exception | OutOfMemoryError decodeFailure) {
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
