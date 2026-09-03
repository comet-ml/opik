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
        var encodedBytes = buf.readableBytes();

        var decoded = codecWithSmallStringLimit().getMapValueDecoder().decode(buf, null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
        var undecodable = (UndecodableStreamMessage) decoded;
        assertThat(undecodable.encodedBytes()).isEqualTo(encodedBytes);
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
        // The first four bytes are LZ4CodecV2's decompressed-length header, which its decoder reads and
        // allocates. Kept explicit and small (well under MAX_MAP_KEY_DECODED_LENGTH, see the boundary
        // tests below) so this exercises the OUTER FaultTolerantCodec.tolerant() catching an ordinary
        // decompression failure on a legitimately-sized declared length, distinct from
        // mapKeyDecoderRejectsImplausibleDeclaredLength below, which never reaches a decoder at all.
        var notAnLz4Frame = Unpooled.wrappedBuffer(
                new byte[]{0, 0, 0, 16, 'n', 'o', 't', '-', 'l', 'z', '4'});

        var decoded = RedisStreamCodec.JAVA.getCodec().getMapKeyDecoder().decode(notAnLz4Frame, null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
    }

    /**
     * The vulnerability this guards: {@code LZ4CodecV2.decode} reads its 4-byte declared length and
     * hands it straight to {@code ByteBufAllocator.DEFAULT.buffer(...)} with no bound of its own
     * (verified against Redisson 4.7.0 bytecode). A corrupted or truncated frame can therefore claim
     * an arbitrary 31-bit length; left unguarded, an {@link OutOfMemoryError} from that allocation
     * would be an {@link Error} the wrapper does not absorb, reproducing the OPIK-8164 wedge through a
     * corrupted header instead of an oversized string.
     * <p>
     * "plai" -- the first four bytes of the free-text buffer this test replaced in an earlier revision
     * -- decodes as 1,886,151,017: a ~1.8 GiB request. This test proves that length is rejected before
     * any allocation is attempted, not merely that it happens not to OOM on this runner.
     */
    @Test
    @DisplayName("an implausible declared length is rejected before LZ4CodecV2 ever allocates")
    void mapKeyDecoderRejectsImplausibleDeclaredLength() throws IOException {
        var declaredLength = "plai".getBytes(StandardCharsets.US_ASCII); // 1,886,151,017
        var buf = Unpooled.wrappedBuffer(declaredLength, "n-frame-body".getBytes(StandardCharsets.UTF_8));

        var decoded = RedisStreamCodec.JAVA.getCodec().getMapKeyDecoder().decode(buf, null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
        assertThat(((UndecodableStreamMessage) decoded).cause())
                .hasMessageContaining("sanity ceiling");
    }

    @Test
    @DisplayName("a negative declared length is rejected the same way")
    void mapKeyDecoderRejectsNegativeDeclaredLength() throws IOException {
        var buf = Unpooled.buffer().writeInt(-1).writeBytes("garbage".getBytes(StandardCharsets.UTF_8));

        var decoded = RedisStreamCodec.JAVA.getCodec().getMapKeyDecoder().decode(buf, null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
        assertThat(((UndecodableStreamMessage) decoded).cause())
                .hasMessageContaining("sanity ceiling");
    }

    /**
     * The boundary: a declared length exactly at the ceiling must NOT be rejected pre-allocation --
     * it has to reach the real decoder, whatever happens to it after that. Distinguished from the
     * over-ceiling cases by NOT carrying the "sanity ceiling" message.
     */
    @Test
    @DisplayName("a declared length exactly at the ceiling is not rejected pre-allocation")
    void mapKeyDecoderDoesNotRejectDeclaredLengthAtTheCeiling() throws IOException {
        var buf = Unpooled.buffer()
                .writeInt(RedisStreamCodec.MAX_MAP_KEY_DECODED_LENGTH)
                .writeBytes("not a real lz4 frame body".getBytes(StandardCharsets.UTF_8));

        var decoded = RedisStreamCodec.JAVA.getCodec().getMapKeyDecoder().decode(buf, null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
        assertThat(((UndecodableStreamMessage) decoded).cause().getMessage())
                .doesNotContain("sanity ceiling");
    }

    @Test
    @DisplayName("a buffer too short to carry a length header is not rejected by this guard")
    void mapKeyDecoderSkipsTheBoundCheckOnATooShortBuffer() throws IOException {
        var buf = Unpooled.wrappedBuffer(new byte[]{1, 2});

        var decoded = RedisStreamCodec.JAVA.getCodec().getMapKeyDecoder().decode(buf, null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
        assertThat(((UndecodableStreamMessage) decoded).cause().getMessage())
                .doesNotContain("sanity ceiling");
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
