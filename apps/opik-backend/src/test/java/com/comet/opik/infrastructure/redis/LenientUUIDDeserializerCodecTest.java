package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.events.ExperimentAggregationMessage;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.codec.JsonJacksonCodec;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Verifies the lenient UUID deserializer survives the production-shape codec
 * (Redisson {@link JsonJacksonCodec} on top of the configured {@link JsonUtils}
 * mapper with default typing enabled), not just a vanilla {@link ObjectMapper}.
 * Wire format observed in this configuration:
 * <pre>
 * {"@class":"...","experiment_id":["java.util.UUID","&lt;uuid&gt;"], ...}
 * </pre>
 * Field names are snake_case because of {@code JsonUtils.MAPPER}'s naming strategy,
 * and UUID is wrapped under {@code As.WRAPPER_ARRAY}. Old stuck messages instead
 * carry the UUID as a bare string {@code "experiment_id":"<uuid>"}; the lenient
 * deserializer must accept both.
 */
@DisplayName("LenientUUIDDeserializer integrated with JsonJacksonCodec (default typing on)")
class LenientUUIDDeserializerCodecTest {

    private static final UUID UUID_VALUE = UUID.fromString("6caf374f-6568-4c6f-aad0-257e0c7296a4");
    private static final String CLASS_HEADER = "\"@class\":\"com.comet.opik.api.events.ExperimentAggregationMessage\"";

    private static JsonJacksonCodec buildCodec() {
        ObjectMapper mapper = JsonUtils.getMapper().copy();
        mapper.registerModule(new SimpleModule().addDeserializer(UUID.class, LenientUUIDDeserializer.INSTANCE));
        return new JsonJacksonCodec(mapper);
    }

    @Test
    @DisplayName("publish: production-shape codec encodes UUID under As.WRAPPER_ARRAY default typing")
    void publishProducesWrapperArrayUuid() throws Exception {
        var codec = buildCodec();
        var msg = ExperimentAggregationMessage.builder()
                .experimentId(UUID_VALUE).workspaceId("ws").userName("user").build();

        ByteBuf encoded = codec.getValueEncoder().encode(msg);
        String wire = encoded.toString(CharsetUtil.UTF_8);

        assertThat(wire)
                .as("Default typing under JsonJacksonCodec wraps UUID in [\"java.util.UUID\",\"...\"]")
                .contains("\"experiment_id\":[\"java.util.UUID\",\"" + UUID_VALUE + "\"]");
    }

    @Test
    @DisplayName("read: production-shape codec decodes a plain-string UUID payload (legacy stuck format)")
    void readDecodesPlainStringUuid() throws Exception {
        var codec = buildCodec();
        String raw = "{" + CLASS_HEADER + ","
                + "\"experiment_id\":\"" + UUID_VALUE + "\","
                + "\"workspace_id\":\"ws\",\"user_name\":\"user\"}";

        Object decoded = codec.getValueDecoder().decode(
                Unpooled.wrappedBuffer(raw.getBytes(StandardCharsets.UTF_8)), null);

        assertThat(decoded).isInstanceOf(ExperimentAggregationMessage.class);
        assertThat(((ExperimentAggregationMessage) decoded).experimentId()).isEqualTo(UUID_VALUE);
    }

    @Test
    @DisplayName("read: production-shape codec round-trips an As.WRAPPER_ARRAY UUID payload (current format)")
    void readDecodesWrapperArrayUuid() {
        var codec = buildCodec();
        String raw = "{" + CLASS_HEADER + ","
                + "\"experiment_id\":[\"java.util.UUID\",\"" + UUID_VALUE + "\"],"
                + "\"workspace_id\":\"ws\",\"user_name\":\"user\"}";

        assertThatNoException().isThrownBy(() -> {
            Object decoded = codec.getValueDecoder().decode(
                    Unpooled.wrappedBuffer(raw.getBytes(StandardCharsets.UTF_8)), null);
            assertThat(decoded).isInstanceOf(ExperimentAggregationMessage.class);
            assertThat(((ExperimentAggregationMessage) decoded).experimentId()).isEqualTo(UUID_VALUE);
        });
    }
}
