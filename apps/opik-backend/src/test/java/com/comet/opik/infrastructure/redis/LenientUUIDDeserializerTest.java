package com.comet.opik.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LenientUUIDDeserializer accepts both plain-string and WRAPPER_ARRAY forms")
class LenientUUIDDeserializerTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new SimpleModule().addDeserializer(UUID.class, LenientUUIDDeserializer.INSTANCE));
    }

    @Test
    @DisplayName("plain string form")
    void plainString() throws Exception {
        var json = "{\"experimentId\":\"6caf374f-6568-4c6f-aad0-257e0c7296a4\"}";
        var result = mapper.readValue(json, Holder.class);
        assertThat(result.experimentId).isEqualTo(UUID.fromString("6caf374f-6568-4c6f-aad0-257e0c7296a4"));
    }

    @Test
    @DisplayName("As.WRAPPER_ARRAY form")
    void wrapperArray() throws Exception {
        var json = "{\"experimentId\":[\"java.util.UUID\",\"6caf374f-6568-4c6f-aad0-257e0c7296a4\"]}";
        var result = mapper.readValue(json, Holder.class);
        assertThat(result.experimentId).isEqualTo(UUID.fromString("6caf374f-6568-4c6f-aad0-257e0c7296a4"));
    }

    @Test
    @DisplayName("plain string form survives round-trip")
    void plainStringRoundTrip() throws Exception {
        var original = new Holder(UUID.fromString("019d4245-7895-7fb0-b8e4-06eec6f6f3a9"));
        var json = mapper.writeValueAsString(original);
        var roundTrip = mapper.readValue(json, Holder.class);
        assertThat(roundTrip.experimentId).isEqualTo(original.experimentId);
    }

    @Test
    @DisplayName("wrapper array with extra trailing elements is rejected, not silently accepted")
    void wrapperArrayWithExtraElementsIsRejected() {
        var json = "{\"experimentId\":[\"java.util.UUID\",\"6caf374f-6568-4c6f-aad0-257e0c7296a4\",\"extra\"]}";
        assertThatThrownBy(() -> mapper.readValue(json, Holder.class))
                .isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("Expected end of UUID wrapper array");
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class Holder {
        private UUID experimentId;
    }
}
