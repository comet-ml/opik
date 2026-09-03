package com.comet.opik.infrastructure.redis;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;
import org.redisson.codec.JsonJacksonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decode side of {@link RedisStreamCodec#JAVA} must never throw: a throw lands inside Redisson's
 * {@code CommandDecoder}, below {@code BaseRedisSubscriber} and before any {@code StreamMessageId} exists,
 * so the entry can never be acked or removed and the stream wedges permanently (OPIK-8164).
 * <p>
 * Container-free on purpose -- this is decoder behaviour, and the wedge it prevents needs no Redis to
 * demonstrate.
 */
@DisplayName("Fault-tolerant stream codec")
class FaultTolerantStreamCodecTest {

    private static final int SMALL_STRING_LIMIT = 64;

    /**
     * A mapper whose string limit is small enough to breach without allocating megabytes.
     * <p>
     * This is the same code path as the shipped codec's value decoder --
     * {@code faultTolerant(JsonJacksonCodec).getMapValueDecoder()} either way, since
     * {@code CompositeCodec} routes map values to {@code JsonJacksonCodec} here -- with only the
     * mapper's limit differing. Deliberately not driven through {@code RedisStreamCodec.JAVA} at its
     * real limit: the enum memoizes a copy of {@code JsonUtils}' mapper at build time, and
     * {@code RedisStreamCodecTest} calls {@code JsonUtils.configure} in the same JVM, so the effective
     * limit there is order-dependent and a test asserting it either skips or flakes. The real-limit
     * behaviour is recorded as measured evidence in {@code FaultTolerantCodec}'s javadoc instead.
     */
    private static Codec codecWithSmallStringLimit() {
        var mapper = new ObjectMapper();
        mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder().maxStringLength(SMALL_STRING_LIMIT).build());
        return RedisStreamCodec.faultTolerant(new JsonJacksonCodec(mapper));
    }

    private static ByteBuf json(String payload) {
        return Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a payload over the string limit decodes to a sentinel instead of throwing")
    void oversizedPayloadYieldsSentinel() throws IOException {
        var oversized = "\"%s\"".formatted("a".repeat(SMALL_STRING_LIMIT + 1));
        var buf = json(oversized);
        var encodedBytes = buf.readableBytes();

        var decoded = codecWithSmallStringLimit().getMapValueDecoder().decode(buf, null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
        var undecodable = (UndecodableStreamMessage) decoded;
        assertThat(undecodable.encodedBytes()).isEqualTo(encodedBytes);
        // The type matters, not just the text: this is the OPIK-8164 failure, and it is an ordinary
        // IOException, NOT an Error. Whether the Exception arm alone suffices for the incident turns on
        // exactly this -- see heapExhaustionDuringMaterializationIsAbsorbed for the case where it does not.
        assertThat(undecodable.cause())
                .isInstanceOf(StreamConstraintsException.class)
                .isNotInstanceOf(Error.class)
                .hasMessageContaining("maximum allowed");
        // The size is reported from the buffer, so it survives the failed decode consuming it.
        assertThat(buf.isReadable()).isFalse();
    }

    @Test
    @DisplayName("a well-formed payload still decodes normally")
    void wellFormedPayloadStillDecodes() throws IOException {
        var decoded = codecWithSmallStringLimit().getMapValueDecoder().decode(json("\"within limits\""), null);

        assertThat(decoded).isEqualTo("within limits");
    }

    @Test
    @DisplayName("malformed JSON decodes to a sentinel rather than throwing")
    void malformedJsonYieldsSentinel() throws IOException {
        var decoded = codecWithSmallStringLimit().getMapValueDecoder().decode(json("{not json"), null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
    }

    /**
     * The wire format must not move. Encoders are handed through untouched, so a pod on either build
     * writes bytes the other can read and a rolling upgrade is safe in both directions.
     */
    @Test
    @DisplayName("encoders are the delegate's own, so the wire format is unchanged")
    void encodersAreUntouched() {
        var delegate = new JsonJacksonCodec(new ObjectMapper());
        var tolerant = RedisStreamCodec.faultTolerant(delegate);

        assertThat(tolerant.getMapValueEncoder()).isSameAs(delegate.getMapValueEncoder());
        assertThat(tolerant.getMapKeyEncoder()).isSameAs(delegate.getMapKeyEncoder());
        assertThat(tolerant.getValueEncoder()).isSameAs(delegate.getValueEncoder());
        assertThat(tolerant.getClassLoader()).isSameAs(delegate.getClassLoader());
        // Decoders, by contrast, must NOT be pass-through -- including the map-key one, which is the
        // field-name path and was the remaining hole.
        assertThat(tolerant.getMapKeyDecoder()).isNotSameAs(delegate.getMapKeyDecoder());
        assertThat(tolerant.getMapValueDecoder()).isNotSameAs(delegate.getMapValueDecoder());
    }

    /**
     * The field-name path on the shipped codec resolves to {@code LZ4CodecV2} over Kryo5, not to the
     * JSON codec, so it is only covered because the wrapper goes around the {@link org.redisson.codec.CompositeCodec}
     * rather than around its value codec. A throw here would wedge exactly like the payload path.
     */
    /**
     * Pins the {@link org.redisson.codec.CompositeCodec} argument order behaviourally, rather than
     * leaving it as a bytecode claim in a javadoc that nothing checks.
     * <p>
     * Two reviewers have queried what {@code encodedBytes} actually measures, and the answer depends
     * entirely on this: the two-arg constructor is {@code (mapKeyCodec, mapValueCodec)}, so LZ4 sits on
     * the field-name path and payloads go through {@code JsonJacksonCodec} <em>uncompressed</em>. That
     * makes {@code encodedBytes} a decoded-scale number for the value path and a compressed one for the
     * key path. If anyone ever swaps those arguments -- which is the pre-existing fix this PR keeps
     * deferring -- this test fails and the {@code encodedBytes} contract has to be revisited with it.
     */
    @Test
    @DisplayName("map values are encoded as plain JSON and only field names are LZ4-framed")
    void compositeCodecPutsLz4OnTheKeyPathNotTheValuePath() throws IOException {
        var codec = RedisStreamCodec.JAVA.getCodec();

        var encodedValue = codec.getMapValueEncoder().encode("a-trace-payload");
        var encodedKey = codec.getMapKeyEncoder().encode("message");

        // A JSON-encoded String starts with a quote and is byte-for-byte readable.
        assertThat(encodedValue.toString(StandardCharsets.UTF_8)).isEqualTo("\"a-trace-payload\"");

        // The field name is not: LZ4CodecV2 prefixes a 4-byte decompressed-length header, so the first
        // bytes are a length rather than the text. Asserting it round-trips is what proves it is framed
        // rather than plain, without pinning LZ4's internal layout.
        assertThat(encodedKey.toString(StandardCharsets.UTF_8)).isNotEqualTo("message");
        assertThat(codec.getMapKeyDecoder().decode(encodedKey, null)).isEqualTo("message");
    }

    @Test
    @DisplayName("the shipped JAVA codec tolerates an undecodable field name too")
    void shippedJavaCodecToleratesUndecodableMapKey() throws IOException {
        // The first four bytes are LZ4CodecV2's decompressed-length header, which its decoder reads and
        // allocates. Kept small and plausible so this exercises an ordinary decompression failure on a
        // legitimately-sized declared length -- the Exception arm -- as distinct from
        // corruptedDeclaredLengthYieldsSentinel below, which covers the lengths that can reach the OOM
        // arm instead.
        var notAnLz4Frame = Unpooled.wrappedBuffer(
                new byte[]{0, 0, 0, 16, 'n', 'o', 't', '-', 'l', 'z', '4'});

        var decoded = RedisStreamCodec.JAVA.getCodec().getMapKeyDecoder().decode(notAnLz4Frame, null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
    }

    /**
     * The reason the catch covers {@link OutOfMemoryError} and not {@link Exception} alone.
     * <p>
     * {@code LZ4CodecV2$1.decode} allocates {@code newarray byte} of the declared length before it
     * validates the frame, so a corrupted field-name header can claim any 31-bit length. Measured
     * against the raw decoder, what that produces depends on the heap: negative lengths give
     * {@code NegativeArraySizeException}, large-but-allocatable lengths allocate and then give
     * {@code IOException}, and lengths beyond the heap give {@link OutOfMemoryError}. Only the last is
     * an {@link Error}, and which row a length lands in moves with {@code -Xmx} -- 1.8 GiB is an
     * {@code IOException} on a 9 GB heap and an {@link OutOfMemoryError} on a 1 GB container.
     * <p>
     * All three must yield the sentinel, because any of them escaping is the OPIK-8164 wedge reached
     * through a corrupted length header. {@code Integer.MAX_VALUE} is the case that actually exercises
     * the OOM arm on a large heap; the others exercise the Exception arm and are included so the
     * behaviour is pinned as heap-independent rather than accidentally uniform.
     */
    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE, 1_886_151_017, Integer.MAX_VALUE})
    @DisplayName("a corrupted LZ4 declared length yields the sentinel however it fails")
    void corruptedDeclaredLengthYieldsSentinel(int declaredLength) throws IOException {
        var buf = Unpooled.buffer()
                .writeInt(declaredLength)
                .writeBytes("frame-body".getBytes(StandardCharsets.UTF_8));

        var decoded = RedisStreamCodec.JAVA.getCodec().getMapKeyDecoder().decode(buf, null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
    }

    /**
     * The boundary of what is absorbed. {@link OutOfMemoryError} is in; every other {@link Error} is
     * not, so a {@link StackOverflowError} -- or an OOM from a legitimate multi-hundred-MB payload
     * document rather than a corrupted length -- still propagates.
     */
    @Test
    @DisplayName("an Error other than OutOfMemoryError still propagates")
    void nonOomErrorStillPropagates() {
        Codec throwsError = RedisStreamCodec.faultTolerant(new StubCodec((b, state) -> {
            throw new StackOverflowError("simulated");
        }));

        assertThatThrownBy(() -> throwsError.getMapValueDecoder().decode(json("{}"), null))
                .isInstanceOf(StackOverflowError.class);
    }

    /**
     * The drain branch: a decoder that consumes part of the buffer and then throws must still leave it
     * fully consumed, and the reported size must be the pre-decode size, not what is left.
     */
    @Test
    @DisplayName("a partially consumed buffer is drained and the pre-decode size reported")
    void partiallyConsumedBufferIsDrained() throws IOException {
        var buf = json("0123456789");
        var encodedBytes = buf.readableBytes();
        Codec partialReader = RedisStreamCodec.faultTolerant(new StubCodec((b, state) -> {
            b.skipBytes(4);
            throw new IllegalStateException("failed after consuming 4 bytes");
        }));

        var decoded = partialReader.getMapValueDecoder().decode(buf, null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
        assertThat(((UndecodableStreamMessage) decoded).encodedBytes()).isEqualTo(encodedBytes);
        assertThat(buf.isReadable()).isFalse();
    }

    /**
     * Why the {@link OutOfMemoryError} arm is not only about corrupted LZ4 lengths: it also covers the
     * PRIMARY payload path. A payload <em>under</em> {@code maxStringLength} but larger than the heap
     * OOMs inside Jackson's own String materialization, before any constraint is breached.
     * <p>
     * Measured, not reasoned: on a fork with {@code -Xmx64m}, a 12,000,000-character payload (well
     * under the 20,000,000 limit) through {@code RedisStreamCodec.JAVA}'s value decoder yields
     * {@code SENTINEL, cause=java.lang.OutOfMemoryError, isError=true}. This matters in production,
     * where {@code maxStringLength} is 100 MB and {@code consumerBatchSize} is 10 -- ten concurrent
     * materializations of a large trace is exactly this case, and without this arm the OOM escapes into
     * {@code CommandDecoder} and wedges the stream.
     * <p>
     * Driven here through a stub rather than a real allocation, because the surefire JVM's heap is far
     * too large to provoke it and forcing a small {@code -Xmx} for one test would slow every other one.
     * The real-heap reproduction above is the evidence; this pins the arm.
     */
    @Test
    @DisplayName("an OutOfMemoryError from a decoder is absorbed into the sentinel")
    void heapExhaustionDuringMaterializationIsAbsorbed() throws IOException {
        Codec throwsOom = RedisStreamCodec.faultTolerant(new StubCodec((b, state) -> {
            throw new OutOfMemoryError("simulated");
        }));

        var decoded = throwsOom.getMapValueDecoder().decode(json("{}"), null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
        assertThat(((UndecodableStreamMessage) decoded).cause()).isInstanceOf(OutOfMemoryError.class);
    }

    /** Minimal codec whose every decoder is the supplied one, for driving the wrapper directly. */
    private record StubCodec(Decoder<Object> decoder) implements Codec {
        @Override
        public Decoder<Object> getMapValueDecoder() {
            return decoder;
        }

        @Override
        public Encoder getMapValueEncoder() {
            return null;
        }

        @Override
        public Decoder<Object> getMapKeyDecoder() {
            return decoder;
        }

        @Override
        public Encoder getMapKeyEncoder() {
            return null;
        }

        @Override
        public Decoder<Object> getValueDecoder() {
            return decoder;
        }

        @Override
        public Encoder getValueEncoder() {
            return null;
        }

        @Override
        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }
    }

    @Test
    @DisplayName("the shipped JAVA codec is fault tolerant")
    void shippedJavaCodecIsFaultTolerant() throws IOException {
        // Guards the wiring, not the wrapper: the enum must hand the stream a decoder that cannot throw.
        var decoded = RedisStreamCodec.JAVA.getCodec().getMapValueDecoder().decode(json("{not json"), null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
    }
}
