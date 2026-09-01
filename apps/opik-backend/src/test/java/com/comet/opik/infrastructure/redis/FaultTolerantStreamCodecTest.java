package com.comet.opik.infrastructure.redis;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.client.codec.Codec;
import org.redisson.codec.JsonJacksonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(undecodable.cause()).isNotNull();
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
        assertThat(tolerant.getMapKeyDecoder()).isSameAs(delegate.getMapKeyDecoder());
        assertThat(tolerant.getClassLoader()).isSameAs(delegate.getClassLoader());
    }

    @Test
    @DisplayName("the shipped JAVA codec is fault tolerant")
    void shippedJavaCodecIsFaultTolerant() throws IOException {
        // Guards the wiring, not the wrapper: the enum must hand the stream a decoder that cannot throw.
        var decoded = RedisStreamCodec.JAVA.getCodec().getMapValueDecoder().decode(json("{not json"), null);

        assertThat(decoded).isInstanceOf(UndecodableStreamMessage.class);
    }
}
