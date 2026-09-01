package com.comet.opik.infrastructure.redis;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    /** A mapper whose string limit is small enough to breach without allocating megabytes. */
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
        var payloadBytes = buf.readableBytes();

        var decoded = codecWithSmallStringLimit().getMapValueDecoder().decode(buf, null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
        var undecodable = (UndecodableStreamMessage) decoded;
        assertThat(undecodable.payloadBytes()).isEqualTo(payloadBytes);
        assertThat(undecodable.cause())
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
    @Test
    @DisplayName("the shipped JAVA codec tolerates an undecodable field name too")
    void shippedJavaCodecToleratesUndecodableMapKey() throws IOException {
        var notAnLz4Frame = json("plainly not an LZ4 frame");

        var decoded = RedisStreamCodec.JAVA.getCodec().getMapKeyDecoder().decode(notAnLz4Frame, null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
    }

    /**
     * The drain branch: a decoder that consumes part of the buffer and then throws must still leave it
     * fully consumed, and the reported size must be the pre-decode size, not what is left.
     */
    @Test
    @DisplayName("a partially consumed buffer is drained and the pre-decode size reported")
    void partiallyConsumedBufferIsDrained() throws IOException {
        var buf = json("0123456789");
        var payloadBytes = buf.readableBytes();
        Codec partialReader = RedisStreamCodec.faultTolerant(new StubCodec((b, state) -> {
            b.skipBytes(4);
            throw new IllegalStateException("failed after consuming 4 bytes");
        }));

        var decoded = partialReader.getMapValueDecoder().decode(buf, null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
        assertThat(((UndecodableStreamMessage) decoded).payloadBytes()).isEqualTo(payloadBytes);
        assertThat(buf.isReadable()).isFalse();
    }

    /**
     * The boundary of the no-throw contract. An {@link Error} is deliberately not absorbed: an
     * OutOfMemoryError while Jackson materializes a multi-megabyte String is the plausible one on this
     * path, and a JVM in that state should not have its failure filed as a routine per-message drop.
     */
    @Test
    @DisplayName("an Error still propagates -- only Exception is absorbed")
    void errorStillPropagates() {
        Codec throwsError = RedisStreamCodec.faultTolerant(new StubCodec((b, state) -> {
            throw new OutOfMemoryError("simulated");
        }));

        assertThatThrownBy(() -> throwsError.getMapValueDecoder().decode(json("{}"), null))
                .isInstanceOf(OutOfMemoryError.class);
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
